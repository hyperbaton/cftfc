package com.hyperbaton.cftfc.job;

import net.dries007.tfc.common.recipes.BarrelRecipe;
import net.dries007.tfc.common.recipes.InstantFluidBarrelRecipe;
import net.dries007.tfc.common.recipes.SealedBarrelRecipe;
import net.dries007.tfc.common.recipes.TFCRecipeTypes;
import net.dries007.tfc.common.recipes.ingredients.TFCIngredients;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Works out how to make a liquid in a barrel from TFC's barrel recipes.
 *
 * <p>Each of those recipes turns the liquid in a barrel into another: sealed with an item for a while (water
 * and barley flour make beer, beer and fruit make vinegar), at once with an item (water and salt make salt
 * water), or at once with a second liquid poured in from a container in the barrel's item slot (salt water
 * and vinegar make brine). A {@link Chain} is a series of these that turns a liquid the yard has, such as
 * water, into the one wanted, all in the same barrel. The second liquids some steps need are made in other
 * barrels, with chains of their own.
 *
 * <p>A barrel converts all of its liquid at each step, and whatever doesn't make up a whole recipe is lost,
 * so {@link #maxBatch} works out how much liquid a barrel can start a chain with for every step to convert
 * all of it: no more than the barrel holds, no more items than its slot holds, and no more of a second
 * liquid than one container carries.
 */
public final class BarrelChains {

    /** The most steps it chains to make a liquid. */
    private static final int MAX_STEPS = 4;
    /** The most items a barrel's slot holds. */
    private static final int SLOT_LIMIT = 64;

    private BarrelChains() {
    }

    public enum Kind {
        /** Sealed with an item, for a while. */
        SEALED,
        /** At once, with an item in the slot. */
        INSTANT,
        /** At once, with a container of a second liquid in the slot. */
        INSTANT_FLUID
    }

    /** One recipe turning the liquid in a barrel into another. */
    public record Step(Kind kind, BarrelRecipe recipe, Fluid input, Fluid output) {

        public int inputAmount() {
            return recipe.getInputFluid().amount();
        }

        public int outputAmount() {
            return recipe.getOutputFluid().getAmount();
        }

        /** The item it takes, for the steps that take one. */
        public Optional<SizedIngredient> item() {
            SizedIngredient item = recipe.getInputItem();
            return kind == Kind.INSTANT_FLUID || item == null || item == TFCIngredients.EMPTY_ITEM
                    ? Optional.empty() : Optional.of(item);
        }

        /** The second liquid it takes, for the steps that take one. */
        public Optional<SizedFluidIngredient> addedFluid() {
            return recipe instanceof InstantFluidBarrelRecipe fluidRecipe ? Optional.of(fluidRecipe.getAddedFluid()) : Optional.empty();
        }

        /** How many times the recipe goes into this much liquid. */
        public int units(int amount) {
            return amount / inputAmount();
        }

        /** How much liquid comes out of converting this much. */
        public int convert(int amount) {
            return units(amount) * outputAmount();
        }
    }

    /** Steps that turn a starting liquid into the one wanted, in the same barrel. */
    public record Chain(Fluid start, List<Step> steps) {

        public Fluid product() {
            return steps.get(steps.size() - 1).output();
        }

        /** The step that takes this liquid, if it's on the way. */
        public int stageOf(Fluid fluid) {
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i).input() == fluid) return i;
            }
            return -1;
        }

        /** How much product comes out of this much liquid at a stage. */
        public int project(int stage, int amount) {
            for (int i = stage; i < steps.size(); i++) {
                amount = steps.get(i).convert(amount);
            }
            return amount;
        }

        /** The second liquids its steps take. */
        public Set<Fluid> addedFluids() {
            Set<Fluid> fluids = new LinkedHashSet<>();
            for (Step step : steps) {
                step.addedFluid().ifPresent(added -> fluids.addAll(fluidsOf(added)));
            }
            return fluids;
        }
    }

    /** Every chain that makes a liquid, the shortest first. */
    public static List<Chain> chainsFor(Level level, Fluid product) {
        return chainsFor(allSteps(level), product);
    }

    private static List<Chain> chainsFor(List<Step> steps, Fluid product) {
        List<Chain> chains = new ArrayList<>();
        collect(steps, product, new ArrayList<>(), new HashSet<>(Set.of(product)), chains);
        chains.sort(Comparator.comparingInt(chain -> chain.steps().size()));
        return chains;
    }

    /**
     * The liquids barrels make that no barrel turns into another liquid: what a chain ends in, such as brine,
     * vinegar or tannin, rather than what it goes through on the way, such as salt water or cider.
     */
    public static List<Fluid> endProducts(Level level) {
        List<Step> steps = allSteps(level);
        Set<Fluid> inputs = new HashSet<>();
        steps.forEach(step -> inputs.add(step.input()));
        Set<Fluid> products = new LinkedHashSet<>();
        steps.stream().map(Step::output).filter(fluid -> !inputs.contains(fluid)).forEach(products::add);
        return List.copyOf(products);
    }

    private static void collect(List<Step> steps, Fluid target, List<Step> after, Set<Fluid> visited, List<Chain> chains) {
        if (after.size() >= MAX_STEPS) return;
        for (Step step : steps) {
            if (step.output() != target || visited.contains(step.input())) continue;
            List<Step> chain = new ArrayList<>();
            chain.add(step);
            chain.addAll(after);
            chains.add(new Chain(step.input(), List.copyOf(chain)));
            visited.add(step.input());
            collect(steps, step.input(), chain, visited, chains);
            visited.remove(step.input());
        }
    }

    /** Every barrel recipe that turns a liquid into another, and leaves nothing else in the barrel. */
    private static List<Step> allSteps(Level level) {
        List<Step> steps = new ArrayList<>();
        for (RecipeHolder<SealedBarrelRecipe> holder : level.getRecipeManager().getAllRecipesFor(TFCRecipeTypes.BARREL_SEALED.get())) {
            if (!holder.value().isInfinite()) addSteps(Kind.SEALED, holder.value(), steps);
        }
        level.getRecipeManager().getAllRecipesFor(TFCRecipeTypes.BARREL_INSTANT.get())
                .forEach(holder -> addSteps(Kind.INSTANT, holder.value(), steps));
        level.getRecipeManager().getAllRecipesFor(TFCRecipeTypes.BARREL_INSTANT_FLUID.get())
                .forEach(holder -> addSteps(Kind.INSTANT_FLUID, holder.value(), steps));
        return steps;
    }

    private static void addSteps(Kind kind, BarrelRecipe recipe, List<Step> steps) {
        FluidStack output = recipe.getOutputFluid();
        if (output.isEmpty() || !recipe.getOutputItem().getSingleStack(ItemStack.EMPTY).isEmpty()) return;
        for (Fluid input : fluidsOf(recipe.getInputFluid())) {
            if (input != output.getFluid()) {
                steps.add(new Step(kind, recipe, input, output.getFluid()));
            }
        }
    }

    /** The liquids an ingredient takes, as still liquids. */
    public static List<Fluid> fluidsOf(SizedFluidIngredient ingredient) {
        List<Fluid> fluids = new ArrayList<>();
        for (FluidStack stack : ingredient.getFluids()) {
            Fluid fluid = stack.getFluid();
            if (fluid.isSource(fluid.defaultFluidState()) && !fluids.contains(fluid)) fluids.add(fluid);
        }
        return fluids;
    }

    /**
     * The most liquid a barrel can take, at a stage of a chain, for every step from there to convert all of it.
     *
     * @param upTo           the most to consider
     * @param capacity       how much a barrel holds
     * @param containerSize  how much of a second liquid one container carries
     * @param items          the items it has for the steps, or null to leave items out of it
     * @return 0 if not even one recipe's worth fits
     */
    public static int maxBatch(Chain chain, int stage, int upTo, int capacity, int containerSize, ItemPool items) {
        int unit = chain.steps().get(stage).inputAmount();
        for (int amount = Math.min(upTo, capacity) / unit * unit; amount > 0; amount -= unit) {
            if (fits(chain, stage, amount, capacity, containerSize, items == null ? null : items.copy())) return amount;
        }
        return 0;
    }

    /**
     * Takes from the pool the items the steps from a stage on take for this much liquid, as far as there are
     * any; the barrels on their way count on them.
     */
    public static void reserve(Chain chain, int stage, int amount, ItemPool items) {
        for (int i = stage; i < chain.steps().size() && amount > 0; i++) {
            Step step = chain.steps().get(i);
            int units = step.units(amount);
            step.item().ifPresent(item -> items.take(item.ingredient(), units * item.count()));
            amount = step.convert(amount);
        }
    }

    private static boolean fits(Chain chain, int stage, int amount, int capacity, int containerSize, ItemPool items) {
        for (int i = stage; i < chain.steps().size(); i++) {
            Step step = chain.steps().get(i);
            if (amount > capacity || amount % step.inputAmount() != 0) return false;
            int units = step.units(amount);
            Optional<SizedIngredient> item = step.item();
            if (item.isPresent()) {
                int count = units * item.get().count();
                if (count > slotLimit(item.get().ingredient())
                        || (items != null && !items.take(item.get().ingredient(), count))) {
                    return false;
                }
            }
            Optional<SizedFluidIngredient> added = step.addedFluid();
            if (added.isPresent() && units * added.get().amount() > containerSize) return false;
            amount = step.convert(amount);
            if (amount <= 0 || amount > capacity) return false;
        }
        return true;
    }

    /**
     * The items it has, kind by kind. A barrel's slot takes only one kind of item at a time, so each step takes
     * all its items from the same kind; and two steps taking the same items, like apples for cider and then fruit
     * for vinegar, take them from the same count.
     */
    public static final class ItemPool {
        private final List<ItemStack> kinds = new ArrayList<>();
        private final List<Integer> counts = new ArrayList<>();

        public static ItemPool of(Iterable<ItemStack> stacks) {
            ItemPool pool = new ItemPool();
            for (ItemStack stack : stacks) {
                if (stack.isEmpty()) continue;
                int index = pool.indexOf(stack);
                if (index >= 0) {
                    pool.counts.set(index, pool.counts.get(index) + stack.getCount());
                } else {
                    pool.kinds.add(stack.copyWithCount(1));
                    pool.counts.add(stack.getCount());
                }
            }
            return pool;
        }

        public ItemPool copy() {
            ItemPool copy = new ItemPool();
            copy.kinds.addAll(kinds);
            copy.counts.addAll(counts);
            return copy;
        }

        /** The most there are of one kind matching an ingredient. */
        public int best(Ingredient ingredient) {
            int best = 0;
            for (int i = 0; i < kinds.size(); i++) {
                if (ingredient.test(kinds.get(i))) best = Math.max(best, counts.get(i));
            }
            return best;
        }

        /** Takes this many of the kind matching an ingredient it has most of; false if no kind has that many. */
        public boolean take(Ingredient ingredient, int count) {
            int bestIndex = -1;
            for (int i = 0; i < kinds.size(); i++) {
                if (ingredient.test(kinds.get(i)) && (bestIndex < 0 || counts.get(i) > counts.get(bestIndex))) {
                    bestIndex = i;
                }
            }
            if (bestIndex < 0 || counts.get(bestIndex) < count) return false;
            counts.set(bestIndex, counts.get(bestIndex) - count);
            return true;
        }

        private int indexOf(ItemStack stack) {
            for (int i = 0; i < kinds.size(); i++) {
                if (ItemStack.isSameItemSameComponents(kinds.get(i), stack)) return i;
            }
            return -1;
        }
    }

    /** How many of an ingredient's items fit in a barrel's slot. */
    public static int slotLimit(Ingredient ingredient) {
        ItemStack[] items = ingredient.getItems();
        return items.length == 0 ? SLOT_LIMIT : Math.min(SLOT_LIMIT, items[0].getMaxStackSize());
    }
}
