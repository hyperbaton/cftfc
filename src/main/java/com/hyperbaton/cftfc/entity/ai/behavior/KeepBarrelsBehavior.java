package com.hyperbaton.cftfc.entity.ai.behavior;

import com.hyperbaton.cft.entity.ai.behavior.JobBehavior;
import com.hyperbaton.cft.entity.ai.behavior.WorkStep;
import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.structure.OpenAirPlatformBlockGroup;
import com.hyperbaton.cft.structure.Structure;
import com.hyperbaton.cft.util.ContainerUtil;
import com.hyperbaton.cft.world.StructuresData;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.hyperbaton.cftfc.job.BarrelChains;
import com.hyperbaton.cftfc.job.BarrelChains.Chain;
import com.hyperbaton.cftfc.job.BarrelChains.ItemPool;
import com.hyperbaton.cftfc.job.BarrelChains.Kind;
import com.hyperbaton.cftfc.job.BarrelChains.Step;
import com.hyperbaton.cftfc.job.BarrelKeeperJob;
import net.dries007.tfc.common.blockentities.BarrelBlockEntity;
import net.dries007.tfc.common.blocks.devices.BarrelBlock;
import net.dries007.tfc.config.TFCConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Makes a barrel keeper make liquids in the barrels of its yard. Each time it checks the barrels, it works out
 * the one thing to do next, does it, and checks again:
 * <ul>
 *     <li>unseals barrels that are done, and takes back what's left in their slots;</li>
 *     <li>moves on a barrel along the {@link Chain} to the liquid it's for: adds the items for the next step, and
 *     seals it if the step needs it, or brings a container of the second liquid the step needs;</li>
 *     <li>splits a barrel that holds more than a step can convert, so none goes to waste;</li>
 *     <li>starts a new batch in an empty barrel when there's less of a liquid than it keeps, filling it from the
 *     yard's sources one container at a time.</li>
 * </ul>
 * Liquids it makes stay in their barrels. It carries one fluid container, a TFC wooden bucket for instance, to
 * draw and pour liquids.
 */
public class KeepBarrelsBehavior extends JobBehavior<BarrelKeeperJob> {

    private static final int REPATH_INTERVAL = 40;
    /** Ticks between checks of the barrels while there's nothing to do. */
    private static final int CHECK_INTERVAL = 200;
    /** Ticks it spends at a barrel or a source before acting. */
    private static final int ACTION_DELAY = 10;
    /** How many times it paths to a place before giving up on reaching it. */
    private static final int MAX_REPATHS = 10;
    /** How close it has to be to a barrel or a source to use it. */
    private static final double REACH = 2.5;
    /** How close it has to be to the yard's key block to use the yard's chests. */
    private static final double STORAGE_REACH = 2.5;
    /** How often it works out again how to make each liquid, in ticks, in case recipes changed. */
    private static final long GOALS_REFRESH = 6000;
    /** How much of a second liquid it plans on carrying at once when it has no container to measure. */
    private static final int DEFAULT_CONTAINER_SIZE = 1000;
    /** How many things in a row it goes to do and can't before it waits a while. */
    private static final int MAX_FAILURES = 3;

