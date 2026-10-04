package com.hyperbaton.cftfc.entity.ai.behavior;

import com.hyperbaton.cft.entity.ai.behavior.JobBehavior;
import com.hyperbaton.cft.entity.ai.behavior.WorkStep;
import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.structure.Structure;
import com.hyperbaton.cft.util.ContainerUtil;
import com.hyperbaton.cft.world.StructuresData;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.hyperbaton.cftfc.job.ProspectorJob;
import net.dries007.tfc.common.blockentities.SluiceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Makes a prospector do its rounds: it checks which sluices around its camp need loading or have washed
 * something out, fetches ore deposits from the camp, goes to each of those sluices (nearest first),
 * picks up what they washed out and loads them with deposits, then brings what it found back to the
 * camp's chests. Between rounds it waits at the camp.
 *
 * <p>What it brings back is only what it picked up at the sluices, never the Xoonglin's own belongings.
 */
public class ProspectBehavior extends JobBehavior<ProspectorJob> {

    private static final int REPATH_INTERVAL = 40;
    /** Ticks between checks of the sluices, while it waits at the camp. */
    private static final int CHECK_INTERVAL = 400;
    /** Ticks between actions at a sluice. */
    private static final int ACTION_INTERVAL = 10;
    /** Deposits it puts in a sluice with each action. */
    private static final int DEPOSITS_PER_ACTION = 4;
    private static final int MAX_REPATHS = 10;
    private static final double REACH = 2.5;
    /** How far around a sluice and its water outlet it looks for what the sluice washed out. */
    private static final double LOOT_RANGE = 1.5;

    private enum State {
        CHECKING_SLUICES(step("checking_sluices")),
        FETCHING_DEPOSITS(step("fetching_deposits")),
        GOING_TO_SLUICE(step("going_to_sluice")),
        WORKING_SLUICE(step("working_sluice")),
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
    private BlockPos camp;
    private final List<BlockPos> sluices = new ArrayList<>();
    private BlockPos currentSluice;
    /** The kinds of items it has picked up at sluices, which it stores at the camp. */
    private final Set<Item> found = new HashSet<>();
    private int repathTimer;
    private int repaths;
    private int cooldown;

    public ProspectBehavior() {
        super(CftfcMemoryModuleTypes.MUST_PROSPECT.get(), ProspectorJob.class, 2400);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, XoonglinEntity entity) {
        ProspectorJob job = getJob(entity);
        return job != null && entity.getAssignedStructurePos(job.getRequiredStructureType()) != null;
    }

    @Override
    protected void start(ServerLevel level, XoonglinEntity entity, long gameTime) {
        ProspectorJob job = getJob(entity);
        camp = entity.getAssignedStructurePos(job.getRequiredStructureType());
        state = State.CHECKING_SLUICES;
        sluices.clear();
        currentSluice = null;
        repathTimer = REPATH_INTERVAL;
        cooldown = 0;
    }

    @Override
    protected WorkStep workStep() {
        return state != null ? state.step : null;
    }

    @Override
    protected void tickWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        ProspectorJob job = getJob(entity);
        if (job == null || camp == null) return;

        switch (state) {
            case CHECKING_SLUICES -> tickCheckingSluices(level, entity, job);
            case FETCHING_DEPOSITS -> tickFetchingDeposits(level, entity, job);
            case GOING_TO_SLUICE -> tickGoingToSluice(level, entity, job);
            case WORKING_SLUICE -> tickWorkingSluice(level, entity, job);
            case RETURNING -> tickReturning(level, entity, job);
            case WAITING -> tickWaiting(entity);
        }
    }

