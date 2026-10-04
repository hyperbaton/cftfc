package com.hyperbaton.cftfc.entity.ai.behavior;

import com.hyperbaton.cft.entity.ai.behavior.JobBehavior;
import com.hyperbaton.cft.entity.ai.behavior.WorkStep;
import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.structure.OpenAirPlatformBlockGroup;
import com.hyperbaton.cft.structure.Structure;
import com.hyperbaton.cft.util.ContainerUtil;
import com.hyperbaton.cft.world.StructuresData;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.hyperbaton.cftfc.job.ComposterJob;
import net.dries007.tfc.common.blockentities.ComposterBlockEntity;
import net.dries007.tfc.common.blockentities.ComposterBlockEntity.AdditionType;
import net.dries007.tfc.common.blockentities.ComposterBlockEntity.Compost;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Makes a composter worker tend the composters of its compost yard. Each time it checks them, it goes to
 * each composter it can do something at: it takes out finished or rotten compost, and adds the green and
 * brown items it carries until the composter has all it takes. Then it stores the compost in the yard's
 * chests, and takes the items for the composters that still need some.
 *
 * <p>Items go in through the composter's own interaction, as a player would add them, so TFC's rules hold:
 * each composter takes 16 worth of greens and 16 of browns, and adding anything restarts its clock.
 */
public class CompostBehavior extends JobBehavior<ComposterJob> {

    private static final int REPATH_INTERVAL = 40;
    /** Ticks between checks of the composters while there's nothing to do. */
    private static final int CHECK_INTERVAL = 200;
    /** Ticks between actions at a composter: adding an item, taking the compost out. */
    private static final int ACTION_INTERVAL = 5;
    /** How many times it paths to a composter before giving up on reaching it. */
    private static final int MAX_REPATHS = 10;
    /** How close it has to be to a composter to use it. */
    private static final double COMPOSTER_REACH = 2.5;
    /** How close it has to be to the yard's key block to use the yard's chests. */
    private static final double STORAGE_REACH = 2.5;
    /** How many greens, and how many browns, a composter takes. */
    private static final int FULL = ComposterBlockEntity.MAX_AMOUNT;

    private static final WorkStep WAITING_FOR_MATERIALS = step("waiting_for_compost_materials");
    private static final WorkStep WAITING_FOR_COMPOST = step("waiting_for_compost");

    private enum State {
        CHECKING_COMPOSTERS(step("checking_composters")),
        GOING_TO_STORAGE(WorkStep.of("going_to_storage")),
        GOING_TO_COMPOSTER(step("going_to_composter")),
        WORKING_COMPOSTER(step("tending_composter")),
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
    private final List<BlockPos> todo = new ArrayList<>();
    private BlockPos currentComposter;
    /** Whether it got anything done at the composters since it last checked them. */
    private boolean actedThisPass;
    /** Whether it's been to the chests since it last got anything done at the composters. */
    private boolean justStored;
    private int repathTimer;
    private int repaths;
    private int cooldown;

    public CompostBehavior() {
        super(CftfcMemoryModuleTypes.MUST_COMPOST.get(), ComposterJob.class, 2400);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, XoonglinEntity entity) {
        ComposterJob job = getJob(entity);
        return job != null && entity.getAssignedStructurePos(job.getRequiredStructureType()) != null;
    }

    @Override
    protected void start(ServerLevel level, XoonglinEntity entity, long gameTime) {
        ComposterJob job = getJob(entity);
        keyBlock = entity.getAssignedStructurePos(job.getRequiredStructureType());
        state = State.CHECKING_COMPOSTERS;
        todo.clear();
        currentComposter = null;
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
        ComposterJob job = getJob(entity);
        if (job == null || keyBlock == null) return;

        switch (state) {
            case CHECKING_COMPOSTERS -> tickChecking(level, entity, job);
            case GOING_TO_STORAGE -> tickGoingToStorage(level, entity, job);
            case GOING_TO_COMPOSTER -> tickGoingToComposter(entity);
            case WORKING_COMPOSTER -> tickWorkingComposter(level, entity, job);
            case WAITING -> tickWaiting(entity);
        }
    }

