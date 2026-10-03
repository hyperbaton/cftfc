package com.hyperbaton.cftfc.entity.ai.behavior;

import com.hyperbaton.cft.entity.ai.behavior.JobBehavior;
import com.hyperbaton.cft.entity.ai.behavior.WorkStep;
import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.structure.Structure;
import com.hyperbaton.cft.util.ContainerUtil;
import com.hyperbaton.cft.world.StructuresData;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.hyperbaton.cftfc.job.MillerJob;
import net.dries007.tfc.common.blockentities.QuernBlockEntity;
import net.dries007.tfc.common.recipes.QuernRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Makes a miller work the querns of its mill, one at a time: it goes to a quern with work to do, stores
 * what the quern has ground, gives it a new handstone if it has none, loads it with something to grind,
 * and turns it, staying by it while it grinds. When that quern runs out of work it moves on to another,
 * and waits by the mill's key block when none has any.
 *
 * <p>Querns turned by a water wheel or windmill grind on their own: it only loads and empties those.
 */
public class GrindBehavior extends JobBehavior<MillerJob> {

    private static final int REPATH_INTERVAL = 40;
    /** Ticks between tending the quern it's at; a quern takes 90 ticks to grind one item. */
    private static final int TEND_INTERVAL = 20;
    /** Ticks between checks for work while it waits. */
    private static final int CHECK_INTERVAL = 100;
    private static final int MAX_REPATHS = 10;
    private static final double QUERN_REACH = 2.5;

    private static final WorkStep WAITING_FOR_GRAIN = step("waiting_for_grain");
    private static final WorkStep WAITING_FOR_HANDSTONE = step("waiting_for_handstone");

    private enum State {
        GOING_TO_QUERN(WorkStep.GOING_TO_WORK),
        GRINDING(step("grinding")),
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
    private BlockPos quern;
    private int repathTimer;
    private int repaths;
    private int cooldown;

    public GrindBehavior() {
        super(CftfcMemoryModuleTypes.MUST_GRIND.get(), MillerJob.class, 2400);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, XoonglinEntity entity) {
        MillerJob job = getJob(entity);
        return job != null && entity.getAssignedStructurePos(job.getRequiredStructureType()) != null;
    }

    @Override
    protected void start(ServerLevel level, XoonglinEntity entity, long gameTime) {
        MillerJob job = getJob(entity);
        keyBlock = entity.getAssignedStructurePos(job.getRequiredStructureType());
        goToQuern(keyBlock);
    }

    @Override
    protected WorkStep workStep() {
        if (state == null) return null;
        return state == State.WAITING && waitStep != null ? waitStep : state.step;
    }

    @Override
    protected void tickWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        MillerJob job = getJob(entity);
        if (job == null || keyBlock == null) return;

        switch (state) {
            case GOING_TO_QUERN -> tickGoingToQuern(level, entity, job);
            case GRINDING -> tickGrinding(level, entity, job);
            case WAITING -> tickWaiting(level, entity, job);
        }
    }

