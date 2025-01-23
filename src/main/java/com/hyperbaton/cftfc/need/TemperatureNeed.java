package com.hyperbaton.cftfc.need;

import com.hyperbaton.cft.need.Need;
import com.hyperbaton.cft.need.satisfaction.NeedSatisfier;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.need.satisfaction.TemperatureNeedSatisfier;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

public class TemperatureNeed extends Need {
    public static final Codec<TemperatureNeed> TEMPERATURE_NEED_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(TemperatureNeed::getId),
            Codec.DOUBLE.fieldOf("damage").forGetter(TemperatureNeed::getDamage),
            Codec.DOUBLE.fieldOf("damage_threshold").forGetter(TemperatureNeed::getDamageThreshold),
            Codec.DOUBLE.fieldOf("provided_happiness").forGetter(TemperatureNeed::getProvidedHappiness),
            Codec.DOUBLE.fieldOf("satisfaction_threshold").forGetter(TemperatureNeed::getSatisfactionThreshold),
            Codec.DOUBLE.fieldOf("frequency").forGetter(TemperatureNeed::getFrequency),
            Codec.BOOL.optionalFieldOf("hidden", DEFAULT_HIDDEN).forGetter(TemperatureNeed::isHidden),
            Codec.DOUBLE.fieldOf("min_average_temperature").forGetter(TemperatureNeed::getMinAverageTemperature),
            Codec.DOUBLE.fieldOf("max_average_temperature").forGetter(TemperatureNeed::getMaxAverageTemperature),
            SeasonalTemperatureLimits.SEASONAL_TEMPERATURE_LIMITS_CODEC.optionalFieldOf("seasonal_limits")
                    .forGetter(TemperatureNeed::getSeasonalLimits)
    ).apply(instance, TemperatureNeed::new));

    private final double minAverageTemperature;
    private final double maxAverageTemperature;
    private final Optional<SeasonalTemperatureLimits> seasonalLimits;

    public TemperatureNeed(String id, double damage, double damageThreshold, double providedHappiness,
                           double satisfactionThreshold, double frequency, boolean hidden,
                           double minAverageTemperature, double maxAverageTemperature,
                           Optional<SeasonalTemperatureLimits> seasonalLimits) {
        super(id, damage, damageThreshold, providedHappiness, satisfactionThreshold, frequency, hidden);
        this.minAverageTemperature = minAverageTemperature;
        this.maxAverageTemperature = maxAverageTemperature;
        this.seasonalLimits = seasonalLimits;
    }

    @Override
    public Codec<? extends Need> needType() {
        return CftfcRegistry.TEMPERATURE_NEED.get();
    }

    @Override
    public NeedSatisfier<? extends Need> createSatisfier() {
        return createSatisfier(this.getSatisfactionThreshold(), false);
    }

    @Override
    public NeedSatisfier<? extends Need> createSatisfier(double satisfaction, boolean isSatisfied) {
        return new TemperatureNeedSatisfier(satisfaction, isSatisfied, this);
    }

    public double getMinAverageTemperature() {
        return minAverageTemperature;
    }

    public double getMaxAverageTemperature() {
        return maxAverageTemperature;
    }

    public Optional<SeasonalTemperatureLimits> getSeasonalLimits() {
        return seasonalLimits;
    }
}
