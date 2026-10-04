package com.hyperbaton.cftfc.job;

import net.dries007.tfc.common.blockentities.FarmlandBlockEntity.NutrientType;
import net.dries007.tfc.common.blockentities.IFarmland;
import net.dries007.tfc.common.blocks.crop.CropBlock;
import net.dries007.tfc.common.blocks.crop.DefaultCropBlock;
import net.dries007.tfc.common.blocks.crop.FloodedCropBlock;
import net.dries007.tfc.common.blocks.crop.PickableCropBlock;
import net.dries007.tfc.common.blocks.crop.SpreadingCropBlock;
import net.dries007.tfc.common.blocks.soil.FarmlandBlock;
import net.dries007.tfc.config.TFCConfig;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.calendar.ICalendar;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateRange;
import net.dries007.tfc.util.data.Fertilizer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chooses what to sow in a plot, from a model of how TFC crops grow.
 *
 * <p>In TFC, farmland holds nitrogen, phosphorus and potassium, from 0 to 1. Each crop takes up to a set
 * amount of some of them while it grows, and gives some back of the others: cereals take nitrogen and give
 * phosphorus and potassium, legumes the other way round, cover crops give all three. A crop yields fully
 * while the soil holds what it takes, and down to a fifth of that when it doesn't. It grows only while the
 * temperature and the farmland's hydration are within its range, and dies when they get too far out of it.
 *
 * <p>For each crop it has seeds for, the planner checks the weather forecast for the crop's growing time,
 * and scores it by the harvest it expects, times how much the farmer values that crop, plus how healthy
 * the soil is left. It looks a few crops ahead, so it will sow a legume now when that makes the wheat after
 * it yield fully. It counts the fertilizer it has for the plot as part of the soil it sows in.
 */
public final class CropPlanner {

    /** How much the crops after this one count, against this one. */
    private static final double DISCOUNT = 0.5;
    /** The least share of its growing time a crop must spend growing for it to be worth sowing. */
    private static final double MIN_GROWING_FRACTION = 0.75;
    /** The yield of a crop that gets none of the nutrients it takes, against one that gets them all. */
    private static final float YIELD_MIN = 0.2f;
    /** The most fertilizer it spreads on a block before sowing it. */
    private static final int MAX_FERTILIZER_PER_BLOCK = 4;
    /** Fertilizer that makes up less than this of what the crop lacks isn't worth spreading. */
    private static final float MIN_USEFUL_FERTILIZER = 0.02f;
    /** The most weather samples it takes over a crop's growing time. */
    private static final int MAX_SAMPLES = 192;
    /** The least time between weather samples, in ticks. */
    private static final long MIN_SAMPLE_INTERVAL = 3000;
    /** How long a crop takes to grow at TFC's default growth speed, in days. */
    private static final int GROWTH_DAYS = 24;

    private final List<Candidate> candidates;
    /** For each window of the forecast and each candidate, the share of that window it would grow. */
    private final double[][] growing;
    private final float soilModifier;
    private final double soilWeight;

    /**
     * @param candidates   the crops it may sow, with how much it values each
     * @param forecast     the weather for each crop it plans ahead, one after another
     * @param soilModifier how much more or less the plot's soil kind gives back than normal farmland
     * @param soilWeight   how much it values healthy soil left behind, against the harvest
     */
    public CropPlanner(List<Candidate> candidates, List<Forecast> forecast, float soilModifier, double soilWeight) {
        this.candidates = List.copyOf(candidates);
        this.growing = new double[forecast.size()][candidates.size()];
        for (int window = 0; window < forecast.size(); window++) {
            for (int i = 0; i < candidates.size(); i++) {
                growing[window][i] = forecast.get(window).growingFraction(candidates.get(i).crop().climate());
            }
        }
        this.soilModifier = soilModifier;
        this.soilWeight = soilWeight;
    }

