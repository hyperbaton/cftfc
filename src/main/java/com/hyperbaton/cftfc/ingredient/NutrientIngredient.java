package com.hyperbaton.cftfc.ingredient;

import com.hyperbaton.cftfc.CftfcRegistry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.common.component.food.FoodCapability;
import net.dries007.tfc.common.component.food.IFood;
import net.dries007.tfc.common.component.food.Nutrient;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;

import java.util.stream.Stream;

/**
 * Matches TFC food that isn't rotten and provides at least {@code min} of a nutrient, such as bread for grain
 * or cheese for dairy. Usable wherever an ingredient is, as {@code {"type": "cftfc:nutrient", "nutrient": "dairy"}}.
 */
public record NutrientIngredient(Nutrient nutrient, float min) implements ICustomIngredient {

    public static final Codec<Nutrient> NUTRIENT_CODEC = StringRepresentable.fromEnum(Nutrient::values);
    public static final float DEFAULT_MIN = 0.5f;

    public static final MapCodec<NutrientIngredient> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            NUTRIENT_CODEC.fieldOf("nutrient").forGetter(NutrientIngredient::nutrient),
            Codec.floatRange(0f, Float.MAX_VALUE).optionalFieldOf("min", DEFAULT_MIN).forGetter(NutrientIngredient::min)
    ).apply(instance, NutrientIngredient::new));

    /** How much of the nutrient the food provides, or 0 if it isn't food or is rotten. */
    public static float nutrientOf(ItemStack stack, Nutrient nutrient) {
        if (stack.isEmpty() || !FoodCapability.has(stack) || FoodCapability.isRotten(stack)) return 0f;
        IFood food = FoodCapability.get(stack);
        return food == null ? 0f : food.getData().nutrient(nutrient);
    }

    @Override
    public boolean test(ItemStack stack) {
        float amount = nutrientOf(stack, nutrient);
        return amount > 0f && amount >= min;
    }

    @Override
    public Stream<ItemStack> getItems() {
        return BuiltInRegistries.ITEM.stream().map(ItemStack::new).filter(this::test);
    }

    @Override
    public boolean isSimple() {
        // Whether food matches depends on its data (it may be rotten), not only on its item
        return false;
    }

    @Override
    public IngredientType<?> getType() {
        return CftfcRegistry.NUTRIENT_INGREDIENT.get();
    }
}