    private static final TagKey<Block> AQUEDUCTS =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("tfc", "aqueducts"));

    private static final WorkStep WAITING_FOR_INGREDIENTS = step("waiting_for_ingredients");
    private static final WorkStep WAITING_FOR_CONTAINER = step("waiting_for_container");
    private static final WorkStep WAITING_FOR_BARRELS = step("waiting_for_barrels");
    private static final WorkStep WAITING_FOR_EMPTY_BARREL = step("waiting_for_empty_barrel");

    private enum State {
        CHECKING_BARRELS(step("checking_barrels")),
        GOING_TO_STORAGE(WorkStep.of("going_to_storage")),
        GOING_TO_SOURCE(step("drawing_liquid")),
        GOING_TO_BARREL(step("going_to_barrel")),
        WORKING_BARREL(step("tending_barrel")),
        WAITING(WorkStep.WAITING);

        /** Shown in the job tab while the behavior is in this state. */
        private final WorkStep step;

        State(WorkStep step) {
            this.step = step;
        }
    }

    /** One thing to do at a barrel. */
    private sealed interface Action permits Unseal, Seal, ClearSlot, AddItems, Move {
        BlockPos barrel();
    }

    private record Unseal(BlockPos barrel) implements Action {
    }

    private record Seal(BlockPos barrel) implements Action {
    }

    /** Takes back what's in the barrel's slot. */
    private record ClearSlot(BlockPos barrel) implements Action {
    }

    /** Puts items in the barrel's slot, all of the same kind, and seals it if asked to. */
    private record AddItems(BlockPos barrel, Ingredient ingredient, int count, boolean seal) implements Action {
    }

    /**
     * Carries a container of liquid from a source or another barrel to the barrel, and pours it in, or puts the
     * container in the barrel's slot when the liquid is the second one a step takes.
     */
    private record Move(BlockPos from, BlockPos barrel, Fluid fluid, int amount, boolean intoSlot) implements Action {
    }

    private record Candidate(int priority, Action action) {
    }

    /** A liquid it makes, whether it's one of its products, and the chains that make it. */
    private record Goal(Fluid fluid, boolean product, List<Chain> chains) {

        Optional<Chain> chainThrough(Fluid fluid) {
            return chains.stream().filter(chain -> chain.stageOf(fluid) >= 0).findFirst();
        }
    }

    private static WorkStep step(String name) {
        return new WorkStep("gui.cftfc.work_step." + name);
    }

    private State state;
    private WorkStep waitStep;
    private BlockPos keyBlock;
    private List<Goal> goals;
    private long goalsMadeAt;
    /** The liquid each barrel is on the way to. */
    private final Map<BlockPos, Fluid> assigned = new HashMap<>();
    /** How much of its starting liquid each new batch takes, while it's being filled. */
    private final Map<BlockPos, Integer> batches = new HashMap<>();
    private Action action;
    /** How many things in a row it went to do and couldn't. */
    private int failures;
    /** Whether it's been to the chests since it last got anything done at the barrels. */
    private boolean justStored;
    private int repathTimer;
    private int repaths;
    private int cooldown;

    public KeepBarrelsBehavior() {
        super(CftfcMemoryModuleTypes.MUST_KEEP_BARRELS.get(), BarrelKeeperJob.class, 2400);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, XoonglinEntity entity) {
        BarrelKeeperJob job = getJob(entity);
        return job != null && entity.getAssignedStructurePos(job.getRequiredStructureType()) != null;
    }

    @Override
    protected void start(ServerLevel level, XoonglinEntity entity, long gameTime) {
        BarrelKeeperJob job = getJob(entity);
        BlockPos yard = entity.getAssignedStructurePos(job.getRequiredStructureType());
        if (!yard.equals(keyBlock)) {
            assigned.clear();
            batches.clear();
        }
        keyBlock = yard;
        state = State.CHECKING_BARRELS;
        action = null;
        justStored = false;
        repathTimer = REPATH_INTERVAL;
        cooldown = 0;
    }

    @Override
    protected WorkStep workStep() {
        if (state == null) return null;
        return state == State.WAITING && waitStep != null ? waitStep : state.step;
    }

    @Override
    protected void tickWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        BarrelKeeperJob job = getJob(entity);
        if (job == null || keyBlock == null) return;

        switch (state) {
            case CHECKING_BARRELS -> tickChecking(level, entity, job, gameTime);
            case GOING_TO_STORAGE -> tickGoingToStorage(level, entity, job);
            case GOING_TO_SOURCE -> tickGoingToSource(level, entity);
            case GOING_TO_BARREL -> tickGoingToBarrel(entity);
            case WORKING_BARREL -> tickWorkingBarrel(level, entity);
            case WAITING -> tickWaiting(entity);
        }
    }

    /** It keeps what it carries when its shift ends, and uses it when it's back. */
    @Override
    protected void stopWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        entity.getNavigation().stop();
        action = null;
    }

    // --- Working out what to do ---

    private void tickChecking(ServerLevel level, XoonglinEntity entity, BarrelKeeperJob job, long gameTime) {
        Optional<Structure> yard = findYard(level, job);
        if (yard.isEmpty()) {
            startWaiting(WorkStep.WAITING);
            return;
        }
        if (goals == null || gameTime - goalsMadeAt > GOALS_REFRESH) {
            goals = findGoals(level, job);
            goalsMadeAt = gameTime;
        }
        List<BlockPos> barrels = findBarrels(level, yard.get());
        Map<Fluid, List<BlockPos>> sources = findSources(level, yard.get());
        List<Container> containers = ContainerUtil.findContainers(level, yard.get());

        List<Candidate> candidates = plan(level, entity, barrels, sources, containers);
        Vec3 position = entity.position();
        action = candidates.stream()
                .min(Comparator.comparingInt(Candidate::priority)
                        .thenComparingDouble(candidate -> Vec3.atCenterOf(candidate.action().barrel()).distanceToSqr(position)))
                .map(Candidate::action)
                .orElse(null);
        if (action == null) {
            startWaiting(waitStep);
            return;
        }
        startAction(level, entity, barrels);
    }

    /**
     * Everything it could do next at the barrels, the most urgent first: what it has to undo or finish, then
     * the barrels on their way to a liquid, then new batches. Sets {@link #waitStep} for when there's nothing.
     */
    private List<Candidate> plan(ServerLevel level, XoonglinEntity entity, List<BlockPos> barrels,
                                 Map<Fluid, List<BlockPos>> sources, List<Container> containers) {
        int capacity = TFCConfig.SERVER.barrelCapacity.get();
        int containerSize = containerSize(entity, containers);
        List<ItemStack> stacks = new ArrayList<>(carriedStacks(entity));
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize(); i++) stacks.add(container.getItem(i));
        }
        // The items it has, less what the barrels on their way will take
        ItemPool items = ItemPool.of(stacks);

        List<Candidate> candidates = new ArrayList<>();
        Map<Fluid, Integer> stock = new HashMap<>(), projected = new HashMap<>(), wanted = new HashMap<>(),
                promised = new HashMap<>();
        List<BlockPos> empty = new ArrayList<>(), working = new ArrayList<>();
        Set<Fluid> filling = new HashSet<>();
        boolean brewing = false;
        assigned.keySet().retainAll(barrels);
        batches.keySet().retainAll(barrels);

        // What each barrel holds, and what it's on the way to
        for (BlockPos pos : barrels) {
            BarrelBlockEntity barrel = barrelAt(level, pos);
            FluidStack fluid = tank(barrel);
            if (isSealed(barrel)) {
                if (isDone(barrel)) {
                    candidates.add(new Candidate(0, new Unseal(pos)));
                    continue;
                }
                brewing = true;
                Goal goal = goalFor(pos, fluid.getFluid());
                Optional<Chain> chain = goal == null ? Optional.empty() : goal.chainThrough(fluid.getFluid());
                if (chain.isPresent()) {
                    int stage = chain.get().stageOf(fluid.getFluid());
                    projected.merge(goal.fluid(), chain.get().project(stage, fluid.getAmount()), Integer::sum);
                    int brewed = chain.get().steps().get(stage).convert(fluid.getAmount());
                    BarrelChains.reserve(chain.get(), stage + 1, brewed, items);
                }
                continue;
            }
            if (!barrel.getInventory().getStackInSlot(BarrelBlockEntity.SLOT_ITEM).isEmpty()) {
                candidates.add(new Candidate(0, new ClearSlot(pos)));
                continue;
            }
            if (fluid.isEmpty()) {
                assigned.remove(pos);
                batches.remove(pos);
                empty.add(pos);
                continue;
            }
            Goal goal = goalFor(pos, fluid.getFluid());
            if (goal == null) continue;
            if (goal.fluid() == fluid.getFluid()) {
                stock.merge(goal.fluid(), fluid.getAmount(), Integer::sum);
                continue;
            }
            Chain chain = goal.chainThrough(fluid.getFluid()).orElseThrow();
            projected.merge(goal.fluid(), chain.project(chain.stageOf(fluid.getFluid()), fluid.getAmount()), Integer::sum);
            if (batches.containsKey(pos)) filling.add(goal.fluid());
            working.add(pos);
        }

        // The next step for each barrel on its way
        for (BlockPos pos : working) {
            FluidStack fluid = tank(barrelAt(level, pos));
            Fluid current = fluid.getFluid();
            int amount = fluid.getAmount();
            Goal goal = goalFor(pos, current);
            Chain chain = goal.chainThrough(current).orElseThrow();
            int stage = chain.stageOf(current);
            Step step = chain.steps().get(stage);

            Integer batch = batches.get(pos);
            if (batch != null && amount < batch) {
                Optional<BlockPos> source = nearest(sources.getOrDefault(current, List.of()), pos);
                if (source.isPresent()) {
                    BarrelChains.reserve(chain, stage, batch, items);
                    candidates.add(new Candidate(2, new Move(source.get(), pos, current, batch - amount, false)));
                    continue;
                }
            }
            batches.remove(pos);

            int fitting = BarrelChains.maxBatch(chain, stage, amount, capacity, containerSize, null);
            if (fitting == 0) continue;
            if (fitting < amount - amount % step.inputAmount()) {
                // More than the steps can convert: move the rest to a barrel of its own
                BlockPos spare = spareBarrel(level, empty, working, current, goal, pos, capacity);
                if (spare != null) {
                    empty.remove(spare);
                    assigned.put(spare, goal.fluid());
                    candidates.add(new Candidate(3, new Move(pos, spare, current, amount - fitting, false)));
                }
                continue;
            }

            switch (step.kind()) {
                case SEALED, INSTANT -> {
                    Optional<SizedIngredient> item = step.item();
                    if (item.isEmpty()) {
                        if (step.kind() == Kind.SEALED) candidates.add(new Candidate(4, new Seal(pos)));
                    } else {
                        int count = step.units(amount) * item.get().count();
                        if (items.take(item.get().ingredient(), count)) {
                            candidates.add(new Candidate(4, new AddItems(pos, item.get().ingredient(), count,
                                    step.kind() == Kind.SEALED)));
                        }
                    }
                }
                case INSTANT_FLUID -> {
                    SizedFluidIngredient added = step.addedFluid().orElseThrow();
                    int needed = step.units(amount) * added.amount();
                    List<Fluid> options = BarrelChains.fluidsOf(added);
                    Optional<BlockPos> supplier = supplier(level, barrels, options, needed, stock, promised);
                    if (supplier.isPresent()) {
                        Fluid supplied = tank(barrelAt(level, supplier.get())).getFluid();
                        promised.merge(supplied, needed, Integer::sum);
                        candidates.add(new Candidate(4, new Move(supplier.get(), pos, supplied, needed, true)));
                    } else {
                        // Make more of it
                        options.stream().filter(this::isGoal).findFirst()
                                .ifPresent(missing -> wanted.merge(missing, needed, Integer::sum));
                    }
                }
            }
            BarrelChains.reserve(chain, stage + 1, step.convert(amount), items);
        }

        // New batches in the empty barrels: first what barrels wait for, then the product there's least of
        List<Goal> order = new ArrayList<>();
        goals.stream()
                .filter(goal -> wanted.getOrDefault(goal.fluid(), 0) > stock.getOrDefault(goal.fluid(), 0)
                        + projected.getOrDefault(goal.fluid(), 0))
                .forEach(order::add);
        goals.stream()
                .filter(goal -> goal.product() && !order.contains(goal))
                .sorted(Comparator.comparingInt(goal -> stock.getOrDefault(goal.fluid(), 0)
                        + projected.getOrDefault(goal.fluid(), 0)))
                .forEach(order::add);
        boolean noRoom = false;
        for (Goal goal : order) {
            if (filling.contains(goal.fluid())) continue;
            if (empty.isEmpty()) {
                noRoom = noRoom || canStart(goal, sources, capacity, containerSize, items, stock);
                continue;
            }
            for (Chain chain : goal.chains()) {
                List<BlockPos> chainSources = sources.getOrDefault(chain.start(), List.of());
                if (chainSources.isEmpty() || !canSupply(chain, sources, capacity, containerSize, items, stock)) continue;
                int amount = BarrelChains.maxBatch(chain, 0, capacity, capacity, containerSize, items);
                if (amount == 0) continue;
                BlockPos barrel = nearest(empty, chainSources.get(0)).orElseThrow();
                empty.remove(barrel);
                assigned.put(barrel, goal.fluid());
                batches.put(barrel, amount);
                BarrelChains.reserve(chain, 0, amount, items);
                BlockPos source = nearest(chainSources, barrel).orElseThrow();
                candidates.add(new Candidate(5, new Move(source, barrel, chain.start(), amount, false)));
                break;
            }
        }

        waitStep = noRoom ? WAITING_FOR_EMPTY_BARREL : brewing ? WAITING_FOR_BARRELS : WAITING_FOR_INGREDIENTS;
        return candidates;
    }

    /** Whether it could start a batch of a liquid now, given an empty barrel. */
    private boolean canStart(Goal goal, Map<Fluid, List<BlockPos>> sources, int capacity, int containerSize,
                             ItemPool items, Map<Fluid, Integer> stock) {
        return goal.chains().stream().anyMatch(chain -> sources.containsKey(chain.start())
                && canSupply(chain, sources, capacity, containerSize, items, stock)
                && BarrelChains.maxBatch(chain, 0, capacity, capacity, containerSize, items) > 0);
    }

    /**
     * Whether it has, or can make, the second liquids a chain takes, so a batch doesn't get stuck on the way.
     * It doesn't look further than chains that take no second liquid themselves.
     */
    private boolean canSupply(Chain chain, Map<Fluid, List<BlockPos>> sources, int capacity, int containerSize,
                              ItemPool items, Map<Fluid, Integer> stock) {
        for (Fluid added : chain.addedFluids()) {
            if (stock.getOrDefault(added, 0) > 0) continue;
            boolean makeable = goal(added).map(goal -> goal.chains().stream().anyMatch(addedChain ->
                    sources.containsKey(addedChain.start()) && addedChain.addedFluids().isEmpty()
                            && BarrelChains.maxBatch(addedChain, 0, capacity, capacity, containerSize, items) > 0))
                    .orElse(false);
            if (!makeable) return false;
        }
        return true;
    }

    /**
     * The liquid a barrel holding this one is on the way to: the one it was on the way to, if this is still on
     * the way; this one, if it's a liquid it makes; or the first liquid it makes that this is on the way to.
     */
    private Goal goalFor(BlockPos pos, Fluid fluid) {
        Fluid previous = assigned.get(pos);
        if (previous != null) {
            Optional<Goal> goal = goal(previous)
                    .filter(candidate -> candidate.fluid() == fluid || candidate.chainThrough(fluid).isPresent());
            if (goal.isPresent()) return goal.get();
        }
        Optional<Goal> goal = goal(fluid).or(() -> goals.stream()
                .filter(candidate -> candidate.chainThrough(fluid).isPresent())
                .findFirst());
        goal.ifPresentOrElse(found -> assigned.put(pos, found.fluid()), () -> assigned.remove(pos));
        return goal.orElse(null);
    }

    private Optional<Goal> goal(Fluid fluid) {
        return goals.stream().filter(goal -> goal.fluid() == fluid).findFirst();
    }

    private boolean isGoal(Fluid fluid) {
        return goal(fluid).isPresent();
    }

    /**
     * The liquids it makes: the job's products, or every liquid a chain ends in; and the second liquids their
     * chains take, which it only makes when a barrel waits for them.
     */
    private static List<Goal> findGoals(ServerLevel level, BarrelKeeperJob job) {
        List<Fluid> products = job.getProducts().isEmpty() ? BarrelChains.endProducts(level) : job.getProducts();
        Map<Fluid, Goal> goals = new LinkedHashMap<>();
        for (Fluid product : products) {
            List<Chain> chains = BarrelChains.chainsFor(level, product);
            if (!chains.isEmpty()) goals.put(product, new Goal(product, true, chains));
        }
        List<Goal> toVisit = new ArrayList<>(goals.values());
        while (!toVisit.isEmpty()) {
            Goal goal = toVisit.remove(0);
            for (Chain chain : goal.chains()) {
                for (Fluid added : chain.addedFluids()) {
                    if (goals.containsKey(added)) continue;
                    Goal addedGoal = new Goal(added, false, BarrelChains.chainsFor(level, added));
                    goals.put(added, addedGoal);
                    toVisit.add(addedGoal);
                }
            }
        }
        return List.copyOf(goals.values());
    }

    /**
     * A barrel to move liquid into from one that holds too much: an empty one, or one holding the same liquid
     * on the way to the same place, with room for it.
     */
    private BlockPos spareBarrel(ServerLevel level, List<BlockPos> empty, List<BlockPos> working, Fluid fluid,
                                 Goal goal, BlockPos from, int capacity) {
        if (!empty.isEmpty()) return nearest(empty, from).orElseThrow();
        return working.stream()
                .filter(pos -> !pos.equals(from) && goal.fluid().equals(assigned.get(pos)))
                .filter(pos -> {
                    FluidStack held = tank(barrelAt(level, pos));
                    return held.getFluid() == fluid && held.getAmount() < capacity;
                })
                .findFirst().orElse(null);
    }

    /** A barrel holding enough of one of these liquids, not promised to another barrel, to pour some out. */
    private Optional<BlockPos> supplier(ServerLevel level, List<BlockPos> barrels, List<Fluid> fluids, int needed,
                                        Map<Fluid, Integer> stock, Map<Fluid, Integer> promised) {
        for (BlockPos pos : barrels) {
            BarrelBlockEntity barrel = barrelAt(level, pos);
            if (isSealed(barrel)) continue;
            FluidStack held = tank(barrel);
            if (held.isEmpty() || !fluids.contains(held.getFluid()) || held.getAmount() < needed) continue;
            int spare = stock.getOrDefault(held.getFluid(), 0) - promised.getOrDefault(held.getFluid(), 0);
            if (spare >= needed) return Optional.of(pos);
        }
        return Optional.empty();
    }

    // --- Doing it ---

    private void startAction(ServerLevel level, XoonglinEntity entity, List<BlockPos> barrels) {
        if (action instanceof AddItems addItems) {
            if (carriedKind(entity, addItems.ingredient()).count() >= addItems.count()) {
                goToBarrel();
            } else if (!justStored) {
                goToStorage();
            } else {
                startWaiting(WAITING_FOR_INGREDIENTS);
            }
        } else if (action instanceof Move move) {
            int slot = containerSlot(entity);
            if (slot < 0) {
                if (!justStored) {
                    goToStorage();
                } else {
                    startWaiting(WAITING_FOR_CONTAINER);
                }
                return;
            }
            IFluidHandlerItem container = containerHandler(entity, slot);
            FluidStack inside = container.getFluidInTank(0);
            if (!inside.isEmpty() && inside.getFluid() != move.fluid()) {
                emptyContainer(level, entity, slot, barrels);
                container = containerHandler(entity, slot);
                inside = container.getFluidInTank(0);
            }
            int wanted = move.intoSlot() ? move.amount() : Math.min(move.amount(), container.getTankCapacity(0));
            if (inside.getAmount() >= wanted) {
                goToBarrel();
            } else {
                state = State.GOING_TO_SOURCE;
                repathTimer = REPATH_INTERVAL;
                repaths = 0;
            }
        } else {
            goToBarrel();
        }
    }

    private void tickGoingToStorage(ServerLevel level, XoonglinEntity entity, BarrelKeeperJob job) {
        if (!moveNear(entity, keyBlock, STORAGE_REACH)) return;

        Optional<Structure> yard = findYard(level, job);
        if (yard.isEmpty()) {
            startWaiting(WorkStep.WAITING);
            return;
        }
        List<Container> containers = ContainerUtil.findContainers(level, yard.get());
        int keep = containerSlot(entity);
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (i != keep && !stack.isEmpty()) {
                inventory.setItem(i, ContainerUtil.insertIntoContainers(containers, stack));
            }
        }
        if (keep < 0) {
            takeContainer(entity, containers);
        }
        if (action instanceof AddItems addItems) {
            ItemStack kind = bestKind(entity, containers, addItems.ingredient()).sample();
            if (!kind.isEmpty()) {
                take(entity, containers, stack -> ItemStack.isSameItemSameComponents(stack, kind), addItems.count());
            }
        }

        justStored = true;
        action = null;
        state = State.CHECKING_BARRELS;
    }

    private void tickGoingToSource(ServerLevel level, XoonglinEntity entity) {
        if (!(action instanceof Move move)) {
            state = State.CHECKING_BARRELS;
            return;
        }
        if (!arrive(entity, move.from())) return;
        if (--cooldown > 0) return;

        int slot = containerSlot(entity);
        if (slot >= 0) {
            IFluidHandlerItem container = containerHandler(entity, slot);
            int wanted = move.intoSlot() ? move.amount() : Math.min(move.amount(), container.getTankCapacity(0));
            int missing = wanted - container.getFluidInTank(0).getAmount();
            if (missing > 0) {
                draw(level, move.from(), move.fluid(), missing, container);
                entity.getInventory().setItem(slot, container.getContainer());
                entity.swing(InteractionHand.MAIN_HAND);
            }
        }
        goToBarrel();
    }

    private void tickGoingToBarrel(XoonglinEntity entity) {
        if (action == null) {
            state = State.CHECKING_BARRELS;
            return;
        }
        if (arrive(entity, action.barrel())) {
            state = State.WORKING_BARREL;
        }
    }

    private void tickWorkingBarrel(ServerLevel level, XoonglinEntity entity) {
        if (--cooldown > 0) return;
        boolean done = action != null && level.getBlockEntity(action.barrel()) instanceof BarrelBlockEntity barrel
                && perform(level, entity, barrel);
        if (done) {
            failures = 0;
            justStored = false;
            entity.swing(InteractionHand.MAIN_HAND);
        } else if (++failures >= MAX_FAILURES) {
            // Things aren't as it planned them: don't keep trying right away
            failures = 0;
            startWaiting(WorkStep.WAITING);
            return;
        }
        action = null;
        state = State.CHECKING_BARRELS;
    }

    /** Does what it came to the barrel for; false if it turned out it couldn't. */
    private boolean perform(ServerLevel level, XoonglinEntity entity, BarrelBlockEntity barrel) {
        BlockPos pos = barrel.getBlockPos();
        IItemHandler slots = barrel.getInventory();
        switch (action) {
            case Unseal unseal -> {
                if (!isSealed(barrel)) return false;
                BarrelBlock.toggleSeal(level, pos, barrel.getBlockState());
                return true;
            }
            case Seal seal -> {
                if (isSealed(barrel) || tank(barrel).isEmpty()) return false;
                BarrelBlock.toggleSeal(level, pos, barrel.getBlockState());
                return true;
            }
            case ClearSlot clearSlot -> {
                if (isSealed(barrel)) return false;
                ItemStack taken = slots.extractItem(BarrelBlockEntity.SLOT_ITEM, 64, false);
                give(entity, taken);
                return !taken.isEmpty();
            }
            case AddItems addItems -> {
                if (isSealed(barrel) || !slots.getStackInSlot(BarrelBlockEntity.SLOT_ITEM).isEmpty()) return false;
                ItemStack kind = carriedKind(entity, addItems.ingredient()).sample();
                int left = addItems.count();
                SimpleContainer inventory = entity.getInventory();
                for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (kind.isEmpty() || !ItemStack.isSameItemSameComponents(stack, kind)) continue;
                    int amount = Math.min(left, stack.getCount());
                    ItemStack remainder = slots.insertItem(BarrelBlockEntity.SLOT_ITEM, stack.copyWithCount(amount), false);
                    int inserted = amount - remainder.getCount();
                    stack.shrink(inserted);
                    left -= inserted;
                }
                inventory.setChanged();
                ItemStack inside = slots.getStackInSlot(BarrelBlockEntity.SLOT_ITEM);
                // Only seal it with just the right items, or some of the liquid goes to waste
                if (addItems.seal() && left == 0 && inside.getCount() == addItems.count()) {
                    BarrelBlock.toggleSeal(level, pos, barrel.getBlockState());
                }
                return left < addItems.count();
            }
            case Move move -> {
                if (isSealed(barrel)) return false;
                int slot = containerSlot(entity);
                if (slot < 0) return false;
                IFluidHandlerItem container = containerHandler(entity, slot);
                if (container.getFluidInTank(0).getFluid() != move.fluid()) return false;
                if (move.intoSlot()) {
                    if (!slots.getStackInSlot(BarrelBlockEntity.SLOT_ITEM).isEmpty()) return false;
                    ItemStack held = entity.getInventory().getItem(slot);
                    ItemStack remainder = slots.insertItem(BarrelBlockEntity.SLOT_ITEM, held.copy(), false);
                    entity.getInventory().setItem(slot, remainder);
                    return remainder.isEmpty();
                }
                FluidStack poured = FluidUtil.tryFluidTransfer(barrel.getInventory(), container,
                        Math.min(move.amount(), container.getFluidInTank(0).getAmount()), true);
                entity.getInventory().setItem(slot, container.getContainer());
                return !poured.isEmpty();
            }
        }
    }

    private void tickWaiting(XoonglinEntity entity) {
        moveNear(entity, keyBlock, STORAGE_REACH);
        if (--cooldown <= 0) {
            justStored = false;
            state = State.CHECKING_BARRELS;
        }
    }

    private void goToStorage() {
        state = State.GOING_TO_STORAGE;
        repathTimer = REPATH_INTERVAL;
        repaths = 0;
    }

    private void goToBarrel() {
        state = State.GOING_TO_BARREL;
        repathTimer = REPATH_INTERVAL;
        repaths = 0;
        cooldown = ACTION_DELAY;
    }

    private void startWaiting(WorkStep reason) {
        state = State.WAITING;
        waitStep = reason;
        action = null;
        cooldown = CHECK_INTERVAL;
        repathTimer = REPATH_INTERVAL;
        repaths = 0;
    }

    /** Walks to a place until it's within reach; true once it is. Gives up and waits if it can't get there. */
    private boolean arrive(XoonglinEntity entity, BlockPos pos) {
        if (isNear(entity, pos, REACH)) {
            entity.getNavigation().stop();
            return true;
        }
        if (++repathTimer >= REPATH_INTERVAL || entity.getNavigation().isDone()) {
            repathTimer = 0;
            if (++repaths > MAX_REPATHS) {
                startWaiting(WorkStep.WAITING);
                return false;
            }
            Path path = entity.getNavigation().createPath(pos, 1);
            if (path != null) entity.getNavigation().moveTo(path, 1.0);
        }
        cooldown = ACTION_DELAY;
        return false;
    }

    // --- The yard ---

    private Optional<Structure> findYard(ServerLevel level, BarrelKeeperJob job) {
        return StructuresData.get(level).findByKeyBlock(keyBlock)
                .filter(structure -> structure.getStructureTypeId().equals(job.getRequiredStructureType()));
    }

    /** The places in the yard: its fence and the ground under it, its ground and what stands on it. */
    private static Set<BlockPos> yardPositions(Structure yard) {
        Set<BlockPos> positions = new LinkedHashSet<>(yard.getBlockPositions()
                .getOrDefault(OpenAirPlatformBlockGroup.BORDER.getKey(), List.of()));
        positions.addAll(yard.getBlockPositions()
                .getOrDefault(OpenAirPlatformBlockGroup.GROUND_PERIMETER.getKey(), List.of()));
        for (BlockPos ground : yard.getBlockPositions().getOrDefault(OpenAirPlatformBlockGroup.SURFACE.getKey(), List.of())) {
            positions.add(ground);
            positions.add(ground.above());
        }
        return positions;
    }

    private static List<BlockPos> findBarrels(ServerLevel level, Structure yard) {
        return yardPositions(yard).stream()
                .filter(pos -> level.getBlockEntity(pos) instanceof BarrelBlockEntity)
                .toList();
    }

    /**
     * Where the yard has liquid to draw that isn't in its barrels: water and other liquids' sources, which never
     * run dry, like a well or an aqueduct, and other blocks that hold liquid.
     */
    private static Map<Fluid, List<BlockPos>> findSources(ServerLevel level, Structure yard) {
        Map<Fluid, List<BlockPos>> sources = new HashMap<>();
        for (BlockPos pos : yardPositions(yard)) {
            Fluid endless = endlessFluid(level, pos);
            if (endless != null) {
                sources.computeIfAbsent(endless, fluid -> new ArrayList<>()).add(pos);
                continue;
            }
            if (level.getBlockEntity(pos) instanceof BarrelBlockEntity) continue;
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
            if (handler == null) continue;
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                FluidStack held = handler.getFluidInTank(tank);
                if (!held.isEmpty()) {
                    sources.computeIfAbsent(held.getFluid(), fluid -> new ArrayList<>()).add(pos);
                }
            }
        }
        return sources;
    }

    /**
     * The liquid a block holds that never runs dry, if any: a source block, like a well, or the water an aqueduct
     * carries.
     */
    private static Fluid endlessFluid(ServerLevel level, BlockPos pos) {
        FluidState fluidState = level.getFluidState(pos);
        if (fluidState.isEmpty()) return null;
        if (fluidState.isSource()) return fluidState.getType();
        if (level.getBlockState(pos).is(AQUEDUCTS) && fluidState.getType() instanceof FlowingFluid flowing) {
            return flowing.getSource();
        }
        return null;
    }

    /** Fills a container with liquid from a source or a barrel. */
    private static void draw(ServerLevel level, BlockPos from, Fluid fluid, int amount, IFluidHandlerItem container) {
        if (endlessFluid(level, from) == fluid) {
            container.fill(new FluidStack(fluid, amount), FluidAction.EXECUTE);
            return;
        }
        IFluidHandler handler = level.getBlockEntity(from) instanceof BarrelBlockEntity barrel
                ? barrel.getInventory() : level.getCapability(Capabilities.FluidHandler.BLOCK, from, null);
        if (handler != null) {
            FluidUtil.tryFluidTransfer(container, handler, new FluidStack(fluid, amount), true);
        }
    }

    private static BarrelBlockEntity barrelAt(ServerLevel level, BlockPos pos) {
        return (BarrelBlockEntity) level.getBlockEntity(pos);
    }

    private static FluidStack tank(BarrelBlockEntity barrel) {
        return barrel.getInventory().getFluidInTank(0);
    }

    private static boolean isSealed(BarrelBlockEntity barrel) {
        return barrel.getBlockState().getOptionalValue(BarrelBlock.SEALED).orElse(false);
    }

    private static boolean isDone(BarrelBlockEntity barrel) {
        return barrel.getRecipe() == null || barrel.getRecipe().isInfinite() || barrel.getRemainingTicks() <= 0;
    }

    private static Optional<BlockPos> nearest(List<BlockPos> positions, BlockPos to) {
        return positions.stream().min(Comparator.comparingDouble(pos -> pos.distSqr(to)));
    }

    // --- Its container ---

    /** The slot of the container it draws and pours liquids with, or -1 if it carries none. */
    private static int containerSlot(XoonglinEntity entity) {
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getCount() == 1 && stack.getCapability(Capabilities.FluidHandler.ITEM) != null) {
                return i;
            }
        }
        return -1;
    }

    private static IFluidHandlerItem containerHandler(XoonglinEntity entity, int slot) {
        return entity.getInventory().getItem(slot).getCapability(Capabilities.FluidHandler.ITEM);
    }

    /** How much its container carries, or the best one in the chests, for planning. */
    private static int containerSize(XoonglinEntity entity, List<Container> containers) {
        int slot = containerSlot(entity);
        if (slot >= 0) return containerHandler(entity, slot).getTankCapacity(0);
        int best = 0;
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                IFluidHandlerItem handler = container.getItem(i).getCapability(Capabilities.FluidHandler.ITEM);
                if (handler != null && handler.getTanks() > 0) best = Math.max(best, handler.getTankCapacity(0));
            }
        }
        return best > 0 ? best : DEFAULT_CONTAINER_SIZE;
    }

    /** Takes the empty container that carries the most from the chests. */
    private static void takeContainer(XoonglinEntity entity, List<Container> containers) {
        Container bestContainer = null;
        int bestSlot = -1, bestCapacity = 0;
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                IFluidHandlerItem handler = stack.getCapability(Capabilities.FluidHandler.ITEM);
                if (handler == null || handler.getTanks() == 0 || !handler.getFluidInTank(0).isEmpty()) continue;
                if (handler.getTankCapacity(0) > bestCapacity) {
                    bestContainer = container;
                    bestSlot = i;
                    bestCapacity = handler.getTankCapacity(0);
                }
            }
        }
        if (bestContainer != null) {
            give(entity, bestContainer.removeItem(bestSlot, 1));
            bestContainer.setChanged();
        }
    }

    /**
     * Empties its container of a liquid it doesn't need now: into a barrel of the same liquid with room, or onto
     * the ground.
     */
    private static void emptyContainer(ServerLevel level, XoonglinEntity entity, int slot, List<BlockPos> barrels) {
        IFluidHandlerItem container = containerHandler(entity, slot);
        Fluid fluid = container.getFluidInTank(0).getFluid();
        for (BlockPos pos : barrels) {
            BarrelBlockEntity barrel = barrelAt(level, pos);
            if (!isSealed(barrel) && tank(barrel).getFluid() == fluid) {
                FluidUtil.tryFluidTransfer(barrel.getInventory(), container, Integer.MAX_VALUE, true);
                if (container.getFluidInTank(0).isEmpty()) break;
            }
        }
        container.drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
        entity.getInventory().setItem(slot, container.getContainer());
    }

    // --- Items ---

    /** A kind of item, as a sample stack, and how many of it there are. */
    private record ItemKind(ItemStack sample, int count) {
        static final ItemKind NONE = new ItemKind(ItemStack.EMPTY, 0);
    }

    /** The kind of item matching an ingredient it has the most of, carried and in the chests. */
    private static ItemKind bestKind(XoonglinEntity entity, List<Container> containers, Ingredient ingredient) {
        List<ItemStack> stacks = new ArrayList<>(carriedStacks(entity));
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize(); i++) stacks.add(container.getItem(i));
        }
        return bestKind(stacks, ingredient);
    }

    private static ItemKind carriedKind(XoonglinEntity entity, Ingredient ingredient) {
        return bestKind(carriedStacks(entity), ingredient);
    }

    /** Items only go in a barrel's slot all of a kind, so it counts each kind on its own. */
    private static ItemKind bestKind(List<ItemStack> stacks, Ingredient ingredient) {
        List<ItemKind> kinds = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || !ingredient.test(stack)) continue;
            boolean counted = false;
            for (int i = 0; i < kinds.size(); i++) {
                ItemKind kind = kinds.get(i);
                if (ItemStack.isSameItemSameComponents(kind.sample(), stack)) {
                    kinds.set(i, new ItemKind(kind.sample(), kind.count() + stack.getCount()));
                    counted = true;
                    break;
                }
            }
            if (!counted) kinds.add(new ItemKind(stack.copyWithCount(1), stack.getCount()));
        }
        return kinds.stream().max(Comparator.comparingInt(ItemKind::count)).orElse(ItemKind.NONE);
    }

    private static List<ItemStack> carriedStacks(XoonglinEntity entity) {
        List<ItemStack> stacks = new ArrayList<>();
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) stacks.add(inventory.getItem(i));
        return stacks;
    }

    /** Puts an item in its inventory, dropping whatever doesn't fit. */
    private static void give(XoonglinEntity entity, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack leftover = entity.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            Containers.dropItemStack(entity.level(), entity.getX(), entity.getY(), entity.getZ(), leftover);
        }
    }

    private static void take(XoonglinEntity entity, List<Container> containers, Predicate<ItemStack> matches, int count) {
        int remaining = count;
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty() || !matches.test(stack)) continue;

                int amount = Math.min(remaining, stack.getCount());
                ItemStack leftover = entity.getInventory().addItem(container.removeItem(i, amount));
                if (!leftover.isEmpty()) {
                    container.setItem(i, leftover);
                }
                remaining -= amount - leftover.getCount();
            }
            container.setChanged();
            if (remaining <= 0) return;
        }
    }

    /** Walks to within reach of a place, stopping beside it; true once it's there. */
    private boolean moveNear(XoonglinEntity entity, BlockPos target, double reach) {
        if (isNear(entity, target, reach)) {
            entity.getNavigation().stop();
            return true;
        }
        if (++repathTimer >= REPATH_INTERVAL || entity.getNavigation().isDone()) {
            repathTimer = 0;
            Path path = entity.getNavigation().createPath(target, 1);
            if (path != null) {
                entity.getNavigation().moveTo(path, 1.0);
            }
        }
        return false;
    }

    private static boolean isNear(XoonglinEntity entity, BlockPos pos, double reach) {
        return entity.position().distanceTo(Vec3.atCenterOf(pos)) < reach;
    }
}
