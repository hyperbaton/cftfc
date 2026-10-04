package com.hyperbaton.cftfc.entity.ai.behavior;

import com.hyperbaton.cft.entity.ai.behavior.JobBehavior;
import com.hyperbaton.cft.entity.ai.behavior.WorkStep;
import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.structure.OpenAirPlatformBlockGroup;
import com.hyperbaton.cft.structure.Structure;
import com.hyperbaton.cft.util.ContainerUtil;
import com.hyperbaton.cft.world.StructuresData;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.hyperbaton.cftfc.job.CropPlanner;
import com.hyperbaton.cftfc.job.CropPlanner.Candidate;
import com.hyperbaton.cftfc.job.CropPlanner.CropInfo;
import com.hyperbaton.cftfc.job.CropPlanner.Forecast;
import com.hyperbaton.cftfc.job.CropPlanner.Nutrients;
import com.hyperbaton.cftfc.job.FarmerJob;
import net.dries007.tfc.common.blockentities.IFarmland;
import net.dries007.tfc.common.blocks.crop.CropBlock;
import net.dries007.tfc.common.blocks.crop.CropHelpers;
import net.dries007.tfc.common.blocks.crop.DeadCropBlock;
import net.dries007.tfc.common.blocks.soil.FarmlandBlock;
import net.dries007.tfc.util.data.Fertilizer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Makes a farmer tend the farmland of its farm, and only that. Each time it checks the fields, it harvests
 * the ripe crops and clears the dead ones, sowing each block again right away when it carries the seed;
 * then it stores what it gathered in the farm's chests, takes the seeds and fertilizer for the empty
 * blocks, and sows them.
 *
 * <p>Connected farmland makes a plot, and all of a plot's empty blocks get the same crop, which it chooses
 * with a {@link CropPlanner} from the seeds the farm has, and keeps for a day.
 */
public class TendFieldsBehavior extends JobBehavior<FarmerJob> {

    private static final int REPATH_INTERVAL = 40;
    /** Ticks between checks of the fields while there's nothing to do. */
    private static final int CHECK_INTERVAL = 200;
    /** Ticks between actions at a block: harvesting, sowing... */
    private static final int ACTION_INTERVAL = 10;
    /** How many times it paths to a block before giving up on reaching it. */
    private static final int MAX_REPATHS = 10;
    /** How close it has to be to a crop to tend it. */
    private static final double CROP_REACH = 2.5;
    /** How close it has to be to the farm's key block to use the farm's chests. */
    private static final double STORAGE_REACH = 2.5;
    /** How long it keeps to the crop it chose for a plot, in ticks. */
    private static final long PLAN_DURATION = 24000;
    /** How long before it looks again for a crop for a plot it found none for, in ticks. */
    private static final long NO_PLAN_DURATION = 2400;

    private static final WorkStep TENDING_CROPS = step("tending_crops");
    private static final WorkStep CLEARING_DEAD_CROPS = step("clearing_dead_crops");
    private static final WorkStep WAITING_FOR_SEEDS = step("waiting_for_seeds");
    private static final WorkStep WAITING_FOR_SEASON = step("waiting_for_season");

    private enum State {
        CHECKING_FIELDS(WorkStep.of("checking_crops")),
        GOING_TO_STORAGE(WorkStep.of("going_to_storage")),
        GOING_TO_CROP(WorkStep.of("walking_to_crops")),
        WORKING_CROP(TENDING_CROPS),
        WAITING(WorkStep.WAITING);

        /** Shown in the job tab while the behavior is in this state. */
        private final WorkStep step;

        State(WorkStep step) {
            this.step = step;
        }
    }

    private enum Kind {
        HARVEST,
        CLEAR,
        SOW
    }

    /** Something to do at a block of farmland. */
    private record Task(BlockPos farmland, Kind kind) {
        BlockPos crop() {
            return farmland.above();
        }
    }

    /** The crop it chose for a plot, if any, and when. */
    private record Plan(Optional<CropInfo> crop, long madeAt) {
    }

    private static WorkStep step(String name) {
        return new WorkStep("gui.cftfc.work_step." + name);
    }