    /**
     * The crop to sow now in soil like this, if any would grow through the weather to come.
     *
     * @param fertilizerPerBlock how much of each fertilizer it can spread on each block it sows
     */
    public Optional<CropInfo> choose(Nutrients soil, Map<Fertilizer, Integer> fertilizerPerBlock) {
        if (growing.length == 0) return Optional.empty();
        CropInfo best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < candidates.size(); i++) {
            if (growing[0][i] < MIN_GROWING_FRACTION) continue;
            CropInfo crop = candidates.get(i).crop();
            Nutrients fertilized = soil;
            for (Fertilizer fertilizer : fertilizersFor(crop, soil, fertilizerPerBlock)) {
                fertilized = fertilized.plus(fertilizer);
            }
            double value = valueOf(i, 0, fertilized, growing.length);
            if (value > bestValue) {
                best = crop;
                bestValue = value;
            }
        }
        return Optional.ofNullable(best);
    }

    /** What sowing a candidate in a window is worth: its harvest, and what's left for the crops after it. */
    private double valueOf(int index, int window, Nutrients soil, int depth) {
        Candidate candidate = candidates.get(index);
        float fed = fed(candidate.crop(), soil);
        double harvest = Mth.lerp(fed, YIELD_MIN, 1) * growing[window][index];
        Nutrients after = after(candidate.crop(), soil, fed, soilModifier);
        return candidate.weight() * harvest + future(after, window + 1, depth - 1);
    }

    /** What soil like this is worth for the crops after: the best of them, or the soil itself at the end. */
    private double future(Nutrients soil, int window, int depth) {
        if (depth > 0 && window < growing.length) {
            double best = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < candidates.size(); i++) {
                if (growing[window][i] >= MIN_GROWING_FRACTION) {
                    best = Math.max(best, valueOf(i, window, soil, depth));
                }
            }
            if (best > Double.NEGATIVE_INFINITY) return DISCOUNT * best;
        }
        // Nothing to sow then: the soil keeps what it has
        return soilWeight * soil.health();
    }

    /**
     * How well soil like this feeds the crop, from 0 to 1: the share of the nutrients it takes that the soil
     * holds. As in TFC, a crop that takes none counts as unfed, and yields the least.
     */
    public static float fed(CropInfo crop, Nutrients soil) {
        float needed = 0, available = 0;
        for (NutrientType type : NutrientType.VALUES) {
            float need = crop.need(type);
            if (need > 0) {
                needed += need;
                available += Math.min(need, soil.get(type));
            }
        }
        return needed > 0 ? available / needed : 0;
    }

    /**
     * The soil once the crop is grown: it took what it takes, as much as the soil held, and gave back what it
     * gives, more the better it was fed, as TFC's farmland does.
     */
    public static Nutrients after(CropInfo crop, Nutrients soil, float fed, float soilModifier) {
        float[] values = new float[3];
        for (NutrientType type : NutrientType.VALUES) {
            float need = crop.need(type);
            float value = soil.get(type);
            if (need > 0) {
                value -= Math.min(need, value);
            } else if (need < 0) {
                value += -need * soilModifier * (0.3f + 0.7f * fed);
            }
            values[type.ordinal()] = value;
        }
        return new Nutrients(values[0], values[1], values[2]);
    }

    /**
     * The fertilizer to spread on soil like this before sowing the crop, from what's available: each time the
     * one that makes up the most of what the crop lacks, wasting the least over the soil's limit.
     */
    public static List<Fertilizer> fertilizersFor(CropInfo crop, Nutrients soil, Map<Fertilizer, Integer> available) {
        Map<Fertilizer, Integer> left = new IdentityHashMap<>(available);
        List<Fertilizer> spread = new ArrayList<>();
        Nutrients current = soil;
        while (spread.size() < MAX_FERTILIZER_PER_BLOCK) {
            Fertilizer best = null;
            float bestUseful = MIN_USEFUL_FERTILIZER;
            float bestWaste = Float.MAX_VALUE;
            for (Map.Entry<Fertilizer, Integer> entry : left.entrySet()) {
                if (entry.getValue() <= 0) continue;
                Fertilizer fertilizer = entry.getKey();
                float useful = 0, waste = 0;
                for (NutrientType type : NutrientType.VALUES) {
                    float adds = fertilizer.getNutrient(type);
                    float lacking = Math.max(0, crop.need(type) - current.get(type));
                    useful += Math.min(adds, lacking);
                    waste += Math.max(0, current.get(type) + adds - 1);
                }
                if (useful > bestUseful + 1e-4f || (useful > bestUseful - 1e-4f && best != null && waste < bestWaste)) {
                    best = fertilizer;
                    bestUseful = useful;
                    bestWaste = waste;
                }
            }
            if (best == null) break;
            spread.add(best);
            left.merge(best, -1, Integer::sum);
            current = current.plus(best);
        }
        return spread;
    }

    /** How long a crop takes to grow, in ticks, at the server's crop growth speed. */
    public static long growthTicks() {
        return (long) (GROWTH_DAYS * ICalendar.TICKS_IN_DAY * TFCConfig.SERVER.cropGrowthModifier.get());
    }

    /** A crop it may sow, and how much it values its harvest. */
    public record Candidate(CropInfo crop, double weight) {
    }

    /** A crop TFC grows from a seed, which it knows how to sow and harvest: one block tall, on dry farmland. */
    public record CropInfo(Item seed, CropBlock block) {

        public static Optional<CropInfo> of(Item seed) {
            if (seed instanceof BlockItem blockItem && supports(blockItem.getBlock())) {
                return Optional.of(new CropInfo(seed, (CropBlock) blockItem.getBlock()));
            }
            return Optional.empty();
        }

        /** Whether it's a crop it knows how to sow and harvest. */
        public static boolean supports(Block block) {
            return block instanceof DefaultCropBlock && !(block instanceof FloodedCropBlock)
                    && !(block instanceof SpreadingCropBlock) && !(block instanceof PickableCropBlock);
        }

        /** How much of a nutrient it takes while it grows, or gives back if negative. */
        public float need(NutrientType type) {
            return switch (type) {
                case NITROGEN -> block.getNForGrowth();
                case PHOSPHOROUS -> block.getPForGrowth();
                case POTASSIUM -> block.getKForGrowth();
            };
        }

        public ClimateRange climate() {
            return block.getClimateRange();
        }
    }

    /** How much nitrogen, phosphorus and potassium soil holds, each from 0 to 1. */
    public record Nutrients(float nitrogen, float phosphorus, float potassium) {

        public Nutrients {
            nitrogen = Mth.clamp(nitrogen, 0, 1);
            phosphorus = Mth.clamp(phosphorus, 0, 1);
            potassium = Mth.clamp(potassium, 0, 1);
        }

        public static Nutrients of(IFarmland farmland) {
            return new Nutrients(farmland.getNutrient(NutrientType.NITROGEN),
                    farmland.getNutrient(NutrientType.PHOSPHOROUS), farmland.getNutrient(NutrientType.POTASSIUM));
        }

        /** The average of some soils. */
        public static Nutrients average(List<Nutrients> soils) {
            if (soils.isEmpty()) return new Nutrients(0, 0, 0);
            float n = 0, p = 0, k = 0;
            for (Nutrients soil : soils) {
                n += soil.nitrogen;
                p += soil.phosphorus;
                k += soil.potassium;
            }
            return new Nutrients(n / soils.size(), p / soils.size(), k / soils.size());
        }

        public float get(NutrientType type) {
            return switch (type) {
                case NITROGEN -> nitrogen;
                case PHOSPHOROUS -> phosphorus;
                case POTASSIUM -> potassium;
            };
        }

        public Nutrients plus(Fertilizer fertilizer) {
            return new Nutrients(nitrogen + fertilizer.nitrogen(), phosphorus + fertilizer.phosphorus(),
                    potassium + fertilizer.potassium());
        }

        /** How good the soil is for whatever comes next: its scarcest nutrient as much as all of them. */
        public float health() {
            float min = Math.min(nitrogen, Math.min(phosphorus, potassium));
            float mean = (nitrogen + phosphorus + potassium) / 3;
            return (min + mean) / 2;
        }
    }

    /** The weather a crop would grow in: temperatures, and the hydration of the plot's driest and wettest farmland. */
    public record Forecast(List<Sample> samples) {

        public record Sample(float temperature, int driestHydration, int wettestHydration) {
        }

        /**
         * Samples the weather over a time to come.
         *
         * @param cropPos  where the crops grow, for the temperature
         * @param driest   the plot's driest farmland now; it stays the driest, since rain wets all of it alike
         * @param wettest  the plot's wettest farmland now
         * @param from     how far ahead it starts, in ticks
         * @param duration how long it lasts, in ticks
         */
        public static Forecast of(Level level, BlockPos cropPos, BlockPos driest, BlockPos wettest, long from, long duration) {
            ICalendar calendar = Calendars.get(level);
            long interval = Math.max(MIN_SAMPLE_INTERVAL, duration / MAX_SAMPLES);
            List<Sample> samples = new ArrayList<>();
            for (long offset = from; offset <= from + duration; offset += interval) {
                long tick = calendar.getCalendarTickFromOffset(offset);
                float temperature = Climate.getInstantTemperature(level, cropPos, calendar, tick);
                int rain = FarmlandBlock.getInstantRainHydration(level, driest, tick);
                samples.add(new Sample(temperature,
                        FarmlandBlock.getInstantHydrationFromRainHydration(level, driest, rain),
                        FarmlandBlock.getInstantHydrationFromRainHydration(level, wettest, rain)));
            }
            return new Forecast(samples);
        }

        /**
         * The share of this time a crop with this climate range would grow, everywhere in the plot; 0 if it
         * would die at some point.
         */
        public double growingFraction(ClimateRange range) {
            if (samples.isEmpty()) return 0;
            int growingSamples = 0;
            for (Sample sample : samples) {
                if (!range.checkBoth(sample.driestHydration(), sample.temperature(), true)
                        || !range.checkBoth(sample.wettestHydration(), sample.temperature(), true)) {
                    return 0;
                }
                if (range.checkBoth(sample.driestHydration(), sample.temperature(), false)
                        && range.checkBoth(sample.wettestHydration(), sample.temperature(), false)) {
                    growingSamples++;
                }
            }
            return (double) growingSamples / samples.size();
        }
    }
}
