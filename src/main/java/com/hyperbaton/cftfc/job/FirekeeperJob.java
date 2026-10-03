package com.hyperbaton.cftfc.job;

import com.hyperbaton.cft.entity.ai.memory.CftMemoryModuleType;
import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.job.Job;
import com.hyperbaton.cft.job.JobState;
import com.hyperbaton.cft.network.JobDisplayEntry;
import com.hyperbaton.cft.network.JobInfoData;
import com.hyperbaton.cft.network.JobStatus;
import com.hyperbaton.cft.util.JobUtil;
import com.hyperbaton.cft.util.RegistryEntries;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.hyperbaton.cft.need.codec.CftCodec.INGREDIENT_CODEC;

/**
 * The Xoonglin keeps the TFC fires around its woodshed burning: it takes fuel from the woodshed's
 * chests, walks to the fires that are running low or have gone out, refuels them and lights them again.
 */
public class FirekeeperJob extends Job {

    private static final RegistryEntries<Block> DEFAULT_FIRES = RegistryEntries.of(
            ResourceLocation.fromNamespaceAndPath("tfc", "firepit"),
            ResourceLocation.fromNamespaceAndPath("tfc", "grill"),
            ResourceLocation.fromNamespaceAndPath("tfc", "pot"));

    public static final Codec<FirekeeperJob> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            propertiesCodec(),
            Codec.DOUBLE.fieldOf("hours_per_day").forGetter(j -> j.hoursPerDay),
            ResourceLocation.CODEC.fieldOf("required_structure").forGetter(j -> j.requiredStructure),
            Codec.intRange(1, 128).optionalFieldOf("radius", 32).forGetter(FirekeeperJob::getRadius),
            RegistryEntries.codec(Registries.BLOCK).optionalFieldOf("fires", DEFAULT_FIRES).forGetter(j -> j.fires),
            INGREDIENT_CODEC.listOf().optionalFieldOf("fuel", List.of()).forGetter(j -> j.fuel),
            Codec.intRange(1, 8).optionalFieldOf("min_fuel", 2).forGetter(FirekeeperJob::getMinFuel),
            Codec.BOOL.optionalFieldOf("relight", true).forGetter(FirekeeperJob::relights),
            Codec.intRange(1, 64).optionalFieldOf("carry", 8).forGetter(FirekeeperJob::getCarry)
    ).apply(inst, FirekeeperJob::new));

    private final double hoursPerDay;
    private final ResourceLocation requiredStructure;
    private final int radius;
    private final RegistryEntries<Block> fires;
    private final List<Ingredient> fuel;
    private final int minFuel;
    private final boolean relight;
    private final int carry;

    public FirekeeperJob(Properties properties, double hoursPerDay, ResourceLocation requiredStructure, int radius,
                         RegistryEntries<Block> fires, List<Ingredient> fuel, int minFuel, boolean relight, int carry) {
        super(properties);
        this.hoursPerDay = hoursPerDay;
        this.requiredStructure = requiredStructure;
        this.radius = radius;
        this.fires = fires;
        this.fuel = List.copyOf(fuel);
        this.minFuel = minFuel;
        this.relight = relight;
        this.carry = carry;
    }

    /** How far from the woodshed's key block the fires it tends can be. */
    public int getRadius() {
        return radius;
    }

    /** A lit fire is refueled when it holds fewer fuel items than this. */
    public int getMinFuel() {
        return minFuel;
    }

    /** Whether it lights fires that have gone out. */
    public boolean relights() {
        return relight;
    }

    /** How many fuel items it takes from the woodshed for a round. */
    public int getCarry() {
        return carry;
    }

    /** Whether it may use this item as fuel; the fire itself still has to accept it. */
    public boolean acceptsFuel(ItemStack stack) {
        return fuel.isEmpty() || fuel.stream().anyMatch(ingredient -> ingredient.test(stack));
    }

    /** Whether the fire is running low on fuel, or has gone out and it relights fires. */
    public boolean needsTending(TendableFire fire) {
        if (fire.isLit()) {
            return fire.fuelCount() < Math.min(minFuel, fire.fuelCapacity());
        }
        return relight;
    }

    /** The fires of the kinds it tends, within its radius of the woodshed, that need tending. */
    public List<BlockPos> findFiresToTend(Level level, BlockPos woodshed) {
        List<BlockPos> found = new ArrayList<>();
        long maxDistSqr = (long) radius * radius;
        for (int chunkX = SectionPos.blockToSectionCoord(woodshed.getX() - radius);
             chunkX <= SectionPos.blockToSectionCoord(woodshed.getX() + radius); chunkX++) {
            for (int chunkZ = SectionPos.blockToSectionCoord(woodshed.getZ() - radius);
                 chunkZ <= SectionPos.blockToSectionCoord(woodshed.getZ() + radius); chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) continue;
                for (BlockEntity blockEntity : level.getChunk(chunkX, chunkZ).getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (pos.distSqr(woodshed) > maxDistSqr) continue;
                    if (!fires.contains(blockEntity.getBlockState().getBlockHolder())) continue;
                    Optional<TendableFire> fire = TendableFire.of(blockEntity);
                    if (fire.isPresent() && needsTending(fire.get())) {
                        found.add(pos);
                    }
                }
            }
        }
        return found;
    }

    @Override
    public ResourceLocation getRequiredStructureType() {
        return requiredStructure;
    }

    @Override
    public void tick(XoonglinEntity xoonglin, JobState state) {
        Level level = xoonglin.level();
        if (level.isClientSide) return;

        long dayIndex = Math.floorDiv(level.getDayTime(), 24000L);
        int neededTicks = (int) Math.round(hoursPerDay * JobUtil.TICKS_PER_MC_HOUR);

        if (state.lastDayIndex == Long.MIN_VALUE) {
            state.lastDayIndex = dayIndex;
        } else if (dayIndex != state.lastDayIndex) {
            boolean metQuota = state.workedTicksToday >= neededTicks;
            if (metQuota) {
                state.consecutiveDaysWorked++;
            } else {
                state.consecutiveDaysWorked = 0;
            }
            state.workedTicksToday = 0;
            state.lastDayIndex = dayIndex;
        }

        BlockPos structurePos = xoonglin.getAssignedStructurePos(requiredStructure);
        Brain<XoonglinEntity> brain = xoonglin.getBrain();

        // Unlike a smelter, its work takes it all around the woodshed, so it's on duty wherever it is
        if (structurePos != null && brain.hasMemoryValue(getWorkMemory()) && brain.isActive(Activity.WORK)
                && canWork(xoonglin)) {
            state.workedTicksToday++;
        }

        if (structurePos == null) {
            brain.setMemory(CftMemoryModuleType.STRUCTURE_NEEDED.get(), requiredStructure);
            brain.eraseMemory(getWorkMemory());
        } else if (state.workedTicksToday < neededTicks && canWork(xoonglin)
                && JobUtil.checkWorkplace(xoonglin, this)) {
            brain.setMemory(getWorkMemory(), Boolean.TRUE);
            brain.eraseMemory(CftMemoryModuleType.STRUCTURE_NEEDED.get());
        } else {
            brain.eraseMemory(getWorkMemory());
            brain.eraseMemory(CftMemoryModuleType.STRUCTURE_NEEDED.get());
        }
    }

    @Override
    public MemoryModuleType<Boolean> getWorkMemory() {
        return CftfcMemoryModuleTypes.MUST_KEEP_FIRES.get();
    }

    @Override
    public void eraseMemories(XoonglinEntity xoonglin) {
        xoonglin.getBrain().eraseMemory(getWorkMemory());
        xoonglin.getBrain().eraseMemory(CftMemoryModuleType.STRUCTURE_NEEDED.get());
    }

    @Override
    public JobInfoData getDisplayInfo(XoonglinEntity xoonglin, JobState state) {
        int neededTicks = (int) Math.round(hoursPerDay * JobUtil.TICKS_PER_MC_HOUR);

        JobStatus status;
        if (xoonglin.getAssignedStructurePos(requiredStructure) == null) {
            status = noStructureStatus();
        } else if (!canWork(xoonglin)) {
            status = cantWorkStatus(xoonglin);
        } else if (state.workedTicksToday >= neededTicks) {
            status = JobStatus.RESTING;
        } else {
            status = JobUtil.workingStatus(xoonglin);
        }

        return new JobInfoData(status, List.of(JobDisplayEntry.progress("gui.cft.job_today",
                state.workedTicksToday, neededTicks, JobUtil.formatWorkTime(state.workedTicksToday, hoursPerDay))));
    }

    @Override
    public Codec<? extends Job> jobType() {
        return CftfcRegistry.FIREKEEPER_JOB.get();
    }
}
