package com.hyperbaton.cftfc.job;

import com.hyperbaton.cft.job.Job;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.common.recipes.QuernRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

import static com.hyperbaton.cft.need.codec.CftCodec.INGREDIENT_CODEC;

/**
 * The Xoonglin grinds at the TFC querns of its mill: it loads them with grain (or anything else with a
 * quern recipe) from the mill's chests, turns them, and stores what comes out, replacing worn-out
 * handstones from the chests too.
 */
public class MillerJob extends WorkplaceJob {

    public static final Codec<MillerJob> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            propertiesCodec(),
            Codec.DOUBLE.fieldOf("hours_per_day").forGetter(MillerJob::getHoursPerDay),
            ResourceLocation.CODEC.fieldOf("required_structure").forGetter(MillerJob::getRequiredStructureType),
            INGREDIENT_CODEC.listOf().optionalFieldOf("inputs", List.of()).forGetter(j -> j.inputs),
            Codec.intRange(1, 64).optionalFieldOf("load", 16).forGetter(MillerJob::getLoad)
    ).apply(inst, MillerJob::new));

    private final List<Ingredient> inputs;
    private final int load;

    public MillerJob(Properties properties, double hoursPerDay, ResourceLocation requiredStructure,
                     List<Ingredient> inputs, int load) {
        super(properties, hoursPerDay, requiredStructure);
        this.inputs = List.copyOf(inputs);
        this.load = load;
    }

    /** How many items it loads into a quern at once. */
    public int getLoad() {
        return load;
    }

    /** Whether it grinds this item: it must have a quern recipe, and be one of its inputs, if it has any. */
    public boolean grinds(ItemStack stack) {
        return QuernRecipe.getRecipe(stack) != null
                && (inputs.isEmpty() || inputs.stream().anyMatch(ingredient -> ingredient.test(stack)));
    }

    @Override
    public MemoryModuleType<Boolean> getWorkMemory() {
        return CftfcMemoryModuleTypes.MUST_GRIND.get();
    }

    @Override
    public Codec<? extends Job> jobType() {
        return CftfcRegistry.MILLER_JOB.get();
    }
}
