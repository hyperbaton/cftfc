package com.hyperbaton.cftfc.need.satisfaction;

import com.hyperbaton.cft.entity.ai.memory.CftMemoryModuleType;
import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.need.satisfaction.NeedSatisfier;
import com.hyperbaton.cft.util.ErrandUtil;
import com.hyperbaton.cft.util.NeedUtil;
import com.hyperbaton.cftfc.ingredient.NutrientIngredient;
import com.hyperbaton.cftfc.need.NutritionNeed;
import net.dries007.tfc.common.component.food.Nutrient;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Keeps a Xoonglin's diet balanced. When the need comes due, it eats the food it carries that best balances
 * its last meals, but only food that keeps its diet balanced or makes it better, so it doesn't eat its way
 * through food that doesn't help. While its diet lacks a nutrient, it fetches food rich in it from its home.
 *
 * <p>Its last meals are kept in the Xoonglin's persistent data, since a need satisfier only saves how
 * satisfied it is.
 */
public class NutritionNeedSatisfier extends NeedSatisfier<NutritionNeed> {

    private static final String TAG_MEALS = "cftfc:meals";

    public NutritionNeedSatisfier(double satisfaction, boolean isSatisfied, NutritionNeed need) {
        super(satisfaction, isSatisfied, need);
    }

    @Override
    public boolean satisfy(XoonglinEntity mob) {
        NutritionNeed need = getNeed();
        List<float[]> meals = loadMeals(mob);
        // What it remembers after one more meal: the oldest one is forgotten once its memory is full
        List<float[]> kept = keptAfterMeal(meals, need);

        SimpleContainer inventory = mob.getInventory();
        int bestSlot = -1;
        int bestCovered = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            float[] nutrients = nutrientsOf(inventory.getItem(i));
            if (nutrients == null) continue;
            List<float[]> after = new ArrayList<>(kept);
            after.add(nutrients);
            int covered = coveredNutrients(after, need);
            if (covered > bestCovered) {
                bestCovered = covered;
                bestSlot = i;
            }
        }