    /** It keeps what it carries when its shift ends, and uses it when it's back. */
    @Override
    protected void stopWork(ServerLevel level, XoonglinEntity entity, long gameTime) {
        entity.getNavigation().stop();
        todo.clear();
        currentComposter = null;
    }

    private void tickChecking(ServerLevel level, XoonglinEntity entity, ComposterJob job) {
        actedThisPass = false;
        Optional<Structure> yard = findYard(level, job);
        List<BlockPos> composters = yard.map(structure -> findComposters(level, structure)).orElse(List.of());
        if (composters.isEmpty()) {
            startWaiting(WorkStep.WAITING);
            return;
        }
        List<Container> containers = ContainerUtil.findContainers(level, yard.get());
        ComposterBlockEntity sample = composterAt(level, composters.get(0));

        todo.clear();
        boolean needsChests = false, lacksMaterials = false;
        for (BlockPos pos : composters) {
            ComposterBlockEntity composter = composterAt(level, pos);
            boolean done = composter.isReady() || composter.isRotten();
            int greens = done ? FULL : FULL - composter.getGreen();
            int browns = done ? FULL : FULL - composter.getBrown();
            boolean canFill = (greens > 0 && carries(entity, job, sample, AdditionType.GREEN))
                    || (browns > 0 && carries(entity, job, sample, AdditionType.BROWN));
            if (done || canFill) {
                todo.add(pos);
            }
            for (AdditionType type : List.of(AdditionType.GREEN, AdditionType.BROWN)) {
                int needed = type == AdditionType.GREEN ? greens : browns;
                if (needed <= 0 || carries(entity, job, sample, type)) continue;
                if (stocked(containers, job, sample, type)) {
                    needsChests = true;
                } else {
                    lacksMaterials = true;
                }
            }
        }

        if (todo.isEmpty()) {
            boolean carriesOther = !findCarried(entity, stack -> !isMaterial(stack, job, sample)).isEmpty();
            if ((needsChests || carriesOther) && !justStored) {
                goToStorage();
            } else {
                startWaiting(lacksMaterials ? WAITING_FOR_MATERIALS : WAITING_FOR_COMPOST);
            }
            return;
        }
        goToNextComposter(entity);
    }