    /** It keeps what it carries when its shift ends, and stores or uses it on its next round. */
    @Override
    protected void stopWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        entity.getNavigation().stop();
        sluices.clear();
        currentSluice = null;
    }

    private void tickCheckingSluices(ServerLevel level, XoonglinEntity entity, ProspectorJob job) {
        sluices.clear();
        for (BlockPos pos : job.findWorkingSluices(level, camp)) {
            if (level.getBlockEntity(pos) instanceof SluiceBlockEntity sluice && needsVisit(level, sluice, job)) {
                sluices.add(pos);
            }
        }
        if (sluices.isEmpty()) {
            // Store anything still carried from an earlier round
            state = carriedFound(entity) > 0 ? State.RETURNING : State.WAITING;
            cooldown = CHECK_INTERVAL;
            repathTimer = REPATH_INTERVAL;
        } else if (anyNeedsLoading(level, job) && carriedDeposits(level, entity, job) < job.getCarry()) {
            state = State.FETCHING_DEPOSITS;
            repathTimer = REPATH_INTERVAL;
        } else {
            goToNextSluice(entity);
        }
    }

    private void tickFetchingDeposits(ServerLevel level, XoonglinEntity entity, ProspectorJob job) {
        if (!moveNear(entity, camp)) return;

        findCamp(level, job).ifPresent(structure -> {
            List<Container> containers = ContainerUtil.findContainers(level, structure);
            store(entity, containers);
            takeDeposits(level, entity, job, containers);
        });
        // Even without deposits, it still picks up what the sluices washed out
        goToNextSluice(entity);
    }

    private void tickGoingToSluice(ServerLevel level, XoonglinEntity entity, ProspectorJob job) {
        if (isNear(entity, currentSluice)) {
            entity.getNavigation().stop();
            state = State.WORKING_SLUICE;
            cooldown = 0;
            return;
        }
        if (++repathTimer >= REPATH_INTERVAL || entity.getNavigation().isDone()) {
            repathTimer = 0;
            if (++repaths > MAX_REPATHS) {
                // Can't reach it: leave it for the next round
                goToNextSluice(entity);
                return;
            }
            Path path = entity.getNavigation().createPath(currentSluice, 1);
            if (path != null) {
                entity.getNavigation().moveTo(path, 1.0);
            }
        }
    }

    private void tickWorkingSluice(ServerLevel level, XoonglinEntity entity, ProspectorJob job) {
        if (!isNear(entity, currentSluice)) {
            state = State.GOING_TO_SLUICE;
            repathTimer = REPATH_INTERVAL;
            return;
        }
        if (--cooldown > 0) return;
        cooldown = ACTION_INTERVAL;

        if (!(level.getBlockEntity(currentSluice) instanceof SluiceBlockEntity sluice)) {
            goToNextSluice(entity);
            return;
        }
        boolean acted = pickUpLoot(level, entity, sluice);
        acted |= loadDeposits(entity, job, sluice);
        if (acted) {
            entity.swing(InteractionHand.MAIN_HAND);
        } else {
            goToNextSluice(entity);
        }
    }

    private void tickReturning(ServerLevel level, XoonglinEntity entity, ProspectorJob job) {
        if (!moveNear(entity, camp)) return;

        findCamp(level, job).ifPresent(structure -> store(entity, ContainerUtil.findContainers(level, structure)));
        startWaiting();
    }

    private void tickWaiting(XoonglinEntity entity) {
        moveNear(entity, camp);
        if (--cooldown <= 0) {
            state = State.CHECKING_SLUICES;
        }
    }

    private void goToNextSluice(XoonglinEntity entity) {
        if (currentSluice != null) {
            sluices.remove(currentSluice);
            currentSluice = null;
        }
        repathTimer = REPATH_INTERVAL;
        repaths = 0;
        if (sluices.isEmpty()) {
            state = State.RETURNING;
            return;
        }
        Vec3 position = entity.position();
        currentSluice = sluices.stream()
                .min(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(position)))
                .orElseThrow();
        state = State.GOING_TO_SLUICE;
    }

    private void startWaiting() {
        state = State.WAITING;
        cooldown = CHECK_INTERVAL;
        repathTimer = REPATH_INTERVAL;
    }

    // --- The sluices ---

    private static boolean needsVisit(ServerLevel level, SluiceBlockEntity sluice, ProspectorJob job) {
        return loadCount(sluice) < job.getMinLoad() || !findLoot(level, sluice).isEmpty();
    }

    private boolean anyNeedsLoading(ServerLevel level, ProspectorJob job) {
        return sluices.stream().anyMatch(pos -> level.getBlockEntity(pos) instanceof SluiceBlockEntity sluice
                && loadCount(sluice) < job.getMinLoad());
    }

    private static int loadCount(SluiceBlockEntity sluice) {
        int count = 0;
        for (int slot = 0; slot < sluice.getInventory().getSlots(); slot++) {
            if (!sluice.getInventory().getStackInSlot(slot).isEmpty()) count++;
        }
        return count;
    }

    /** What the sluice washed out: the items lying around it and its water outlet, other than deposits. */
    private static List<ItemEntity> findLoot(ServerLevel level, SluiceBlockEntity sluice) {
        AABB area = new AABB(sluice.getBlockPos()).minmax(new AABB(sluice.getWaterOutputPos())).inflate(LOOT_RANGE);
        return level.getEntitiesOfClass(ItemEntity.class, area,
                item -> item.isAlive() && !sluice.isItemValid(0, item.getItem()));
    }

    private boolean pickUpLoot(ServerLevel level, XoonglinEntity entity, SluiceBlockEntity sluice) {
        boolean pickedUp = false;
        for (ItemEntity item : findLoot(level, sluice)) {
            ItemStack stack = item.getItem();
            ItemStack leftover = entity.getInventory().addItem(stack.copy());
            if (leftover.getCount() == stack.getCount()) continue;
            found.add(stack.getItem());
            pickedUp = true;
            if (leftover.isEmpty()) {
                item.discard();
            } else {
                item.setItem(leftover);
            }
        }
        return pickedUp;
    }

    /** Puts a few carried deposits in the sluice's empty slots; false if it can't put any. */
    private static boolean loadDeposits(XoonglinEntity entity, ProspectorJob job, SluiceBlockEntity sluice) {
        SimpleContainer inventory = entity.getInventory();
        int loaded = 0;
        for (int slot = 0; slot < sluice.getInventory().getSlots() && loaded < DEPOSITS_PER_ACTION; slot++) {
            if (!sluice.getInventory().getStackInSlot(slot).isEmpty()) continue;
            ItemStack deposit = ItemStack.EMPTY;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && job.loads(stack, sluice)) {
                    deposit = stack;
                    break;
                }
            }
            if (deposit.isEmpty()) break;
            sluice.getInventory().setStackInSlot(slot, deposit.split(1));
            sluice.setAndUpdateSlots(slot);
            loaded++;
        }
        if (loaded > 0) {
            inventory.setChanged();
        }
        return loaded > 0;
    }

    // --- Its inventory and the camp's chests ---

    /** Takes deposits the sluices on this round wash, up to its carrying capacity. */
    private void takeDeposits(ServerLevel level, XoonglinEntity entity, ProspectorJob job, List<Container> containers) {
        int remaining = job.getCarry() - carriedDeposits(level, entity, job);
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty() || !loadedOnRound(level, job, stack)) continue;

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

    /** Stores what it picked up at the sluices; whatever doesn't fit, it keeps. */
    private void store(XoonglinEntity entity, List<Container> containers) {
        if (containers.isEmpty()) return;
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && found.contains(stack.getItem())) {
                inventory.setItem(i, ContainerUtil.insertIntoContainers(containers, stack));
            }
        }
        if (carriedFound(entity) == 0) {
            found.clear();
        }
    }

    private boolean loadedOnRound(ServerLevel level, ProspectorJob job, ItemStack stack) {
        return sluices.stream().anyMatch(pos -> level.getBlockEntity(pos) instanceof SluiceBlockEntity sluice
                && job.loads(stack, sluice));
    }

    private int carriedDeposits(ServerLevel level, XoonglinEntity entity, ProspectorJob job) {
        SimpleContainer inventory = entity.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && loadedOnRound(level, job, stack)) count += stack.getCount();
        }
        return count;
    }

    private int carriedFound(XoonglinEntity entity) {
        SimpleContainer inventory = entity.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && found.contains(stack.getItem())) count += stack.getCount();
        }
        return count;
    }

    // --- Moving around ---

    /** Walks to within reach of a place, stopping beside it; true once it's there. */
    private boolean moveNear(XoonglinEntity entity, BlockPos target) {
        if (isNear(entity, target)) {
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

    private static boolean isNear(XoonglinEntity entity, BlockPos pos) {
        return pos != null && entity.position().distanceTo(Vec3.atCenterOf(pos)) < REACH;
    }

    private Optional<Structure> findCamp(ServerLevel level, ProspectorJob job) {
        return StructuresData.get(level).findByKeyBlock(camp)
                .filter(structure -> structure.getStructureTypeId().equals(job.getRequiredStructureType()));
    }
}