    @Override
    protected void stopWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        entity.getNavigation().stop();
    }

    private void tickGoingToQuern(ServerLevel level, XoonglinEntity entity, MillerJob job) {
        if (isNear(entity, quern)) {
            entity.getNavigation().stop();
            state = State.GRINDING;
            cooldown = 0;
            return;
        }
        if (++repathTimer >= REPATH_INTERVAL || entity.getNavigation().isDone()) {
            repathTimer = 0;
            if (++repaths > MAX_REPATHS) {
                startWaiting(WorkStep.WAITING);
                return;
            }
            // Stop beside the quern rather than on it
            Path path = entity.getNavigation().createPath(quern, 1);
            if (path != null) {
                entity.getNavigation().moveTo(path, 1.0);
            }
        }
    }

    private void tickGrinding(ServerLevel level, XoonglinEntity entity, MillerJob job) {
        if (!isNear(entity, quern)) {
            goToQuern(quern);
            return;
        }
        if (--cooldown > 0) return;
        cooldown = TEND_INTERVAL;

        Optional<Structure> mill = findMill(level, job);
        if (mill.isEmpty()) {
            startWaiting(WorkStep.WAITING);
            return;
        }
        List<Container> storage = ContainerUtil.findContainers(level, mill.get());

        if (level.getBlockEntity(quern) instanceof QuernBlockEntity quernEntity) {
            boolean acted = tend(quernEntity, storage, job);
            if (!quernEntity.isGrinding() && !quernEntity.isConnectedToNetwork() && quernEntity.hasHandstone()
                    && quernEntity.startGrinding()) {
                acted = true;
            }
            if (acted) {
                entity.swing(InteractionHand.MAIN_HAND);
            }
            if (quernEntity.isGrinding()) return;
        }

        // Nothing more to do at this quern: move on to another one with work, or wait
        findQuernWithWork(level, entity, mill.get(), storage, job).ifPresentOrElse(next -> {
            if (!next.equals(quern)) goToQuern(next);
        }, () -> startWaiting(waitReason(storage, job)));
    }

    private void tickWaiting(ServerLevel level, XoonglinEntity entity, MillerJob job) {
        moveNear(entity, keyBlock);
        if (--cooldown > 0) return;

        Optional<Structure> mill = findMill(level, job);
        if (mill.isEmpty()) {
            startWaiting(WorkStep.WAITING);
            return;
        }
        List<Container> storage = ContainerUtil.findContainers(level, mill.get());
        findQuernWithWork(level, entity, mill.get(), storage, job)
                .ifPresentOrElse(this::goToQuern, () -> startWaiting(waitReason(storage, job)));
    }

    /**
     * Stores what the quern has ground, takes out anything it can't grind, gives it a handstone and loads
     * it with something to grind, as far as the mill's chests allow.
     *
     * @return whether it did anything
     */
    private static boolean tend(QuernBlockEntity quern, List<Container> storage, MillerJob job) {
        IItemHandlerModifiable inventory = quern.getInventory();
        boolean acted = moveToStorage(quern, QuernBlockEntity.SLOT_OUTPUT, storage);

        ItemStack input = inventory.getStackInSlot(QuernBlockEntity.SLOT_INPUT);
        if (!input.isEmpty() && QuernRecipe.getRecipe(input) == null) {
            acted |= moveToStorage(quern, QuernBlockEntity.SLOT_INPUT, storage);
        }

        if (!quern.hasHandstone()) {
            ItemStack handstone = take(storage, stack -> quern.isItemValid(QuernBlockEntity.SLOT_HANDSTONE, stack), 1);
            if (!handstone.isEmpty()) {
                inventory.setStackInSlot(QuernBlockEntity.SLOT_HANDSTONE, handstone);
                quern.setAndUpdateSlots(QuernBlockEntity.SLOT_HANDSTONE);
                acted = true;
            }
        }

        if (inventory.getStackInSlot(QuernBlockEntity.SLOT_INPUT).isEmpty()) {
            ItemStack load = take(storage, job::grinds, job.getLoad());
            if (!load.isEmpty()) {
                inventory.setStackInSlot(QuernBlockEntity.SLOT_INPUT, load);
                quern.setAndUpdateSlots(QuernBlockEntity.SLOT_INPUT);
                acted = true;
            }
        }
        return acted;
    }

    /** Whether there's anything it can do at the quern with what the mill's chests hold. */
    private static boolean hasWork(QuernBlockEntity quern, List<Container> storage, MillerJob job) {
        IItemHandlerModifiable inventory = quern.getInventory();
        if (!inventory.getStackInSlot(QuernBlockEntity.SLOT_OUTPUT).isEmpty()) return true;
        if (quern.isGrinding()) return !quern.isConnectedToNetwork();

        boolean canHaveHandstone = quern.hasHandstone()
                || storageHas(storage, stack -> quern.isItemValid(QuernBlockEntity.SLOT_HANDSTONE, stack));
        ItemStack input = inventory.getStackInSlot(QuernBlockEntity.SLOT_INPUT);
        boolean hasInput = !input.isEmpty() && QuernRecipe.getRecipe(input) != null;
        if (quern.isConnectedToNetwork()) {
            // It grinds on its own: it only needs loading
            return !hasInput && canHaveHandstone && storageHas(storage, job::grinds);
        }
        return canHaveHandstone && (hasInput || storageHas(storage, job::grinds));
    }

    private Optional<BlockPos> findQuernWithWork(ServerLevel level, XoonglinEntity entity, Structure mill,
                                                 List<Container> storage, MillerJob job) {
        Vec3 position = entity.position();
        return mill.getAllBlockPositions().stream()
                .filter(pos -> level.getBlockEntity(pos) instanceof QuernBlockEntity quernEntity
                        && hasWork(quernEntity, storage, job))
                .min(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(position)));
    }

    private static WorkStep waitReason(List<Container> storage, MillerJob job) {
        return storageHas(storage, job::grinds) ? WAITING_FOR_HANDSTONE : WAITING_FOR_GRAIN;
    }

    private void goToQuern(BlockPos target) {
        quern = target;
        state = State.GOING_TO_QUERN;
        repathTimer = REPATH_INTERVAL;
        repaths = 0;
    }

    private void startWaiting(WorkStep reason) {
        state = State.WAITING;
        waitStep = reason;
        cooldown = CHECK_INTERVAL;
        repathTimer = REPATH_INTERVAL;
    }

    private void moveNear(XoonglinEntity entity, BlockPos target) {
        if (isNear(entity, target)) {
            entity.getNavigation().stop();
            return;
        }
        if (++repathTimer >= REPATH_INTERVAL || entity.getNavigation().isDone()) {
            repathTimer = 0;
            Path path = entity.getNavigation().createPath(target, 1);
            if (path != null) {
                entity.getNavigation().moveTo(path, 1.0);
            }
        }
    }

    private static boolean isNear(XoonglinEntity entity, BlockPos pos) {
        return pos != null && entity.position().distanceTo(Vec3.atCenterOf(pos)) < QUERN_REACH;
    }

    private Optional<Structure> findMill(ServerLevel level, MillerJob job) {
        return StructuresData.get(level).findByKeyBlock(keyBlock)
                .filter(structure -> structure.getStructureTypeId().equals(job.getRequiredStructureType()));
    }

    /** Moves a quern slot into the mill's chests; whatever doesn't fit stays in the quern. */
    private static boolean moveToStorage(QuernBlockEntity quern, int slot, List<Container> storage) {
        ItemStack stack = quern.getInventory().getStackInSlot(slot);
        if (stack.isEmpty()) return false;
        ItemStack remainder = ContainerUtil.insertIntoContainers(storage, stack.copy());
        if (remainder.getCount() == stack.getCount()) return false;
        quern.getInventory().setStackInSlot(slot, remainder);
        quern.setAndUpdateSlots(slot);
        return true;
    }

    /** Takes up to {@code maxCount} items of the first stack in the chests that matches. */
    private static ItemStack take(List<Container> storage, Predicate<ItemStack> matches, int maxCount) {
        for (Container container : storage) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && matches.test(stack)) {
                    ItemStack taken = container.removeItem(i, Math.min(maxCount, stack.getCount()));
                    container.setChanged();
                    return taken;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean storageHas(List<Container> storage, Predicate<ItemStack> matches) {
        for (Container container : storage) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && matches.test(stack)) return true;
            }
        }
        return false;
    }
}
