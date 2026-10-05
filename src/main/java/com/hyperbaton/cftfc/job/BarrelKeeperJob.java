package com.hyperbaton.cftfc.job;

import com.hyperbaton.cft.job.Job;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.material.Fluid;

import java.util.List;

/**
 * The Xoonglin makes liquids in the barrels of its barrel yard, from TFC's own barrel recipes. It looks at the
 * liquids the yard has, in its barrels, wells and aqueducts, and the items in its chests, and makes as much as it
 * can of whatever it can make from them, sharing its barrels among the liquids it can make. To make brine, for
 * instance, it fills a barrel with water from the yard's well, salts it into salt water, and pours in vinegar,
 * which it makes in another barrel from fruit and cider, which it brews from water and apples. The liquids stay in
 * the barrels for someone else to take. See {@link BarrelChains}.
 */
public class BarrelKeeperJob extends WorkplaceJob {

    public static final Codec<BarrelKeeperJob> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            propertiesCodec(),
            Codec.DOUBLE.fieldOf("hours_per_day").forGetter(BarrelKeeperJob::getHoursPerDay),
            ResourceLocation.CODEC.fieldOf("required_structure").forGetter(BarrelKeeperJob::getRequiredStructureType),
            BuiltInRegistries.FLUID.byNameCodec().listOf().optionalFieldOf("products", List.of())
                    .forGetter(BarrelKeeperJob::getProducts)
    ).apply(inst, BarrelKeeperJob::new));

    private final List<Fluid> products;

    public BarrelKeeperJob(Properties properties, double hoursPerDay, ResourceLocation requiredStructure,
                           List<Fluid> products) {
        super(properties, hoursPerDay, requiredStructure);
        this.products = List.copyOf(products);
    }

    /**
     * The liquids it makes, if only some: it makes the liquids along the way to them as well. Empty for every
     * liquid barrels make that no barrel turns into another one.
     */
    public List<Fluid> getProducts() {
        return products;
    }

    @Override
    public MemoryModuleType<Boolean> getWorkMemory() {
        return CftfcMemoryModuleTypes.MUST_KEEP_BARRELS.get();
    }

    @Override
    public Codec<? extends Job> jobType() {
        return CftfcRegistry.BARREL_KEEPER_JOB.get();
    }
}
