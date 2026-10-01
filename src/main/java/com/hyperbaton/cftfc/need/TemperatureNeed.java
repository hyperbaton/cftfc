package com.hyperbaton.cftfc.need;

import com.hyperbaton.cft.need.Need;
import com.hyperbaton.cft.need.satisfaction.NeedSatisfier;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.need.satisfaction.TemperatureNeedSatisfier;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

public class TemperatureNeed extends Need {
    private static final ResourceLocation DEFAULT_ICON = ResourceLocation.fromNamespaceAndPath("tfc", "thermometer");

    public static final Codec<TemperatureNeed> TEMPERATURE_NEED_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            propertiesCodec(),
            Codec.DOUBLE.fieldOf("min_average_temperature").forGetter(TemperatureNeed::getMinAverageTemperature),
            Codec.DOUBLE.fieldOf("max_average_temperature").forGetter(TemperatureNeed::getMaxAverageTemperature),
            SeasonalTemperatureLimits.SEASONAL_TEMPERATURE_LIMITS_CODEC.optionalFieldOf("seasonal_limits")
                    .forGetter(TemperatureNeed::getSeasonalLimits)
    ).apply(instance, TemperatureNeed::new));

    private final double minAverageTemperature;
    private final double maxAverageTemperature;
    private final Optional<SeasonalTemperatureLimits> seasonalLimits;

    public TemperatureNeed(Properties properties,
                           double minAverageTemperature, double maxAverageTemperature,
                           Optional<SeasonalTemperatureLimits> seasonalLimits) {
        super(properties);
        this.minAverageTemperature = minAverageTemperature;
        this.maxAverageTemperature = maxAverageTemperature;
        this.seasonalLimits = seasonalLimits;
    }

    @Override
    public Codec<? extends Need> needType() {
        return CftfcRegistry.TEMPERATURE_NEED.get();
    }

    @Override
    public NeedSatisfier<? extends Need> createSatisfier(double satisfaction, boolean isSatisfied) {
        return new TemperatureNeedSatisfier(satisfaction, isSatisfied, this);
    }

    @Override
    public String getTypeName() {
        return Component.translatable("gui.cftfc.need_type.temperature").getString();
    }

    @Override
    public List<ResourceLocation> getDefaultIcons() {
        return List.of(DEFAULT_ICON);
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
