package com.hyperbaton.cftfc.job;

import com.hyperbaton.cft.job.Job;
import com.hyperbaton.cft.util.RegistryEntries;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
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
public class FirekeeperJob extends WorkplaceJob {

    private static final RegistryEntries<Block> DEFAULT_FIRES = RegistryEntries.of(
            ResourceLocation.fromNamespaceAndPath("tfc", "firepit"),
            ResourceLocation.fromNamespaceAndPath("tfc", "grill"),
            ResourceLocation.fromNamespaceAndPath("tfc", "pot"));

    public static final Codec<FirekeeperJob> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            propertiesCodec(),
            Codec.DOUBLE.fieldOf("hours_per_day").forGetter(FirekeeperJob::getHoursPerDay),
            ResourceLocation.CODEC.fieldOf("required_structure").forGetter(FirekeeperJob::getRequiredStructureType),
            Codec.intRange(1, 128).optionalFieldOf("radius", 32).forGetter(FirekeeperJob::getRadius),
            RegistryEntries.codec(Registries.BLOCK).optionalFieldOf("fires", DEFAULT_FIRES).forGetter(j -> j.fires),
            INGREDIENT_CODEC.listOf().optionalFieldOf("fuel", List.of()).forGetter(j -> j.fuel),
            Codec.intRange(1, 8).optionalFieldOf("min_fuel", 2).forGetter(FirekeeperJob::getMinFuel),
            Codec.BOOL.optionalFieldOf("relight", true).forGetter(FirekeeperJob::relights),
            Codec.intRange(1, 64).optionalFieldOf("carry", 8).forGetter(FirekeeperJob::getCarry)
    ).apply(inst, FirekeeperJob::new));

    private final int radius;
    private final RegistryEntries<Block> fires;
    private final List<Ingredient> fuel;
    private final int minFuel;
    private final boolean relight;
    private final int carry;

    public FirekeeperJob(Properties properties, double hoursPerDay, ResourceLocation requiredStructure, int radius,
                         RegistryEntries<Block> fires, List<Ingredient> fuel, int minFuel, boolean relight, int carry) {
        super(properties, hoursPerDay, requiredStructure);
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
    public MemoryModuleType<Boolean> getWorkMemory() {
        return CftfcMemoryModuleTypes.MUST_KEEP_FIRES.get();
    }

    @Override
    public Codec<? extends Job> jobType() {
        return CftfcRegistry.FIREKEEPER_JOB.get();
    }
}
