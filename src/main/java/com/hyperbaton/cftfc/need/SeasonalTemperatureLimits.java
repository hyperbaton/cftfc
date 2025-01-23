package com.hyperbaton.cftfc.need;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record SeasonalTemperatureLimits(double minSpringTemperature, double maxSpringTemperature,
                                        double minSummerTemperature, double maxSummerTemperature,
                                        double minFallTemperature, double maxFallTemperature,
                                        double minWinterTemperature, double maxWinterTemperature) {
    public static final Codec<SeasonalTemperatureLimits> SEASONAL_TEMPERATURE_LIMITS_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("min_spring_temperature").forGetter(SeasonalTemperatureLimits::minSpringTemperature),
            Codec.DOUBLE.fieldOf("max_spring_temperature").forGetter(SeasonalTemperatureLimits::maxSpringTemperature),
            Codec.DOUBLE.fieldOf("min_summer_temperature").forGetter(SeasonalTemperatureLimits::minSummerTemperature),
            Codec.DOUBLE.fieldOf("max_summer_temperature").forGetter(SeasonalTemperatureLimits::maxSummerTemperature),
            Codec.DOUBLE.fieldOf("min_fall_temperature").forGetter(SeasonalTemperatureLimits::minFallTemperature),
            Codec.DOUBLE.fieldOf("max_fall_temperature").forGetter(SeasonalTemperatureLimits::maxFallTemperature),
            Codec.DOUBLE.fieldOf("min_winter_temperature").forGetter(SeasonalTemperatureLimits::minWinterTemperature),
            Codec.DOUBLE.fieldOf("max_winter_temperature").forGetter(SeasonalTemperatureLimits::maxWinterTemperature)
    ).apply(instance, SeasonalTemperatureLimits::new));

}
