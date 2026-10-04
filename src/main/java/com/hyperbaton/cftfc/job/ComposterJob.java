package com.hyperbaton.cftfc.job;

import com.hyperbaton.cft.job.Job;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

import static com.hyperbaton.cft.need.codec.CftCodec.INGREDIENT_CODEC;

/**
 * The Xoonglin makes compost in the TFC composters of its compost yard. It fills each composter with green
 * and brown items from the yard's chests, picking them so that they waste the least over what the composter
 * takes, and never adds anything that would rot the compost. Once a composter is done, it takes the compost
 * out and stores it in the chests, and fills the composter again. A composter someone else spoiled is
 * emptied of its rotten compost too.
 */
public class ComposterJob extends WorkplaceJob {

    public static final Codec<ComposterJob> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            propertiesCodec(),
            Codec.DOUBLE.fieldOf("hours_per_day").forGetter(ComposterJob::getHoursPerDay),
            ResourceLocation.CODEC.fieldOf("required_structure").forGetter(ComposterJob::getRequiredStructureType),
            INGREDIENT_CODEC.listOf().optionalFieldOf("materials", List.of()).forGetter(j -> j.materials),
            Codec.intRange(1, 64).optionalFieldOf("carry", 32).forGetter(ComposterJob::getCarry)
    ).apply(inst, ComposterJob::new));

    private final List<Ingredient> materials;
    private final int carry;

    public ComposterJob(Properties properties, double hoursPerDay, ResourceLocation requiredStructure,
                        List<Ingredient> materials, int carry) {
        super(properties, hoursPerDay, requiredStructure);
        this.materials = List.copyOf(materials);
        this.carry = carry;
    }

    /** Whether it may compost this item, if the composter takes it: any item, unless materials are listed. */
    public boolean mayCompost(ItemStack stack) {
        return materials.isEmpty() || materials.stream().anyMatch(ingredient -> ingredient.test(stack));
    }

    /** The most items it takes from the chests at once. */
    public int getCarry() {
        return carry;
    }

    @Override
    public MemoryModuleType<Boolean> getWorkMemory() {
        return CftfcMemoryModuleTypes.MUST_COMPOST.get();
    }

    @Override
    public Codec<? extends Job> jobType() {
        return CftfcRegistry.COMPOSTER_JOB.get();
    }
}
