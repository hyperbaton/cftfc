package com.hyperbaton.cftfc.entity.ai.behavior;

import com.hyperbaton.cft.entity.ai.behavior.JobBehavior;
import com.hyperbaton.cft.entity.ai.behavior.WorkStep;
import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.structure.Structure;
import com.hyperbaton.cft.util.ContainerUtil;
import com.hyperbaton.cft.world.StructuresData;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.hyperbaton.cftfc.job.PreserverJob;
import com.hyperbaton.cftfc.job.PreserverJob.Process;
import net.dries007.tfc.common.blockentities.BarrelBlockEntity;
import net.dries007.tfc.common.blocks.devices.BarrelBlock;
import net.dries007.tfc.common.component.food.FoodCapability;
import net.dries007.tfc.common.component.food.FoodTraits;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Makes a preserver work its pantry. It goes from barrel to barrel: it unseals the ones that are done, moves
 * the preserved food to the pantry's chests, loads each barrel with food its brine or vinegar can preserve,
 * and seals it. Between barrels, it salts meat and fish in the chests. It only touches barrels of brine or
 * vinegar, and waits by the pantry's key block when there's nothing to do.
 */
public class PreserveBehavior extends JobBehavior<PreserverJob> {

    private static final int REPATH_INTERVAL = 40;
    /** Ticks between actions: sealing a barrel, loading it, salting a piece of food... */
    private static final int ACTION_INTERVAL = 20;
    /** Ticks between checks for work while it waits. */
    private static final int CHECK_INTERVAL = 200;
    /** Pieces of food it salts with each action. */
    private static final int SALTED_PER_ACTION = 4;
    private static final int MAX_REPATHS = 10;
    private static final double REACH = 2.5;

    private static final WorkStep WAITING_FOR_FOOD = step("waiting_for_food");
    private static final WorkStep WAITING_FOR_BRINE = step("waiting_for_brine");

    private enum State {
        GOING_TO_BARREL(WorkStep.GOING_TO_WORK),
        TENDING_BARREL(step("tending_barrel")),
        SALTING(step("salting")),
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
    private BlockPos barrel;
    private int repathTimer;
    private int repaths;
    private int cooldown;

    public PreserveBehavior() {
        super(CftfcMemoryModuleTypes.MUST_PRESERVE.get(), PreserverJob.class, 2400);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, XoonglinEntity entity) {
        PreserverJob job = getJob(entity);
        return job != null && entity.getAssignedStructurePos(job.getRequiredStructureType()) != null;
    }

    @Override
    protected void start(ServerLevel level, XoonglinEntity entity, long gameTime) {
        PreserverJob job = getJob(entity);
        keyBlock = entity.getAssignedStructurePos(job.getRequiredStructureType());
        decideNext(level, entity, job);
    }

    @Override
    protected WorkStep workStep() {
        if (state == null) return null;
        return state == State.WAITING && waitStep != null ? waitStep : state.step;
    }

    @Override
    protected void tickWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        PreserverJob job = getJob(entity);
        if (job == null || keyBlock == null) return;

        switch (state) {
            case GOING_TO_BARREL -> tickGoingToBarrel(level, entity, job);
            case TENDING_BARREL -> tickTendingBarrel(level, entity, job);
            case SALTING -> tickSalting(level, entity, job);
            case WAITING -> tickWaiting(level, entity, job);
        }
    }