    private State state;
    private WorkStep waitStep;
    private BlockPos keyBlock;
    /** For each block of farmland, the plot it belongs to, named by one of its blocks. */
    private final Map<BlockPos, BlockPos> plotOf = new HashMap<>();
    private final Map<BlockPos, Plan> plans = new HashMap<>();
    private final List<Task> todo = new ArrayList<>();
    /** The blocks it found empty to sow, for the seeds and fertilizer it takes from the chests. */
    private final List<Task> toSow = new ArrayList<>();
    private Task currentTask;
    /** Whether it got anything done at the fields since it last checked them. */
    private boolean actedThisPass;
    /** Whether it's been to the chests since it last got anything done at the fields. */
    private boolean justStored;
    private int repathTimer;
    private int repaths;
    private int cooldown;

    public TendFieldsBehavior() {
        super(CftfcMemoryModuleTypes.MUST_FARM.get(), FarmerJob.class, 2400);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, XoonglinEntity entity) {
        FarmerJob job = getJob(entity);
        return job != null && entity.getAssignedStructurePos(job.getRequiredStructureType()) != null;
    }

    @Override
    protected void start(ServerLevel level, XoonglinEntity entity, long gameTime) {
        FarmerJob job = getJob(entity);
        BlockPos farm = entity.getAssignedStructurePos(job.getRequiredStructureType());
        if (!farm.equals(keyBlock)) {
            plans.clear();
        }
        keyBlock = farm;
        state = State.CHECKING_FIELDS;
        todo.clear();
        currentTask = null;
        justStored = false;
        repathTimer = REPATH_INTERVAL;
        cooldown = 0;
    }

    @Override
    protected WorkStep workStep() {
        if (state == null) return null;
        if (state == State.WAITING && waitStep != null) return waitStep;
        if (state == State.WORKING_CROP && currentTask != null) {
            return switch (currentTask.kind()) {
                case HARVEST -> WorkStep.of("harvesting");
                case CLEAR -> CLEARING_DEAD_CROPS;
                case SOW -> WorkStep.of("planting");
            };
        }
        return state.step;
    }

    @Override
    protected void tickWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        FarmerJob job = getJob(entity);
        if (job == null || keyBlock == null) return;

