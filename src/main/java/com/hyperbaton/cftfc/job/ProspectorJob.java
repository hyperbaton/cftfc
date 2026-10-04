package com.hyperbaton.cftfc.job;

import com.hyperbaton.cft.job.Job;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.common.blockentities.SluiceBlockEntity;
import net.dries007.tfc.common.blocks.devices.SluiceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

import static com.hyperbaton.cft.need.codec.CftCodec.INGREDIENT_CODEC;

/**
 * The Xoonglin works the TFC sluices around its camp: it loads them with ore deposits from the camp's
 * chests, picks up what they wash out, and brings it back to the camp's chests.
 */
public class ProspectorJob extends WorkplaceJob {

    public static final Codec<ProspectorJob> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            propertiesCodec(),
            Codec.DOUBLE.fieldOf("hours_per_day").forGetter(ProspectorJob::getHoursPerDay),
            ResourceLocation.CODEC.fieldOf("required_structure").forGetter(ProspectorJob::getRequiredStructureType),
            Codec.intRange(1, 128).optionalFieldOf("radius", 24).forGetter(ProspectorJob::getRadius),
            INGREDIENT_CODEC.listOf().optionalFieldOf("inputs", List.of()).forGetter(j -> j.inputs),
            Codec.intRange(1, SluiceBlockEntity.MAX_SOIL).optionalFieldOf("min_load", 8).forGetter(ProspectorJob::getMinLoad),
            Codec.intRange(1, 256).optionalFieldOf("carry", 32).forGetter(ProspectorJob::getCarry)
    ).apply(inst, ProspectorJob::new));

    private final int radius;
    private final List<Ingredient> inputs;
    private final int minLoad;
    private final int carry;

    public ProspectorJob(Properties properties, double hoursPerDay, ResourceLocation requiredStructure, int radius,
                         List<Ingredient> inputs, int minLoad, int carry) {
        super(properties, hoursPerDay, requiredStructure);
        this.radius = radius;
        this.inputs = List.copyOf(inputs);
        this.minLoad = minLoad;
        this.carry = carry;
    }

    /** How far from the camp's key block the sluices it works can be. */
    public int getRadius() {
        return radius;
    }

    /** A sluice is loaded again when it holds fewer deposits than this. */
    public int getMinLoad() {
        return minLoad;
    }

    /** How many deposits it takes from the camp for a round. */
    public int getCarry() {
        return carry;
    }

    /** Whether it may put this item in a sluice: the sluice must wash it, and it must be one of its inputs. */
    public boolean loads(ItemStack stack, SluiceBlockEntity sluice) {
        return sluice.isItemValid(0, stack)
                && (inputs.isEmpty() || inputs.stream().anyMatch(ingredient -> ingredient.test(stack)));
    }

    /** The sluices within its radius of the camp that have water running through them. */
    public List<BlockPos> findWorkingSluices(Level level, BlockPos camp) {
        List<BlockPos> found = new ArrayList<>();
        long maxDistSqr = (long) radius * radius;
        for (int chunkX = SectionPos.blockToSectionCoord(camp.getX() - radius);
             chunkX <= SectionPos.blockToSectionCoord(camp.getX() + radius); chunkX++) {
            for (int chunkZ = SectionPos.blockToSectionCoord(camp.getZ() - radius);
                 chunkZ <= SectionPos.blockToSectionCoord(camp.getZ() + radius); chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) continue;
                for (BlockEntity blockEntity : level.getChunk(chunkX, chunkZ).getBlockEntities().values()) {
                    if (blockEntity instanceof SluiceBlockEntity sluice
                            && blockEntity.getBlockPos().distSqr(camp) <= maxDistSqr
                            && sluice.getBlockState().getOptionalValue(SluiceBlock.UPPER).orElse(false)
                            && sluice.getFlow() != null) {
                        found.add(blockEntity.getBlockPos());
                    }
                }
            }
        }
        return found;
    }

    @Override
    public MemoryModuleType<Boolean> getWorkMemory() {
        return CftfcMemoryModuleTypes.MUST_PROSPECT.get();
    }

    @Override
    public Codec<? extends Job> jobType() {
        return CftfcRegistry.PROSPECTOR_JOB.get();
    }
}
