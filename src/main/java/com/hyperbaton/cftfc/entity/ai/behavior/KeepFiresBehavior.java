package com.hyperbaton.cftfc.entity.ai.behavior;

import com.hyperbaton.cft.CftConfig;
import com.hyperbaton.cft.entity.ai.behavior.JobBehavior;
import com.hyperbaton.cft.entity.ai.behavior.WorkStep;
import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.structure.Structure;
import com.hyperbaton.cft.util.ContainerUtil;
import com.hyperbaton.cft.world.StructuresData;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.hyperbaton.cftfc.job.FirekeeperJob;
import com.hyperbaton.cftfc.job.TendableFire;
import net.dries007.tfc.common.TFCTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Makes a firekeeper do its rounds: it checks which fires around its woodshed need tending, fetches fuel
 * from the woodshed, refuels and relights those fires one after another, nearest first, and brings back
 * the fuel it has left. Between rounds it waits at the woodshed.
 */
public class KeepFiresBehavior extends JobBehavior<FirekeeperJob> {

    private static final int REPATH_INTERVAL = 40;
    /** Ticks between checks for fires that need tending, while it waits at the woodshed. */
    private static final int CHECK_INTERVAL = 200;
    /** Ticks between putting fuel items in a fire, so a firepit has time to slide each one down. */
    private static final int FUEL_INTERVAL = 10;
    /** How many times it paths towards a fire before giving up on reaching it this round. */
    private static final int MAX_REPATHS = 10;
    /** How close it has to be to a fire to tend it. It stops beside the fire, not on it, so it doesn't burn. */
    private static final double FIRE_REACH = 2.5;

    private enum State {
        CHECKING_FIRES(step("checking_fires")),
        FETCHING_FUEL(step("fetching_fuel")),
        GOING_TO_FIRE(step("going_to_fire")),
        TENDING_FIRE(step("tending_fire")),
        RETURNING(WorkStep.HEADING_BACK),
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
    private BlockPos woodshed;
    private final List<BlockPos> fires = new ArrayList<>();
    private BlockPos currentFire;
    private int repathTimer;
    private int repaths;
    private int cooldown;

    public KeepFiresBehavior() {
        super(CftfcMemoryModuleTypes.MUST_KEEP_FIRES.get(), FirekeeperJob.class, 2400);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, XoonglinEntity entity) {
        FirekeeperJob job = getJob(entity);
        return job != null && entity.getAssignedStructurePos(job.getRequiredStructureType()) != null;
    }

    @Override
    protected void start(ServerLevel level, XoonglinEntity entity, long gameTime) {
        FirekeeperJob job = getJob(entity);
        woodshed = entity.getAssignedStructurePos(job.getRequiredStructureType());
        state = State.CHECKING_FIRES;
        fires.clear();
        currentFire = null;
        repathTimer = REPATH_INTERVAL;
        cooldown = 0;
    }

    @Override
    protected WorkStep workStep() {
        return state != null ? state.step : null;
    }

    @Override
    protected void tickWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        FirekeeperJob job = getJob(entity);
        if (job == null || woodshed == null) return;

        switch (state) {
            case CHECKING_FIRES -> tickCheckingFires(level, entity, job);
            case FETCHING_FUEL -> tickFetchingFuel(level, entity, job);
            case GOING_TO_FIRE -> tickGoingToFire(entity, job);
            case TENDING_FIRE -> tickTendingFire(level, entity, job);
            case RETURNING -> tickReturning(level, entity, job);
            case WAITING -> tickWaiting(entity);
        }
    }

