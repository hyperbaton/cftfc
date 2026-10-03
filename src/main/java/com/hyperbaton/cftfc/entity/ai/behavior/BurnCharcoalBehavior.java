package com.hyperbaton.cftfc.entity.ai.behavior;

import com.hyperbaton.cft.entity.ai.behavior.JobBehavior;
import com.hyperbaton.cft.entity.ai.behavior.WorkStep;
import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.structure.Structure;
import com.hyperbaton.cft.util.ContainerUtil;
import com.hyperbaton.cft.world.StructuresData;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.hyperbaton.cftfc.job.CharcoalBurnerJob;
import com.hyperbaton.cftfc.job.CharcoalPit;
import com.hyperbaton.cftfc.job.CharcoalPit.Cell;
import com.hyperbaton.cftfc.job.CharcoalPit.CellState;
import net.dries007.tfc.common.blockentities.LogPileBlockEntity;
import net.dries007.tfc.common.blocks.CharcoalPileBlock;
import net.dries007.tfc.common.blocks.TFCBlocks;
import net.dries007.tfc.common.blocks.devices.BurningLogPileBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Makes a charcoal burner work its pit. Each time it checks the pit it does whatever comes next: while
 * the pit burns, it waits; once it's charcoal, it uncovers each hole, digs the charcoal out and fills
 * the hole with a new log pile, then covers it; when every hole holds a full, covered log pile, it
 * lights the pit. It fetches logs and cover blocks from the yard's chests, and stores the charcoal in
 * them.
 *
 * <p>It works the holes from the ground beside them, never standing in a hole or on a cover it may
 * remove. It uncovers a hole before taking anything out of it, since TFC dirt falls into an empty hole,
 * and never covers an empty one.
 */
public class BurnCharcoalBehavior extends JobBehavior<CharcoalBurnerJob> {

    private static final int REPATH_INTERVAL = 40;
    /** Ticks between checks of the pit while it waits. */
    private static final int CHECK_INTERVAL = 200;
    /** Ticks between actions at a hole: placing a log, a cover... */
    private static final int ACTION_INTERVAL = 10;
    /** How many times it paths to a place before giving up on reaching it. */
    private static final int MAX_REPATHS = 10;
    /** How close it has to be to the bottom of a hole to work it from the ground beside it. */
    private static final double PIT_REACH = 3.0;
    /** How close it has to be to the yard's key block to use the yard's chests. */
    private static final double STORAGE_REACH = 2.5;

    private static final WorkStep WAITING_FOR_CHARCOAL = step("waiting_for_charcoal");
    private static final WorkStep WAITING_FOR_LOGS = step("waiting_for_logs");
    private static final WorkStep WAITING_FOR_COVER = step("waiting_for_cover");

    private enum State {
        CHECKING_PIT(step("checking_pit")),
        GOING_TO_STORAGE(WorkStep.of("going_to_storage")),
        GOING_TO_HOLE(step("going_to_pit")),
        WORKING_HOLE(step("working_pit")),
        LIGHTING_PIT(step("lighting_pit")),
        WAITING(WorkStep.WAITING);

        /** Shown in the job tab while the behavior is in this state. */
        private final WorkStep step;

        State(WorkStep step) {
            this.step = step;
        }
    }

    private static WorkStep step(String name) {
        return new WorkStep("gui.cftfc.work_step." + name);
    }

    private State state;
    private WorkStep waitStep;
    private BlockPos keyBlock;
    private final List<Cell> todo = new ArrayList<>();
    private Cell currentCell;
    private BlockPos standingSpot;
    private boolean lightOnArrival;
    /** Whether it got anything done at the holes since it last checked the pit. */
    private boolean actedThisPass;
    private int repathTimer;
    private int repaths;
    private int cooldown;

    public BurnCharcoalBehavior() {
        super(CftfcMemoryModuleTypes.MUST_BURN_CHARCOAL.get(), CharcoalBurnerJob.class, 2400);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, XoonglinEntity entity) {
        CharcoalBurnerJob job = getJob(entity);
        return job != null && entity.getAssignedStructurePos(job.getRequiredStructureType()) != null;
    }

    @Override
    protected void start(ServerLevel level, XoonglinEntity entity, long gameTime) {
        CharcoalBurnerJob job = getJob(entity);
        keyBlock = entity.getAssignedStructurePos(job.getRequiredStructureType());
        state = State.CHECKING_PIT;
        todo.clear();
        currentCell = null;
        standingSpot = null;
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
        CharcoalBurnerJob job = getJob(entity);
        if (job == null || keyBlock == null) return;

        switch (state) {
            case CHECKING_PIT -> tickCheckingPit(level, entity, job);
            case GOING_TO_STORAGE -> tickGoingToStorage(level, entity, job);
            case GOING_TO_HOLE -> tickGoingToHole(level, entity, job);
            case WORKING_HOLE -> tickWorkingHole(level, entity, job);
            case LIGHTING_PIT -> tickLightingPit(level, entity, job);
            case WAITING -> tickWaiting(entity);
        }
    }