        int required = need.getNutrients().size();
        if (bestSlot >= 0 && (bestCovered == required || bestCovered > coveredNutrients(meals, need))) {
            float[] eaten = nutrientsOf(inventory.getItem(bestSlot));
            inventory.removeItem(bestSlot, 1);
            List<float[]> updated = new ArrayList<>(kept);
            updated.add(eaten);
            saveMeals(mob, updated);
            if (bestCovered == required) {
                super.satisfy(mob);
                return true;
            }
        }
        return failAndSeek(mob);
    }

    /** Asks for food rich in each nutrient its diet will lack, and goes to fetch it from its home. */
    @Override
    public void addMemoriesForSatisfaction(XoonglinEntity mob) {
        List<Ingredient> wanted = wantedFood(mob);
        List<Ingredient> supplies = new ArrayList<>(mob.getBrain().getMemory(CftMemoryModuleType.SUPPLIES_NEEDED.get())
                .orElse(List.of()));
        boolean changed = false;
        for (Ingredient ingredient : wanted) {
            if (!supplies.contains(ingredient)) {
                supplies.add(ingredient);
                changed = true;
            }
        }
        if (changed || !mob.getBrain().hasMemoryValue(CftMemoryModuleType.SUPPLIES_NEEDED.get())) {
            mob.getBrain().setMemory(CftMemoryModuleType.SUPPLIES_NEEDED.get(), supplies);
        }

        findContainerWithFood(mob, wanted).ifPresent(pos -> {
            mob.getBrain().setMemory(CftMemoryModuleType.HOME_CONTAINER.get(), pos);
            ErrandUtil.startUnlessCoolingDown(mob, ErrandUtil.SUPPLIES, CftMemoryModuleType.SUPPLY_COOLDOWN.get());
        });
    }

    /** Food rich in the nutrients its next meal should bring, or in any of them if its diet lacks none. */
    private List<Ingredient> wantedFood(XoonglinEntity mob) {
        NutritionNeed need = getNeed();
        List<float[]> kept = keptAfterMeal(loadMeals(mob), need);
        List<Nutrient> lacking = need.getNutrients().stream()
                .filter(nutrient -> totalOf(kept, nutrient) < need.getMinAmount())
                .toList();
        List<Nutrient> wanted = lacking.isEmpty() ? need.getNutrients() : lacking;
        return wanted.stream()
                .map(nutrient -> new NutrientIngredient(nutrient, need.getMinAmount()).toVanilla())
                .toList();
    }

    private static Optional<BlockPos> findContainerWithFood(XoonglinEntity mob, List<Ingredient> wanted) {
        return NeedUtil.supplySourcePositions(mob).stream()
                .filter(pos -> {
                    if (!(mob.level().getBlockEntity(pos) instanceof Container container)) return false;
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack stack = container.getItem(i);
                        if (!stack.isEmpty() && wanted.stream().anyMatch(ingredient -> ingredient.test(stack))) {
                            return true;
                        }
                    }
                    return false;
                })
                .findFirst();
    }

    // --- Its diet ---

    /** The nutrients the food provides, or null if it isn't food it can eat. */
    private static float[] nutrientsOf(ItemStack stack) {
        float[] nutrients = new float[Nutrient.TOTAL];
        boolean any = false;
        for (Nutrient nutrient : Nutrient.VALUES) {
            nutrients[nutrient.ordinal()] = NutrientIngredient.nutrientOf(stack, nutrient);
            any |= nutrients[nutrient.ordinal()] > 0f;
        }
        return any ? nutrients : null;
    }

    private static List<float[]> keptAfterMeal(List<float[]> meals, NutritionNeed need) {
        int keep = need.getMeals() - 1;
        return meals.size() > keep ? new ArrayList<>(meals.subList(meals.size() - keep, meals.size())) : meals;
    }

    /** How many of the nutrients it needs these meals provide enough of. */
    private static int coveredNutrients(List<float[]> meals, NutritionNeed need) {
        return (int) need.getNutrients().stream()
                .filter(nutrient -> totalOf(meals, nutrient) >= need.getMinAmount())
                .count();
    }

    private static float totalOf(List<float[]> meals, Nutrient nutrient) {
        float total = 0f;
        for (float[] meal : meals) {
            total += meal[nutrient.ordinal()];
        }
        return total;
    }

    // --- Its last meals, in the Xoonglin's persistent data ---

    private List<float[]> loadMeals(XoonglinEntity mob) {
        CompoundTag all = mob.getPersistentData().getCompound(TAG_MEALS);
        List<float[]> meals = new ArrayList<>();
        if (!all.contains(mealsKey(), Tag.TAG_INT_ARRAY)) return meals;
        int[] bits = all.getIntArray(mealsKey());
        for (int start = 0; start + Nutrient.TOTAL <= bits.length; start += Nutrient.TOTAL) {
            float[] meal = new float[Nutrient.TOTAL];
            for (int i = 0; i < Nutrient.TOTAL; i++) {
                meal[i] = Float.intBitsToFloat(bits[start + i]);
            }
            meals.add(meal);
        }
        return meals;
    }

    private void saveMeals(XoonglinEntity mob, List<float[]> meals) {
        int[] bits = new int[meals.size() * Nutrient.TOTAL];
        for (int m = 0; m < meals.size(); m++) {
            for (int i = 0; i < Nutrient.TOTAL; i++) {
                bits[m * Nutrient.TOTAL + i] = Float.floatToIntBits(meals.get(m)[i]);
            }
        }
        CompoundTag all = mob.getPersistentData().getCompound(TAG_MEALS);
        all.put(mealsKey(), new IntArrayTag(bits));
        mob.getPersistentData().put(TAG_MEALS, all);
    }

    /** Each nutrition need of the Xoonglin keeps its own meals. */
    private String mealsKey() {
        return getNeedId() != null ? getNeedId().toString() : "default";
    }
}