        switch (state) {
            case CHECKING_FIELDS -> tickCheckingFields(level, entity, job, gameTime);
            case GOING_TO_STORAGE -> tickGoingToStorage(level, entity, job);
            case GOING_TO_CROP -> tickGoingToCrop(entity);
            case WORKING_CROP -> tickWorkingCrop(level, entity, job);
            case WAITING -> tickWaiting(entity);
        }
    }

    /** It keeps what it carries when its shift ends, and uses it when it's back. */
    @Override
    protected void stopWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        entity.getNavigation().stop();
        todo.clear();
        currentTask = null;
    }

    private void tickCheckingFields(ServerLevel level, XoonglinEntity entity, FarmerJob job, long gameTime) {
        actedThisPass = false;
        Optional<Structure> farm = findFarm(level, job);
        if (farm.isEmpty()) {
            startWaiting(WorkStep.WAITING);
            return;
        }
        List<BlockPos> farmland = findFarmland(level, farm.get());
        Map<BlockPos, List<BlockPos>> plots = findPlots(farmland);
        List<Container> containers = ContainerUtil.findContainers(level, farm.get());
        Map<Item, Integer> seeds = seedStock(entity, containers, job);
        Map<Fertilizer, Integer> fertilizers = fertilizerStock(entity, containers, job);

        todo.clear();
        toSow.clear();
        boolean unplanned = false;
        for (Map.Entry<BlockPos, List<BlockPos>> plot : plots.entrySet()) {
            // Blocks it'll sow this pass: the empty ones, and the ones it's about to harvest or clear
            List<BlockPos> open = new ArrayList<>();
            for (BlockPos block : plot.getValue()) {
                BlockState above = level.getBlockState(block.above());
                if (isRipe(above)) {
                    todo.add(new Task(block, Kind.HARVEST));
                    open.add(block);
                } else if (above.getBlock() instanceof DeadCropBlock) {
                    todo.add(new Task(block, Kind.CLEAR));
                    open.add(block);
                } else if (above.isAir()) {
                    toSow.add(new Task(block, Kind.SOW));
                    open.add(block);
                }
            }
            if (open.isEmpty()) continue;
            Plan plan = planFor(level, job, plot.getKey(), open, seeds, fertilizers, gameTime);
            if (plan.crop().isEmpty()) {
                unplanned = true;
            }
        }
        toSow.removeIf(task -> cropFor(task.farmland()).isEmpty());
        WorkStep idle = !unplanned ? WorkStep.WAITING : seeds.isEmpty() ? WAITING_FOR_SEEDS : WAITING_FOR_SEASON;

        if (todo.isEmpty()) {
            // Nothing to harvest or clear: store what it gathered, take what it needs, and sow
            boolean needsChests = carriesUnneeded(entity, job) || toSow.stream().anyMatch(task -> {
                Item seed = cropFor(task.farmland()).orElseThrow().seed();
                return carried(entity, stack -> stack.is(seed)) == 0;
            });
            if (needsChests && !justStored) {
                goToStorage();
                return;
            }
            toSow.stream()
                    .filter(task -> carried(entity, stack -> stack.is(cropFor(task.farmland()).orElseThrow().seed())) > 0)
                    .forEach(todo::add);
        } else if (!hasFreeSlot(entity) && !justStored) {
            goToStorage();
            return;
        }

        if (todo.isEmpty()) {
            startWaiting(idle);
        } else {
            goToNextTask(entity);
        }
    }

    private void tickGoingToStorage(ServerLevel level, XoonglinEntity entity, FarmerJob job) {
        if (!moveNear(entity, keyBlock, STORAGE_REACH, keyBlock)) return;

        Optional<Structure> farm = findFarm(level, job);
        if (farm.isEmpty()) {
            startWaiting(WorkStep.WAITING);
            return;
        }
        List<Container> containers = ContainerUtil.findContainers(level, farm.get());
        store(entity, containers);

        // The seeds for the blocks to sow, and the fertilizer it'll spread on them
        Map<Item, Integer> seedsNeeded = new HashMap<>();
        Map<Fertilizer, Integer> fertilizerAvailable = fertilizerStock(entity, containers, job);
        Map<Fertilizer, Integer> fertilizerNeeded = new IdentityHashMap<>();
        for (Task task : toSow) {
            Optional<CropInfo> crop = cropFor(task.farmland());
            if (crop.isEmpty()) continue;
            seedsNeeded.merge(crop.get().seed(), 1, Integer::sum);
            if (level.getBlockEntity(task.farmland()) instanceof IFarmland farmland) {
                for (Fertilizer fertilizer : CropPlanner.fertilizersFor(crop.get(), Nutrients.of(farmland), fertilizerAvailable)) {
                    fertilizerAvailable.merge(fertilizer, -1, Integer::sum);
                    fertilizerNeeded.merge(fertilizer, 1, Integer::sum);
                }
            }
        }
        seedsNeeded.forEach((seed, count) ->
                take(entity, containers, stack -> stack.is(seed), Math.min(count, job.getCarry())));
        int fertilizerLeft = job.getCarry();
        for (Map.Entry<Fertilizer, Integer> entry : fertilizerNeeded.entrySet()) {
            int count = Math.min(entry.getValue(), fertilizerLeft);
            take(entity, containers, stack -> job.fertilizerOf(stack) == entry.getKey(), count);
            fertilizerLeft -= count;
        }

        justStored = true;
        state = State.CHECKING_FIELDS;
    }

    private void tickGoingToCrop(XoonglinEntity entity) {
        if (currentTask == null) {
            state = State.CHECKING_FIELDS;
            return;
        }
        if (isNear(entity, currentTask.crop(), CROP_REACH)) {
            entity.getNavigation().stop();
            state = State.WORKING_CROP;
            cooldown = ACTION_INTERVAL;
            return;
        }
        if (++repathTimer >= REPATH_INTERVAL || entity.getNavigation().isDone()) {
            repathTimer = 0;
            if (++repaths > MAX_REPATHS) {
                // Can't get there: leave this block for later
                goToNextTask(entity);
                return;
            }
            BlockPos crop = currentTask.crop();
            entity.getNavigation().moveTo(crop.getX() + 0.5, crop.getY(), crop.getZ() + 0.5, 1.0);
        }
    }

    private void tickWorkingCrop(ServerLevel level, XoonglinEntity entity, FarmerJob job) {
        if (!isNear(entity, currentTask.crop(), CROP_REACH)) {
            state = State.GOING_TO_CROP;
            repathTimer = REPATH_INTERVAL;
            return;
        }
        if (--cooldown > 0) return;
        cooldown = ACTION_INTERVAL;

        BlockState above = level.getBlockState(currentTask.crop());
        switch (currentTask.kind()) {
            case HARVEST, CLEAR -> {
                boolean stillThere = currentTask.kind() == Kind.HARVEST
                        ? isRipe(above) : above.getBlock() instanceof DeadCropBlock;
                if (!stillThere) break;
                if (!hasFreeSlot(entity)) {
                    goToStorage();
                    return;
                }
                gather(level, entity, currentTask.crop());
                actedThisPass = true;
                justStored = false;
                // Sow it again right away when it carries the seed
                Optional<CropInfo> crop = cropFor(currentTask.farmland());
                if (crop.isPresent() && carried(entity, stack -> stack.is(crop.get().seed())) > 0) {
                    currentTask = new Task(currentTask.farmland(), Kind.SOW);
                    return;
                }
            }
            case SOW -> {
                Optional<CropInfo> crop = cropFor(currentTask.farmland());
                if (above.isAir() && crop.isPresent() && sow(level, entity, job, currentTask.farmland(), crop.get())) {
                    actedThisPass = true;
                    justStored = false;
                }
            }
        }
        goToNextTask(entity);
    }

    private void tickWaiting(XoonglinEntity entity) {
        moveNear(entity, keyBlock, STORAGE_REACH, keyBlock);
        if (--cooldown <= 0) {
            justStored = false;
            state = State.CHECKING_FIELDS;
        }
    }

    private void goToStorage() {
        state = State.GOING_TO_STORAGE;
        repathTimer = REPATH_INTERVAL;
        repaths = 0;
    }

    /** Moves on to the nearest block left to tend, or checks the fields again once there are none. */
    private void goToNextTask(XoonglinEntity entity) {
        if (currentTask != null) {
            todo.remove(currentTask);
            todo.removeIf(task -> task.farmland().equals(currentTask.farmland()));
            currentTask = null;
        }
        if (todo.isEmpty()) {
            if (actedThisPass) {
                state = State.CHECKING_FIELDS;
            } else {
                // Couldn't reach or tend any of the blocks: don't keep trying right away
                startWaiting(WorkStep.WAITING);
            }
            return;
        }
        Vec3 position = entity.position();
        currentTask = todo.stream()
                .min(Comparator.comparingDouble(task -> Vec3.atCenterOf(task.crop()).distanceToSqr(position)))
                .orElseThrow();
        state = State.GOING_TO_CROP;
        repathTimer = REPATH_INTERVAL;
        repaths = 0;
    }

    private void startWaiting(WorkStep reason) {
        state = State.WAITING;
        waitStep = reason;
        cooldown = CHECK_INTERVAL;
        repathTimer = REPATH_INTERVAL;
        repaths = 0;
    }

    // --- The fields ---

    private Optional<Structure> findFarm(ServerLevel level, FarmerJob job) {
        return StructuresData.get(level).findByKeyBlock(keyBlock)
                .filter(structure -> structure.getStructureTypeId().equals(job.getRequiredStructureType()));
    }

    /** The farm's farmland: the TFC farmland in its ground. */
    private static List<BlockPos> findFarmland(ServerLevel level, Structure farm) {
        return farm.getBlockPositions()
                .getOrDefault(OpenAirPlatformBlockGroup.SURFACE.getKey(), List.of()).stream()
                .filter(pos -> level.getBlockEntity(pos) instanceof IFarmland)
                .toList();
    }

    /** Splits the farmland into plots of blocks that touch, each named by one of its blocks. */
    private Map<BlockPos, List<BlockPos>> findPlots(List<BlockPos> farmland) {
        plotOf.clear();
        Set<BlockPos> left = new HashSet<>(farmland);
        Map<BlockPos, List<BlockPos>> plots = new HashMap<>();
        for (BlockPos start : farmland) {
            if (!left.remove(start)) continue;
            List<BlockPos> plot = new ArrayList<>();
            Deque<BlockPos> queue = new ArrayDeque<>(List.of(start));
            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                plot.add(pos);
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    BlockPos next = pos.relative(direction);
                    if (left.remove(next)) queue.add(next);
                }
            }
            // Named by its lowest block, so it keeps its name, and its plan, from one check to the next
            BlockPos key = plot.stream().min(Comparator.comparingLong(BlockPos::asLong)).orElseThrow();
            plot.forEach(pos -> plotOf.put(pos, key));
            plots.put(key, plot);
        }
        return plots;
    }

    /** The crop it chose for the plot this block belongs to, if any. */
    private Optional<CropInfo> cropFor(BlockPos farmland) {
        BlockPos plot = plotOf.get(farmland);
        Plan plan = plot != null ? plans.get(plot) : null;
        return plan != null ? plan.crop() : Optional.empty();
    }

    /**
     * The crop to sow in a plot's open blocks: the one it chose earlier if it's still fresh and it has the
     * seeds, or the one a {@link CropPlanner} chooses now from the seeds and fertilizer it has.
     */
    private Plan planFor(ServerLevel level, FarmerJob job, BlockPos plot, List<BlockPos> open,
                         Map<Item, Integer> seeds, Map<Fertilizer, Integer> fertilizers, long gameTime) {
        Plan plan = plans.get(plot);
        if (plan != null) {
            long age = gameTime - plan.madeAt();
            boolean fresh = plan.crop()
                    .map(crop -> age < PLAN_DURATION && seeds.getOrDefault(crop.seed(), 0) > 0)
                    .orElse(age < NO_PLAN_DURATION);
            if (fresh) return plan;
        }

        List<Candidate> candidates = new ArrayList<>();
        seeds.forEach((seed, count) -> CropInfo.of(seed).ifPresent(crop ->
                job.weightOf(seed).ifPresent(weight -> candidates.add(new Candidate(crop, weight)))));

        Optional<CropInfo> choice = Optional.empty();
        if (!candidates.isEmpty()) {
            List<Nutrients> soils = new ArrayList<>();
            float soilModifier = 0;
            BlockPos driest = open.get(0), wettest = open.get(0);
            int driestHydration = Integer.MAX_VALUE, wettestHydration = Integer.MIN_VALUE;
            for (BlockPos block : open) {
                if (level.getBlockEntity(block) instanceof IFarmland farmland) {
                    soils.add(Nutrients.of(farmland));
                }
                soilModifier += CropHelpers.getSoilModifier(level.getBlockState(block));
                int hydration = FarmlandBlock.getInstantHydration(level, block);
                if (hydration < driestHydration) {
                    driest = block;
                    driestHydration = hydration;
                }
                if (hydration > wettestHydration) {
                    wettest = block;
                    wettestHydration = hydration;
                }
            }
            soilModifier /= open.size();

            long growth = CropPlanner.growthTicks();
            List<Forecast> forecast = new ArrayList<>();
            for (int window = 0; window < job.getLookahead(); window++) {
                forecast.add(Forecast.of(level, open.get(0).above(), driest, wettest, window * growth, growth));
            }
            // What each block can get of the fertilizer there is, sharing it across the plot
            Map<Fertilizer, Integer> perBlock = new IdentityHashMap<>();
            fertilizers.forEach((fertilizer, count) -> {
                if (count / open.size() > 0) perBlock.put(fertilizer, count / open.size());
            });
            choice = new CropPlanner(candidates, forecast, soilModifier, job.getSoilWeight())
                    .choose(Nutrients.average(soils), perBlock);
        }
        plan = new Plan(choice, gameTime);
        plans.put(plot, plan);
        return plan;
    }

    /** Whether it's a ripe crop it knows how to harvest. */
    private static boolean isRipe(BlockState state) {
        return CropInfo.supports(state.getBlock()) && state.getBlock() instanceof CropBlock crop
                && state.getValue(crop.getAgeProperty()) >= crop.getMaxAge();
    }

    /** Harvests or clears a crop, keeping what it drops. */
    private static void gather(ServerLevel level, XoonglinEntity entity, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), entity, ItemStack.EMPTY);
        level.destroyBlock(pos, false, entity);
        drops.forEach(drop -> give(entity, drop));
        entity.swing(InteractionHand.MAIN_HAND);
    }

    /** Spreads the fertilizer the crop needs, from what it carries, and sows it; false if it can't. */
    private static boolean sow(ServerLevel level, XoonglinEntity entity, FarmerJob job, BlockPos farmlandPos, CropInfo crop) {
        BlockPos pos = farmlandPos.above();
        BlockState cropState = crop.block().defaultBlockState();
        ItemStack seed = findCarried(entity, stack -> stack.is(crop.seed()));
        if (seed.isEmpty() || !cropState.canSurvive(level, pos)
                || !(level.getBlockEntity(farmlandPos) instanceof IFarmland farmland)) {
            return false;
        }

        Map<Fertilizer, Integer> carried = new IdentityHashMap<>();
        forEachCarried(entity, stack -> {
            Fertilizer fertilizer = job.fertilizerOf(stack);
            if (fertilizer != null) carried.merge(fertilizer, stack.getCount(), Integer::sum);
        });
        for (Fertilizer fertilizer : CropPlanner.fertilizersFor(crop, Nutrients.of(farmland), carried)) {
            findCarried(entity, stack -> job.fertilizerOf(stack) == fertilizer).shrink(1);
            farmland.addNutrients(fertilizer);
            IFarmland.addNutrientParticles(level, pos, fertilizer);
        }

        level.setBlockAndUpdate(pos, cropState);
        seed.shrink(1);
        entity.getInventory().setChanged();
        entity.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    // --- Its inventory and the farm's chests ---

    private static Map<Item, Integer> seedStock(XoonglinEntity entity, List<Container> containers, FarmerJob job) {
        Map<Item, Integer> stock = new HashMap<>();
        Predicate<ItemStack> isSeed = stack -> CropInfo.of(stack.getItem()).isPresent()
                && job.weightOf(stack.getItem()).isPresent();
        forEachStack(entity, containers, stack -> {
            if (isSeed.test(stack)) stock.merge(stack.getItem(), stack.getCount(), Integer::sum);
        });
        return stock;
    }

    private static Map<Fertilizer, Integer> fertilizerStock(XoonglinEntity entity, List<Container> containers, FarmerJob job) {
        Map<Fertilizer, Integer> stock = new IdentityHashMap<>();
        forEachStack(entity, containers, stack -> {
            Fertilizer fertilizer = job.fertilizerOf(stack);
            if (fertilizer != null) stock.merge(fertilizer, stack.getCount(), Integer::sum);
        });
        return stock;
    }

    private static void forEachStack(XoonglinEntity entity, List<Container> containers, Consumer<ItemStack> action) {
        forEachCarried(entity, action);
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty()) action.accept(stack);
            }
        }
    }

    private static void forEachCarried(XoonglinEntity entity, Consumer<ItemStack> action) {
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) action.accept(stack);
        }
    }

    /** Whether it carries anything it won't use on the blocks to sow: what it harvested, or spare seeds. */
    private boolean carriesUnneeded(XoonglinEntity entity, FarmerJob job) {
        Set<Item> needed = new HashSet<>();
        toSow.forEach(task -> cropFor(task.farmland()).ifPresent(crop -> needed.add(crop.seed())));
        return !findCarried(entity, stack -> !needed.contains(stack.getItem())
                && (toSow.isEmpty() || job.fertilizerOf(stack) == null)).isEmpty();
    }

    private static boolean hasFreeSlot(XoonglinEntity entity) {
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) return true;
        }
        return false;
    }

    private static ItemStack findCarried(XoonglinEntity entity, Predicate<ItemStack> matches) {
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matches.test(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static int carried(XoonglinEntity entity, Predicate<ItemStack> matches) {
        SimpleContainer inventory = entity.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matches.test(stack)) count += stack.getCount();
        }
        return count;
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
        int remaining = count - carried(entity, matches);
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

    /** Stores everything it carries in the farm's chests, as far as it fits. */
    private static void store(XoonglinEntity entity, List<Container> containers) {
        if (containers.isEmpty()) return;
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                inventory.setItem(i, ContainerUtil.insertIntoContainers(containers, stack));
            }
        }
    }

    /** Walks to within reach of a place, stopping beside it; true once it's there. */
    private boolean moveNear(XoonglinEntity entity, BlockPos target, double reach, BlockPos pathTarget) {
        if (isNear(entity, target, reach)) {
            entity.getNavigation().stop();
            return true;
        }
        if (++repathTimer >= REPATH_INTERVAL || entity.getNavigation().isDone()) {
            repathTimer = 0;
            Path path = entity.getNavigation().createPath(pathTarget, 1);
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
