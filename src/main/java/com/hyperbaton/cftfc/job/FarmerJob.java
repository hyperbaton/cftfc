package com.hyperbaton.cftfc.job;

import com.hyperbaton.cft.job.Job;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.util.data.Fertilizer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;
import java.util.OptionalDouble;

import static com.hyperbaton.cft.need.codec.CftCodec.INGREDIENT_CODEC;

/**
 * The Xoonglin farms the farmland of its farm, as TFC farming works: it harvests ripe crops, clears dead
 * ones, and chooses what to sow in each plot from the seeds in the farm's chests. It picks the crop that
 * will grow through the weather to come and that gives the best harvest while keeping the soil's
 * nutrients up, rotating crops that deplete a nutrient with crops that restore it, and spreads fertilizer
 * where the crop would lack something. See {@link CropPlanner}.
 */
public class FarmerJob extends WorkplaceJob {

    public static final Codec<FarmerJob> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            propertiesCodec(),
            Codec.DOUBLE.fieldOf("hours_per_day").forGetter(FarmerJob::getHoursPerDay),
            ResourceLocation.CODEC.fieldOf("required_structure").forGetter(FarmerJob::getRequiredStructureType),
            CropEntry.CODEC.listOf().optionalFieldOf("crops", List.of()).forGetter(j -> j.crops),
            Codec.BOOL.optionalFieldOf("fertilize", true).forGetter(j -> j.fertilize),
            INGREDIENT_CODEC.listOf().optionalFieldOf("fertilizers", List.of()).forGetter(j -> j.fertilizers),
            Codec.doubleRange(0, 10).optionalFieldOf("soil_weight", 0.5).forGetter(FarmerJob::getSoilWeight),
            Codec.intRange(1, 3).optionalFieldOf("lookahead", 2).forGetter(FarmerJob::getLookahead),
            Codec.intRange(1, 64).optionalFieldOf("carry", 32).forGetter(FarmerJob::getCarry)
    ).apply(inst, FarmerJob::new));

    /** A crop it may sow, by its seed, and how much it values that crop's harvest. */
    public record CropEntry(Item seed, double weight) {
        public static final Codec<CropEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("seed").forGetter(CropEntry::seed),
                Codec.doubleRange(0, 100).optionalFieldOf("weight", 1.0).forGetter(CropEntry::weight)
        ).apply(inst, CropEntry::new));
    }

    private final List<CropEntry> crops;
    private final boolean fertilize;
    private final List<Ingredient> fertilizers;
    private final double soilWeight;
    private final int lookahead;
    private final int carry;

    public FarmerJob(Properties properties, double hoursPerDay, ResourceLocation requiredStructure,
                     List<CropEntry> crops, boolean fertilize, List<Ingredient> fertilizers,
                     double soilWeight, int lookahead, int carry) {
        super(properties, hoursPerDay, requiredStructure);
        this.crops = List.copyOf(crops);
        this.fertilize = fertilize;
        this.fertilizers = List.copyOf(fertilizers);
        this.soilWeight = soilWeight;
        this.lookahead = lookahead;
        this.carry = carry;
    }

    /**
     * How much it values the harvest of the crop this seed grows, if it sows it at all: the weight listed
     * for it, or 1 for any seed when no crops are listed.
     */
    public OptionalDouble weightOf(Item seed) {
        if (crops.isEmpty()) return OptionalDouble.of(1);
        return crops.stream()
                .filter(entry -> entry.seed() == seed)
                .mapToDouble(CropEntry::weight)
                .findFirst();
    }

    /** The fertilizer TFC knows this item as, if it's one it spreads. */
    public Fertilizer fertilizerOf(ItemStack stack) {
        if (!fertilize || stack.isEmpty()) return null;
        if (!fertilizers.isEmpty() && fertilizers.stream().noneMatch(ingredient -> ingredient.test(stack))) {
            return null;
        }
        return Fertilizer.get(stack);
    }

    /** How much it values healthy soil left behind, against the harvest. */
    public double getSoilWeight() {
        return soilWeight;
    }

    /** How many crops ahead it plans each plot. */
    public int getLookahead() {
        return lookahead;
    }

    /** The most seeds of a kind, and the most fertilizer, it carries from the chests at once. */
    public int getCarry() {
        return carry;
    }

    @Override
    public MemoryModuleType<Boolean> getWorkMemory() {
        return CftfcMemoryModuleTypes.MUST_FARM.get();
    }

    @Override
    public Codec<? extends Job> jobType() {
        return CftfcRegistry.FARMER_JOB.get();
    }
}