    @Override
    protected void stopWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        entity.getNavigation().stop();
    }

    private void tickGoingToBarrel(ServerLevel level, XoonglinEntity entity, PreserverJob job) {
        if (isNear(entity, barrel)) {
            entity.getNavigation().stop();
            state = State.TENDING_BARREL;
            cooldown = 0;
            return;
        }
        if (++repathTimer >= REPATH_INTERVAL || entity.getNavigation().isDone()) {
            repathTimer = 0;
            if (++repaths > MAX_REPATHS) {
                startWaiting(WorkStep.WAITING);
                return;
            }
            Path path = entity.getNavigation().createPath(barrel, 1);
            if (path != null) {
                entity.getNavigation().moveTo(path, 1.0);
            }
        }
    }

    private void tickTendingBarrel(ServerLevel level, XoonglinEntity entity, PreserverJob job) {
        if (!isNear(entity, barrel)) {
            goToBarrel(barrel);
            return;
        }
        if (--cooldown > 0) return;
        cooldown = ACTION_INTERVAL;

        Optional<Structure> pantry = findPantry(level, job);
        if (pantry.isPresent() && level.getBlockEntity(barrel) instanceof BarrelBlockEntity barrelEntity
                && tend(level, barrelEntity, ContainerUtil.findContainers(level, pantry.get()), job)) {
            entity.swing(InteractionHand.MAIN_HAND);
            return;
        }
        decideNext(level, entity, job);
    }

    private void tickSalting(ServerLevel level, XoonglinEntity entity, PreserverJob job) {
        moveNear(entity, keyBlock);
        if (--cooldown > 0) return;
        cooldown = ACTION_INTERVAL;

        Optional<Structure> pantry = findPantry(level, job);
        if (pantry.isPresent() && salt(entity, ContainerUtil.findContainers(level, pantry.get()), job)) {
            entity.swing(InteractionHand.MAIN_HAND);
            // Barrels come first: check whether one has work before salting more
            if (findBarrelWithWork(level, entity, pantry.get(), job).isEmpty()) return;
        }
        decideNext(level, entity, job);
    }

    private void tickWaiting(ServerLevel level, XoonglinEntity entity, PreserverJob job) {
        moveNear(entity, keyBlock);
        if (--cooldown <= 0) {
            decideNext(level, entity, job);
        }
    }

    /** Goes to the nearest barrel with work, or salts, or waits when there's nothing to do. */
    private void decideNext(ServerLevel level, XoonglinEntity entity, PreserverJob job) {
        Optional<Structure> pantry = findPantry(level, job);
        if (pantry.isEmpty()) {
            startWaiting(WorkStep.WAITING);
            return;
        }
        Optional<BlockPos> next = findBarrelWithWork(level, entity, pantry.get(), job);
        if (next.isPresent()) {
            goToBarrel(next.get());
            return;
        }
        List<Container> storage = ContainerUtil.findContainers(level, pantry.get());
        if (storageHas(storage, job::salts) && storageHas(storage, PreserverJob::isSalt)) {
            state = State.SALTING;
            cooldown = ACTION_INTERVAL;
            repathTimer = REPATH_INTERVAL;
            return;
        }
        startWaiting(hasLiquid(level, pantry.get(), job) ? WAITING_FOR_FOOD : WAITING_FOR_BRINE);
    }

    // --- Barrels ---

    /**
     * Does the next thing the barrel needs: unseal it when it's done, take out what it has finished, load it
     * with food to preserve, and seal it.
     *
     * @return whether it did anything
     */
    private static boolean tend(ServerLevel level, BarrelBlockEntity barrel, List<Container> storage, PreserverJob job) {
        Optional<Process> process = processOf(barrel, job);
        if (process.isEmpty()) return false;

        if (isSealed(barrel)) {
            if (!isDone(barrel)) return false;
            BarrelBlock.toggleSeal(level, barrel.getBlockPos(), barrel.getBlockState());
            return true;
        }

        IItemHandler items = barrel.getInventory();
        ItemStack inside = items.getStackInSlot(BarrelBlockEntity.SLOT_ITEM);
        if (!inside.isEmpty()) {
            if (job.processes(process.get(), inside) && fluidAmount(barrel) >= PreserverJob.FLUID_PER_ITEM) {
                BarrelBlock.toggleSeal(level, barrel.getBlockPos(), barrel.getBlockState());
                return true;
            }
            // Preserved, or something it can't preserve here: store it
            ItemStack remainder = ContainerUtil.insertIntoContainers(storage, inside.copy());
            if (remainder.getCount() == inside.getCount()) return false;
            items.extractItem(BarrelBlockEntity.SLOT_ITEM, inside.getCount() - remainder.getCount(), false);
            return true;
        }

        int batch = Math.min(job.getLoad(), fluidAmount(barrel) / PreserverJob.FLUID_PER_ITEM);
        if (batch <= 0) return false;
        ItemStack food = take(storage, stack -> job.processes(process.get(), stack), batch);
        if (food.isEmpty()) return false;
        ItemStack remainder = items.insertItem(BarrelBlockEntity.SLOT_ITEM, food, false);
        if (!remainder.isEmpty()) {
            ItemStack left = ContainerUtil.insertIntoContainers(storage, remainder);
            if (!left.isEmpty()) {
                Containers.dropItemStack(level, barrel.getBlockPos().getX(), barrel.getBlockPos().getY() + 1,
                        barrel.getBlockPos().getZ(), left);
            }
        }
        return true;
    }

    private static boolean hasWork(BarrelBlockEntity barrel, List<Container> storage, PreserverJob job) {
        Optional<Process> process = processOf(barrel, job);
        if (process.isEmpty()) return false;
        if (isSealed(barrel)) return isDone(barrel);

        ItemStack inside = barrel.getInventory().getStackInSlot(BarrelBlockEntity.SLOT_ITEM);
        if (!inside.isEmpty()) return true;
        return fluidAmount(barrel) >= PreserverJob.FLUID_PER_ITEM
                && storageHas(storage, stack -> job.processes(process.get(), stack));
    }

    /** Whether a sealed barrel has finished: its recipe is over, or it only keeps the food now. */
    private static boolean isDone(BarrelBlockEntity barrel) {
        return barrel.getRecipe() == null || barrel.getRecipe().isInfinite() || barrel.getRemainingTicks() <= 0;
    }

    private static boolean isSealed(BarrelBlockEntity barrel) {
        return barrel.getBlockState().getOptionalValue(BarrelBlock.SEALED).orElse(false);
    }

    private static Optional<Process> processOf(BarrelBlockEntity barrel, PreserverJob job) {
        FluidStack fluid = barrel.getInventory().getFluidHandler().getFluidInTank(0);
        return fluid.isEmpty() ? Optional.empty() : job.processFor(fluid.getFluid());
    }

    private static int fluidAmount(BarrelBlockEntity barrel) {
        return barrel.getInventory().getFluidHandler().getFluidInTank(0).getAmount();
    }

    private Optional<BlockPos> findBarrelWithWork(ServerLevel level, XoonglinEntity entity, Structure pantry,
                                                  PreserverJob job) {
        List<Container> storage = ContainerUtil.findContainers(level, pantry);
        Vec3 position = entity.position();
        return pantry.getAllBlockPositions().stream()
                .filter(pos -> level.getBlockEntity(pos) instanceof BarrelBlockEntity barrelEntity
                        && hasWork(barrelEntity, storage, job))
                .min(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(position)));
    }

    /** Whether any of the pantry's barrels holds enough brine or vinegar for one more piece of food. */
    private static boolean hasLiquid(ServerLevel level, Structure pantry, PreserverJob job) {
        return pantry.getAllBlockPositions().stream()
                .anyMatch(pos -> level.getBlockEntity(pos) instanceof BarrelBlockEntity barrelEntity
                        && processOf(barrelEntity, job).isPresent()
                        && fluidAmount(barrelEntity) >= PreserverJob.FLUID_PER_ITEM);
    }

    // --- Salting ---

    /**
     * Salts a few pieces of food in the chests, using one salt for each, as TFC's salting recipe does.
     *
     * @return whether it salted anything
     */
    private static boolean salt(XoonglinEntity entity, List<Container> storage, PreserverJob job) {
        int salted = 0;
        while (salted < SALTED_PER_ACTION) {
            ItemStack food = take(storage, job::salts, 1);
            if (food.isEmpty()) break;
            ItemStack salt = take(storage, PreserverJob::isSalt, 1);
            if (salt.isEmpty()) {
                // No salt left after all: put the food back
                store(entity, storage, food);
                break;
            }
            food = FoodCapability.roundCreationDate(FoodCapability.applyTrait(food, FoodTraits.SALTED));
            store(entity, storage, food);
            salted++;
        }
        return salted > 0;
    }

    // --- The pantry's chests ---

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

    /** Puts an item in the chests, dropping it by the Xoonglin if they're full. */
    private static void store(XoonglinEntity entity, List<Container> storage, ItemStack stack) {
        ItemStack left = ContainerUtil.insertIntoContainers(storage, stack);
        if (!left.isEmpty()) {
            Containers.dropItemStack(entity.level(), entity.getX(), entity.getY(), entity.getZ(), left);
        }
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

    // --- Moving around ---

    private void goToBarrel(BlockPos target) {
        barrel = target;
        state = State.GOING_TO_BARREL;
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
        return pos != null && entity.position().distanceTo(Vec3.atCenterOf(pos)) < REACH;
    }

    private Optional<Structure> findPantry(ServerLevel level, PreserverJob job) {
        return StructuresData.get(level).findByKeyBlock(keyBlock)
                .filter(structure -> structure.getStructureTypeId().equals(job.getRequiredStructureType()));
    }
}