    /** It keeps what it carries when its shift ends, and uses it when it's back. */
    @Override
    protected void stopWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        entity.getNavigation().stop();
        todo.clear();
        currentCell = null;
        standingSpot = null;
    }

    private void tickCheckingPit(ServerLevel level, XoonglinEntity entity, CharcoalBurnerJob job) {
        actedThisPass = false;
        List<Cell> cells = findCells(level, job);
        if (cells.isEmpty()) {
            // No holes dug in the yard, or none that would keep the fire in
            startWaiting(WorkStep.WAITING);
            return;
        }
        if (cells.stream().anyMatch(cell -> CharcoalPit.stateOf(level, cell, job.getLogsPerPile()) == CellState.BURNING)) {
            startWaiting(WAITING_FOR_CHARCOAL);
            return;
        }

        todo.clear();
        cells.stream()
                .filter(cell -> CharcoalPit.stateOf(level, cell, job.getLogsPerPile()) != CellState.READY)
                .forEach(todo::add);

        if (carried(entity, BurnCharcoalBehavior::isCharcoal) > 0 || needsSupplies(level, entity, job)) {
            goToStorage();
        } else if (todo.isEmpty()) {
            // Every hole holds a full, covered log pile
            lightOnArrival = true;
            goToHole(level, entity, cells.get(0), cells);
        } else {
            goToNextHole(level, entity, job);
        }
    }

    private void tickGoingToStorage(ServerLevel level, XoonglinEntity entity, CharcoalBurnerJob job) {
        if (!moveNear(entity, keyBlock, STORAGE_REACH, keyBlock)) return;

        Optional<Structure> yard = findYard(level, job);
        if (yard.isEmpty()) {
            startWaiting(WorkStep.WAITING);
            return;
        }
        List<Container> containers = ContainerUtil.findContainers(level, yard.get());
        store(entity, containers, BurnCharcoalBehavior::isCharcoal);

        int logsNeeded = Math.min(job.getCarry(), logsNeeded(level, job)) - carried(entity, job::acceptsLog);
        take(entity, containers, job::acceptsLog, logsNeeded);
        int coversNeeded = coversNeeded(level) - carried(entity, job::acceptsCover);
        take(entity, containers, job::acceptsCover, coversNeeded);

        if (todo.isEmpty()) {
            state = State.CHECKING_PIT;
        } else if (canProgress(level, entity, job)) {
            goToNextHole(level, entity, job);
        } else {
            startWaiting(carried(entity, job::acceptsLog) == 0 && logsNeeded(level, job) > 0
                    ? WAITING_FOR_LOGS : WAITING_FOR_COVER);
        }
    }

    private void tickGoingToHole(ServerLevel level, XoonglinEntity entity, CharcoalBurnerJob job) {
        if (currentCell == null || standingSpot == null) {
            goToNextHole(level, entity, job);
            return;
        }
        if (isNear(entity, currentCell.pile(), PIT_REACH) && entity.getNavigation().isDone()) {
            entity.getNavigation().stop();
            state = lightOnArrival ? State.LIGHTING_PIT : State.WORKING_HOLE;
            cooldown = 0;
            return;
        }
        if (++repathTimer >= REPATH_INTERVAL || entity.getNavigation().isDone()) {
            repathTimer = 0;
            if (++repaths > MAX_REPATHS) {
                // Can't get there: leave this hole for later
                lightOnArrival = false;
                goToNextHole(level, entity, job);
                return;
            }
            entity.getNavigation().moveTo(standingSpot.getX() + 0.5, standingSpot.getY(), standingSpot.getZ() + 0.5, 1.0);
        }
    }

    private void tickWorkingHole(ServerLevel level, XoonglinEntity entity, CharcoalBurnerJob job) {
        if (!isNear(entity, currentCell.pile(), PIT_REACH)) {
            state = State.GOING_TO_HOLE;
            repathTimer = REPATH_INTERVAL;
            return;
        }
        if (--cooldown > 0) return;
        cooldown = ACTION_INTERVAL;

        if (workHole(level, entity, job, currentCell)) {
            actedThisPass = true;
            entity.swing(InteractionHand.MAIN_HAND);
        } else {
            goToNextHole(level, entity, job);
        }
    }

    /**
     * Does the next thing the hole needs, if it can.
     *
     * @return false once there's nothing more it can do there
     */
    private boolean workHole(ServerLevel level, XoonglinEntity entity, CharcoalBurnerJob job, Cell cell) {
        boolean covered = CharcoalPit.isCovered(level, cell);
        boolean hasLogs = carried(entity, job::acceptsLog) > 0;
        switch (CharcoalPit.stateOf(level, cell, job.getLogsPerPile())) {
            case CHARCOAL -> {
                if (covered) {
                    uncover(level, entity, cell);
                } else {
                    collectCharcoal(level, entity, cell);
                }
                return true;
            }
            case EMPTY -> {
                if (!hasLogs) return false;
                if (covered) {
                    uncover(level, entity, cell);
                } else {
                    placeLogPile(level, entity, job, cell);
                }
                return true;
            }
            case FILLING -> {
                if (hasLogs) {
                    if (covered) {
                        uncover(level, entity, cell);
                    } else {
                        addLog(level, entity, job, cell);
                    }
                    return true;
                }
                // Out of logs: cover what's there for now, rather than leave the hole open
                return !covered && cover(level, entity, job, cell);
            }
            case PILED -> {
                return cover(level, entity, job, cell);
            }
            default -> {
                return false;
            }
        }
    }

    private void tickLightingPit(ServerLevel level, XoonglinEntity entity, CharcoalBurnerJob job) {
        lightOnArrival = false;
        List<Cell> cells = findCells(level, job);
        // Only ever light a pit whose every hole is full and covered, or the fire gets out
        boolean ready = !cells.isEmpty() && cells.stream()
                .allMatch(cell -> CharcoalPit.stateOf(level, cell, job.getLogsPerPile()) == CellState.READY);
        if (!ready) {
            state = State.CHECKING_PIT;
            return;
        }
        for (Cell cell : cells) {
            BurningLogPileBlock.lightLogPile(level, cell.pile());
        }
        entity.swing(InteractionHand.MAIN_HAND);
        startWaiting(WAITING_FOR_CHARCOAL);
    }

    private void tickWaiting(XoonglinEntity entity) {
        moveNear(entity, keyBlock, STORAGE_REACH, keyBlock);
        if (--cooldown <= 0) {
            state = State.CHECKING_PIT;
        }
    }

    private void goToStorage() {
        state = State.GOING_TO_STORAGE;
        repathTimer = REPATH_INTERVAL;
        repaths = 0;
    }

    /** Moves on to the nearest hole left to work, or checks the pit again once there are none. */
    private void goToNextHole(ServerLevel level, XoonglinEntity entity, CharcoalBurnerJob job) {
        if (currentCell != null) {
            todo.remove(currentCell);
            currentCell = null;
        }
        if (todo.isEmpty()) {
            if (actedThisPass) {
                state = State.CHECKING_PIT;
            } else {
                // Couldn't reach or work any of the holes: don't keep trying right away
                startWaiting(WorkStep.WAITING);
            }
            return;
        }
        Vec3 position = entity.position();
        Cell next = todo.stream()
                .min(Comparator.comparingDouble(cell -> Vec3.atCenterOf(cell.pile()).distanceToSqr(position)))
                .orElseThrow();
        goToHole(level, entity, next, findCells(level, job));
    }

    private void goToHole(ServerLevel level, XoonglinEntity entity, Cell cell, List<Cell> cells) {
        currentCell = cell;
        standingSpot = findStandingSpot(level, cell, cells);
        state = State.GOING_TO_HOLE;
        repathTimer = REPATH_INTERVAL;
        repaths = 0;
        if (standingSpot == null) {
            // Nowhere to stand within reach: skip it
            lightOnArrival = false;
            goToNextHole(level, entity, getJob(entity));
        }
    }

    private void startWaiting(WorkStep reason) {
        state = State.WAITING;
        waitStep = reason;
        cooldown = CHECK_INTERVAL;
        repathTimer = REPATH_INTERVAL;
        repaths = 0;
    }

    /**
     * The nearest solid ground beside the yard's ground level from where it can reach the bottom of the
     * hole, never on top of a hole's cover, since it may take that cover off.
     */
    private static BlockPos findStandingSpot(ServerLevel level, Cell cell, List<Cell> cells) {
        Set<BlockPos> covers = cells.stream().map(Cell::cover).collect(Collectors.toSet());
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = cell.cover().offset(dx, 1, dz);
                BlockPos ground = feet.below();
                if (covers.contains(ground) || !level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) {
                    continue;
                }
                if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                    continue;
                }
                double distance = Vec3.atBottomCenterOf(feet).distanceTo(Vec3.atCenterOf(cell.pile()));
                if (distance < PIT_REACH && distance < bestDistance) {
                    best = feet;
                    bestDistance = distance;
                }
            }
        }
        return best;
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

    private Optional<Structure> findYard(ServerLevel level, CharcoalBurnerJob job) {
        return StructuresData.get(level).findByKeyBlock(keyBlock)
                .filter(structure -> structure.getStructureTypeId().equals(job.getRequiredStructureType()));
    }

    private List<Cell> findCells(ServerLevel level, CharcoalBurnerJob job) {
        return findYard(level, job).map(yard -> CharcoalPit.find(level, yard)).orElse(List.of());
    }

    // --- What the holes left to work need ---

    private int logsNeeded(ServerLevel level, CharcoalBurnerJob job) {
        int needed = 0;
        for (Cell cell : todo) {
            CellState cellState = CharcoalPit.stateOf(level, cell, job.getLogsPerPile());
            if (cellState == CellState.EMPTY || cellState == CellState.CHARCOAL) {
                needed += job.getLogsPerPile();
            } else if (cellState == CellState.FILLING) {
                needed += job.getLogsPerPile() - CharcoalPit.logCount(level, cell);
            }
        }
        return needed;
    }

    /** Covers for the holes left to work that don't have one; the others reuse theirs. */
    private int coversNeeded(ServerLevel level) {
        return (int) todo.stream().filter(cell -> !CharcoalPit.isCovered(level, cell)).count();
    }

    private boolean needsSupplies(ServerLevel level, XoonglinEntity entity, CharcoalBurnerJob job) {
        if (todo.isEmpty()) return false;
        int logsNeeded = Math.min(job.getCarry(), logsNeeded(level, job));
        return carried(entity, job::acceptsLog) < logsNeeded
                || carried(entity, job::acceptsCover) < coversNeeded(level);
    }

    /** Whether it can get anything done at the holes left with what it carries. */
    private boolean canProgress(ServerLevel level, XoonglinEntity entity, CharcoalBurnerJob job) {
        boolean hasLogs = carried(entity, job::acceptsLog) > 0;
        boolean hasCovers = carried(entity, job::acceptsCover) > 0;
        for (Cell cell : todo) {
            switch (CharcoalPit.stateOf(level, cell, job.getLogsPerPile())) {
                case CHARCOAL -> {
                    return true;
                }
                case EMPTY, FILLING -> {
                    if (hasLogs) return true;
                }
                case PILED -> {
                    if (hasCovers) return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    // --- Working a hole ---

    private static void uncover(ServerLevel level, XoonglinEntity entity, Cell cell) {
        BlockState cover = level.getBlockState(cell.cover());
        level.setBlockAndUpdate(cell.cover(), Blocks.AIR.defaultBlockState());
        give(entity, new ItemStack(cover.getBlock()));
    }

    private static void collectCharcoal(ServerLevel level, XoonglinEntity entity, Cell cell) {
        BlockState pile = level.getBlockState(cell.pile());
        int layers = pile.getValue(CharcoalPileBlock.LAYERS);
        level.setBlockAndUpdate(cell.pile(), Blocks.AIR.defaultBlockState());
        give(entity, new ItemStack(Items.CHARCOAL, layers));
    }

    private static void placeLogPile(ServerLevel level, XoonglinEntity entity, CharcoalBurnerJob job, Cell cell) {
        level.setBlockAndUpdate(cell.pile(), TFCBlocks.LOG_PILE.get().defaultBlockState());
        addLog(level, entity, job, cell);
    }

    private static void addLog(ServerLevel level, XoonglinEntity entity, CharcoalBurnerJob job, Cell cell) {
        if (!(level.getBlockEntity(cell.pile()) instanceof LogPileBlockEntity pile)) return;
        ItemStack log = findCarried(entity, job::acceptsLog);
        if (log.isEmpty()) return;
        for (int slot = 0; slot < pile.getInventory().getSlots(); slot++) {
            if (pile.getInventory().getStackInSlot(slot).isEmpty() && pile.isItemValid(slot, log)) {
                pile.getInventory().setStackInSlot(slot, log.split(1));
                pile.setAndUpdateSlots(slot);
                entity.getInventory().setChanged();
                return;
            }
        }
    }

    /** Covers the hole with a carried block that keeps the fire in; false if it carries none. */
    private static boolean cover(ServerLevel level, XoonglinEntity entity, CharcoalBurnerJob job, Cell cell) {
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !job.acceptsCover(stack)) continue;
            BlockState cover = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
            if (CharcoalPit.insulates(cover, level, cell.cover(), Direction.DOWN)) {
                level.setBlockAndUpdate(cell.cover(), cover);
                stack.shrink(1);
                inventory.setChanged();
                return true;
            }
        }
        return false;
    }

    // --- Its inventory and the yard's chests ---

    private static boolean isCharcoal(ItemStack stack) {
        return stack.is(Items.CHARCOAL);
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

    private static void store(XoonglinEntity entity, List<Container> containers, Predicate<ItemStack> matches) {
        if (containers.isEmpty()) return;
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matches.test(stack)) {
                inventory.setItem(i, ContainerUtil.insertIntoContainers(containers, stack));
            }
        }
    }
}
