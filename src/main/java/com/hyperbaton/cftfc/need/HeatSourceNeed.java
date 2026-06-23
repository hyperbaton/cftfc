package com.hyperbaton.cftfc.need;

import com.hyperbaton.cft.need.Need;
import com.hyperbaton.cft.need.satisfaction.NeedSatisfier;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.need.satisfaction.HeatSourceNeedSatisfier;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

public class HeatSourceNeed extends Need {
    private static final ResourceLocation DEFAULT_ICON = ResourceLocation.fromNamespaceAndPath("tfc", "firepit");

    public static final Codec<HeatSourceNeed> HEAT_SOURCE_NEED_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(HeatSourceNeed::getId),
            Codec.DOUBLE.fieldOf("damage").forGetter(HeatSourceNeed::getDamage),
            Codec.DOUBLE.fieldOf("damage_threshold").forGetter(HeatSourceNeed::getDamageThreshold),
            Codec.DOUBLE.fieldOf("provided_happiness").forGetter(HeatSourceNeed::getProvidedHappiness),
            Codec.DOUBLE.fieldOf("satisfaction_threshold").forGetter(HeatSourceNeed::getSatisfactionThreshold),
            Codec.DOUBLE.fieldOf("frequency").forGetter(HeatSourceNeed::getFrequency),
            Codec.BOOL.optionalFieldOf("hidden", DEFAULT_HIDDEN).forGetter(HeatSourceNeed::isHidden),
            ResourceLocation.CODEC.optionalFieldOf("icon").forGetter(Need::getIcon),
            Codec.INT.fieldOf("search_radius").forGetter(HeatSourceNeed::getSearchRadius),
            Codec.DOUBLE.optionalFieldOf("temperature_threshold").forGetter(HeatSourceNeed::getTemperatureThreshold),
            Codec.DOUBLE.optionalFieldOf("min_source_temperature", 0.0).forGetter(HeatSourceNeed::getMinSourceTemperature),
            Codec.DOUBLE.optionalFieldOf("max_source_temperature", Double.MAX_VALUE).forGetter(HeatSourceNeed::getMaxSourceTemperature)
    ).apply(instance, HeatSourceNeed::new));

    private final int searchRadius;
    private final Optional<Double> temperatureThreshold;
    private final double minSourceTemperature;
    private final double maxSourceTemperature;

    public HeatSourceNeed(String id, double damage, double damageThreshold, double providedHappiness,
                          double satisfactionThreshold, double frequency, boolean hidden,
                          Optional<ResourceLocation> icon,
                          int searchRadius, Optional<Double> temperatureThreshold,
                          double minSourceTemperature, double maxSourceTemperature) {
        super(id, damage, damageThreshold, providedHappiness, satisfactionThreshold, frequency, hidden, icon);
        this.searchRadius = searchRadius;
        this.temperatureThreshold = temperatureThreshold;
        this.minSourceTemperature = minSourceTemperature;
        this.maxSourceTemperature = maxSourceTemperature;
    }

    @Override
    public Codec<? extends Need> needType() {
        return CftfcRegistry.HEAT_SOURCE_NEED.get();
    }

    @Override
    public NeedSatisfier<? extends Need> createSatisfier(double satisfaction, boolean isSatisfied) {
        return new HeatSourceNeedSatisfier(satisfaction, isSatisfied, this);
    }

    @Override
    public String getTypeName() {
        return Component.translatable("gui.cftfc.need_type.heat_source").getString();
    }

    @Override
    public List<ResourceLocation> getDefaultIcons() {
        return List.of(DEFAULT_ICON);
    }

    public int getSearchRadius() {
        return searchRadius;
    }

    public Optional<Double> getTemperatureThreshold() {
        return temperatureThreshold;
    }

    public double getMinSourceTemperature() {
        return minSourceTemperature;
    }

    public double getMaxSourceTemperature() {
        return maxSourceTemperature;
    }
}