    /**
     * It keeps the fuel it carries when its shift ends or something else calls it away, and burns it
     * on its next round.
     */
    @Override
    protected void stopWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        entity.getNavigation().stop();
        fires.clear();
        currentFire = null;
    }

    private void tickCheckingFires(ServerLevel level, XoonglinEntity entity, FirekeeperJob job) {
        fires.clear();
        fires.addAll(job.findFiresToTend(level, woodshed));
        if (fires.isEmpty()) {
            startWaiting();
        } else if (carriedFuel(entity, job) > 0) {
            goToNextFire(entity, job);
        } else {
            state = State.FETCHING_FUEL;
            repathTimer = REPATH_INTERVAL;
        }
    }

    private void tickFetchingFuel(ServerLevel level, XoonglinEntity entity, FirekeeperJob job) {
        if (!moveToWoodshed(entity)) return;

        findWoodshed(level, job).ifPresent(structure -> takeFuel(level, entity, job, structure));
        if (carriedFuel(entity, job) == 0) {
            // Nothing to burn in the woodshed: wait for someone to bring fuel
            startWaiting();
            return;
        }
        goToNextFire(entity, job);
    }

    private void tickGoingToFire(XoonglinEntity entity, FirekeeperJob job) {
        if (isNearFire(entity, currentFire)) {
            entity.getNavigation().stop();
            state = State.TENDING_FIRE;
            cooldown = 0;
            return;
        }
        if (++repathTimer >= REPATH_INTERVAL || entity.getNavigation().isDone()) {
            repathTimer = 0;
            if (++repaths > MAX_REPATHS) {
                // Can't reach it: leave it for the next round
                goToNextFire(entity, job);
                return;
            }
            // Stop beside the fire rather than walking into it
            Path path = entity.getNavigation().createPath(currentFire, 1);
            if (path != null) {
                entity.getNavigation().moveTo(path, 1.0);
            }
        }
    }

    private void tickTendingFire(ServerLevel level, XoonglinEntity entity, FirekeeperJob job) {
        if (!isNearFire(entity, currentFire)) {
            state = State.GOING_TO_FIRE;
            repathTimer = REPATH_INTERVAL;
            return;
        }
        if (--cooldown > 0) return;
        cooldown = FUEL_INTERVAL;

        Optional<TendableFire> fire = fireAt(level, currentFire);
        if (fire.isEmpty()) {
            goToNextFire(entity, job);
            return;
        }

        // Fill it up, one item at a time
        if (fire.get().fuelCount() < fire.get().fuelCapacity()) {
            ItemStack fuel = findCarriedFuel(entity, job, fire.get());
            if (!fuel.isEmpty()) {
                fire.get().insert(fuel);
                entity.getInventory().setChanged();
                entity.swing(InteractionHand.MAIN_HAND);
                return;
            }
        }

        if (!fire.get().isLit() && job.relights() && fire.get().light()) {
            entity.swing(InteractionHand.MAIN_HAND);
        }
        goToNextFire(entity, job);
    }

    private void tickReturning(ServerLevel level, XoonglinEntity entity, FirekeeperJob job) {
        if (!moveToWoodshed(entity)) return;

        findWoodshed(level, job).ifPresent(structure -> storeFuel(level, entity, job, structure));
        startWaiting();
    }

    private void tickWaiting(XoonglinEntity entity) {
        moveToWoodshed(entity);
        if (--cooldown <= 0) {
            state = State.CHECKING_FIRES;
        }
    }

    /** Moves on to the nearest fire left, fetching more fuel first if it has run out. */
    private void goToNextFire(XoonglinEntity entity, FirekeeperJob job) {
        if (currentFire != null) {
            fires.remove(currentFire);
            currentFire = null;
        }
        repathTimer = REPATH_INTERVAL;
        repaths = 0;

        if (fires.isEmpty()) {
            state = State.RETURNING;
        } else if (carriedFuel(entity, job) == 0) {
            state = State.FETCHING_FUEL;
        } else {
            Vec3 position = entity.position();
            currentFire = fires.stream()
                    .min(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(position)))
                    .orElseThrow();
            state = State.GOING_TO_FIRE;
        }
    }

    private void startWaiting() {
        state = State.WAITING;
        cooldown = CHECK_INTERVAL;
        repathTimer = REPATH_INTERVAL;
    }

    /** Walks to the woodshed's key block; true once it's there. */
    private boolean moveToWoodshed(XoonglinEntity entity) {
        if (entity.position().distanceTo(Vec3.atCenterOf(woodshed)) < CftConfig.CLOSE_ENOUGH_DISTANCE_TO_CONTAINER.get()) {
            entity.getNavigation().stop();
            return true;
        }
        if (++repathTimer >= REPATH_INTERVAL || entity.getNavigation().isDone()) {
            repathTimer = 0;
            entity.getNavigation().moveTo(woodshed.getX() + 0.5, woodshed.getY(), woodshed.getZ() + 0.5, 1.0);
        }
        return false;
    }

    private static boolean isNearFire(XoonglinEntity entity, BlockPos fire) {
        return fire != null && entity.position().distanceTo(Vec3.atCenterOf(fire)) < FIRE_REACH;
    }

    private static Optional<TendableFire> fireAt(ServerLevel level, BlockPos pos) {
        var blockEntity = level.getBlockEntity(pos);
        return blockEntity == null ? Optional.empty() : TendableFire.of(blockEntity);
    }

    private Optional<Structure> findWoodshed(ServerLevel level, FirekeeperJob job) {
        return StructuresData.get(level).findByKeyBlock(woodshed)
                .filter(structure -> structure.getStructureTypeId().equals(job.getRequiredStructureType()));
    }

    /** Fills up to its carrying capacity with fuel from the woodshed that the fires on its round burn. */
    private void takeFuel(ServerLevel level, XoonglinEntity entity, FirekeeperJob job, Structure structure) {
        int remaining = job.getCarry() - carriedFuel(entity, job);
        for (Container container : ContainerUtil.findContainers(level, structure)) {
            for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty() || !isFuel(job, stack) || !burnsOnRound(level, stack)) continue;

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

    /** Puts the fuel it carries back in the woodshed; whatever doesn't fit, it keeps. */
    private void storeFuel(ServerLevel level, XoonglinEntity entity, FirekeeperJob job, Structure structure) {
        List<Container> containers = ContainerUtil.findContainers(level, structure);
        if (containers.isEmpty()) return;
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && isFuel(job, stack)) {
                inventory.setItem(i, ContainerUtil.insertIntoContainers(containers, stack));
            }
        }
    }

    /** Whether any fire on this round can burn the item. */
    private boolean burnsOnRound(ServerLevel level, ItemStack stack) {
        return fires.stream().anyMatch(pos -> fireAt(level, pos).map(fire -> fire.acceptsFuel(stack)).orElse(false));
    }

    private static ItemStack findCarriedFuel(XoonglinEntity entity, FirekeeperJob job, TendableFire fire) {
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && job.acceptsFuel(stack) && fire.canInsert(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static int carriedFuel(XoonglinEntity entity, FirekeeperJob job) {
        SimpleContainer inventory = entity.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && isFuel(job, stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Fuel it may use: TFC firepit or forge fuel, and allowed by its job. */
    private static boolean isFuel(FirekeeperJob job, ItemStack stack) {
        return (stack.is(TFCTags.Items.FIREPIT_FUEL) || stack.is(TFCTags.Items.FORGE_FUEL)) && job.acceptsFuel(stack);
    }
}