    private void tickGoingToStorage(ServerLevel level, XoonglinEntity entity, ComposterJob job) {
        if (!moveNear(entity, keyBlock, STORAGE_REACH, keyBlock)) return;

        Optional<Structure> yard = findYard(level, job);
        List<BlockPos> composters = yard.map(structure -> findComposters(level, structure)).orElse(List.of());
        if (composters.isEmpty()) {
            startWaiting(WorkStep.WAITING);
            return;
        }
        List<Container> containers = ContainerUtil.findContainers(level, yard.get());
        ComposterBlockEntity sample = composterAt(level, composters.get(0));
        store(entity, containers);

        // The items that fill the composters best, from what the chests hold
        Map<Item, Integer> available = new HashMap<>();
        Map<Item, Compost> values = new HashMap<>();
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!isMaterial(stack, job, sample)) continue;
                available.merge(stack.getItem(), stack.getCount(), Integer::sum);
                values.putIfAbsent(stack.getItem(), sample.getCompost(stack));
            }
        }
        Map<Item, Integer> toTake = new HashMap<>();
        for (BlockPos pos : composters) {
            ComposterBlockEntity composter = composterAt(level, pos);
            boolean done = composter.isReady() || composter.isRotten();
            choose(done ? FULL : FULL - composter.getGreen(), AdditionType.GREEN, available, values)
                    .forEach((item, count) -> toTake.merge(item, count, Integer::sum));
            choose(done ? FULL : FULL - composter.getBrown(), AdditionType.BROWN, available, values)
                    .forEach((item, count) -> toTake.merge(item, count, Integer::sum));
        }
        int left = job.getCarry();
        for (Map.Entry<Item, Integer> entry : toTake.entrySet()) {
            int count = Math.min(entry.getValue(), left);
            Item item = entry.getKey();
            take(entity, containers, stack -> stack.is(item) && isMaterial(stack, job, sample), count);
            left -= count;
            if (left <= 0) break;
        }

        justStored = true;
        state = State.CHECKING_COMPOSTERS;
    }

    private void tickGoingToComposter(XoonglinEntity entity) {
        if (currentComposter == null) {
            state = State.CHECKING_COMPOSTERS;
            return;
        }
        if (isNear(entity, currentComposter, COMPOSTER_REACH)) {
            entity.getNavigation().stop();
            state = State.WORKING_COMPOSTER;
            cooldown = ACTION_INTERVAL;
            return;
        }
        if (++repathTimer >= REPATH_INTERVAL || entity.getNavigation().isDone()) {
            repathTimer = 0;
            if (++repaths > MAX_REPATHS) {
                // Can't get there: leave this composter for later
                goToNextComposter(entity);
                return;
            }
            Path path = entity.getNavigation().createPath(currentComposter, 1);
            if (path != null) {
                entity.getNavigation().moveTo(path, 1.0);
            }
        }
    }

    private void tickWorkingComposter(ServerLevel level, XoonglinEntity entity, ComposterJob job) {
        if (!isNear(entity, currentComposter, COMPOSTER_REACH)) {
            state = State.GOING_TO_COMPOSTER;
            repathTimer = REPATH_INTERVAL;
            return;
        }
        if (--cooldown > 0) return;
        cooldown = ACTION_INTERVAL;

        if (!(level.getBlockEntity(currentComposter) instanceof ComposterBlockEntity composter)) {
            goToNextComposter(entity);
            return;
        }
        if (composter.isReady() || composter.isRotten()) {
            if (!hasFreeSlot(entity)) {
                goToStorage();
                return;
            }
            // Take the compost out, as TFC does when it's emptied by hand
            give(entity, composter.getInventory().extractItem(0, 64, false));
            composter.reset();
            act(entity);
            return;
        }

        ItemStack material = pickCarried(entity, job, composter, AdditionType.GREEN, FULL - composter.getGreen());
        if (material.isEmpty()) {
            material = pickCarried(entity, job, composter, AdditionType.BROWN, FULL - composter.getBrown());
        }
        if (material.isEmpty()) {
            goToNextComposter(entity);
            return;
        }
        FakePlayer hand = FakePlayerFactory.getMinecraft(level);
        hand.setPos(entity.getX(), entity.getY(), entity.getZ());
        composter.use(material, hand, false);
        entity.getInventory().setChanged();
        act(entity);
    }

    private void tickWaiting(XoonglinEntity entity) {
        moveNear(entity, keyBlock, STORAGE_REACH, keyBlock);
        if (--cooldown <= 0) {
            justStored = false;
            state = State.CHECKING_COMPOSTERS;
        }
    }

    private void act(XoonglinEntity entity) {
        actedThisPass = true;
        justStored = false;
        entity.swing(InteractionHand.MAIN_HAND);
    }

    private void goToStorage() {
        state = State.GOING_TO_STORAGE;
        repathTimer = REPATH_INTERVAL;
        repaths = 0;
    }

    /** Moves on to the nearest composter left to tend, or checks them again once there are none. */
    private void goToNextComposter(XoonglinEntity entity) {
        if (currentComposter != null) {
            todo.remove(currentComposter);
            currentComposter = null;
        }
        if (todo.isEmpty()) {
            if (actedThisPass) {
                state = State.CHECKING_COMPOSTERS;
            } else {
                // Couldn't reach or fill any of the composters: don't keep trying right away
                startWaiting(WorkStep.WAITING);
            }
            return;
        }
        Vec3 position = entity.position();
        currentComposter = todo.stream()
                .min(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(position)))
                .orElseThrow();
        state = State.GOING_TO_COMPOSTER;
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

    // --- The yard and its composters ---

    private Optional<Structure> findYard(ServerLevel level, ComposterJob job) {
        return StructuresData.get(level).findByKeyBlock(keyBlock)
                .filter(structure -> structure.getStructureTypeId().equals(job.getRequiredStructureType()));
    }

    /** The yard's composters: in its fence, or standing on its ground. */
    private static List<BlockPos> findComposters(ServerLevel level, Structure yard) {
        Set<BlockPos> candidates = new LinkedHashSet<>(yard.getBlockPositions()
                .getOrDefault(OpenAirPlatformBlockGroup.BORDER.getKey(), List.of()));
        yard.getBlockPositions().getOrDefault(OpenAirPlatformBlockGroup.SURFACE.getKey(), List.of())
                .forEach(ground -> candidates.add(ground.above()));
        return candidates.stream()
                .filter(pos -> level.getBlockEntity(pos) instanceof ComposterBlockEntity)
                .toList();
    }

    private static ComposterBlockEntity composterAt(ServerLevel level, BlockPos pos) {
        return (ComposterBlockEntity) level.getBlockEntity(pos);
    }

    // --- What goes in a composter ---

    /** Whether it's an item it may add to a composter: green or brown, and nothing that rots the compost. */
    private static boolean isMaterial(ItemStack stack, ComposterJob job, ComposterBlockEntity composter) {
        if (stack.isEmpty() || !job.mayCompost(stack)) return false;
        Compost compost = composter.getCompost(stack);
        return (compost.type() == AdditionType.GREEN || compost.type() == AdditionType.BROWN) && compost.amount() > 0;
    }

    private static boolean isMaterial(ItemStack stack, ComposterJob job, ComposterBlockEntity composter, AdditionType type) {
        return isMaterial(stack, job, composter) && composter.getCompost(stack).type() == type;
    }

    private static boolean carries(XoonglinEntity entity, ComposterJob job, ComposterBlockEntity composter, AdditionType type) {
        return !findCarried(entity, stack -> isMaterial(stack, job, composter, type)).isEmpty();
    }

    private static boolean stocked(List<Container> containers, ComposterJob job, ComposterBlockEntity composter, AdditionType type) {
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (isMaterial(container.getItem(i), job, composter, type)) return true;
            }
        }
        return false;
    }

    /**
     * The carried item to add next towards what the composter still takes of a kind: the one worth the
     * most that doesn't go over it, or else the one worth the least, so the least goes to waste.
     */
    private static ItemStack pickCarried(XoonglinEntity entity, ComposterJob job, ComposterBlockEntity composter,
                                         AdditionType type, int needed) {
        if (needed <= 0) return ItemStack.EMPTY;
        ItemStack best = ItemStack.EMPTY;
        int bestAmount = 0;
        SimpleContainer inventory = entity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!isMaterial(stack, job, composter, type)) continue;
            int amount = composter.getCompost(stack).amount();
            if (best.isEmpty() || isBetter(amount, bestAmount, needed)) {
                best = stack;
                bestAmount = amount;
            }
        }
        return best;
    }

    /** Picks, from what's available, the items that make up what a composter takes of a kind, wasting the least. */
    private static Map<Item, Integer> choose(int needed, AdditionType type, Map<Item, Integer> available,
                                             Map<Item, Compost> values) {
        Map<Item, Integer> chosen = new HashMap<>();
        int left = needed;
        while (left > 0) {
            Item best = null;
            int bestAmount = 0;
            for (Map.Entry<Item, Integer> entry : available.entrySet()) {
                Compost compost = values.get(entry.getKey());
                if (entry.getValue() <= 0 || compost.type() != type) continue;
                if (best == null || isBetter(compost.amount(), bestAmount, left)) {
                    best = entry.getKey();
                    bestAmount = compost.amount();
                }
            }
            if (best == null) break;
            available.merge(best, -1, Integer::sum);
            chosen.merge(best, 1, Integer::sum);
            left -= bestAmount;
        }
        return chosen;
    }

    /** Whether an item worth this much fills what's left better than one worth that much. */
    private static boolean isBetter(int amount, int bestAmount, int left) {
        boolean fits = amount <= left, bestFits = bestAmount <= left;
        if (fits != bestFits) return fits;
        return fits ? amount > bestAmount : amount < bestAmount;
    }

    // --- Its inventory and the yard's chests ---

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

    /** Stores everything it carries in the yard's chests, as far as it fits. */
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
