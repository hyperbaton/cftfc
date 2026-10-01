package com.hyperbaton.cftfc.need;

import com.hyperbaton.cft.need.Need;
import com.hyperbaton.cft.need.satisfaction.NeedSatisfier;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.need.satisfaction.RainfallNeedSatisfier;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public class RainfallNeed extends Need {
    private static final ResourceLocation DEFAULT_ICON = ResourceLocation.fromNamespaceAndPath("tfc", "wooden_bucket");

    public static final Codec<RainfallNeed> RAINFALL_NEED_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            propertiesCodec(),
            Codec.DOUBLE.fieldOf("min_rainfall").forGetter(RainfallNeed::getMinRainfall),
            Codec.DOUBLE.fieldOf("max_rainfall").forGetter(RainfallNeed::getMaxRainfall)
    ).apply(instance, RainfallNeed::new));

    private double minRainfall;
    private double maxRainfall;

    public RainfallNeed(Properties properties, double minRainfall, double maxRainfall) {
        super(properties);
        this.minRainfall = minRainfall;
        this.maxRainfall = maxRainfall;
    }

    @Override
    public Codec<? extends Need> needType() {
        return CftfcRegistry.RAINFALL_NEED.get();
    }

    @Override
    public NeedSatisfier<? extends Need> createSatisfier(double satisfaction, boolean isSatisfied) {
        return new RainfallNeedSatisfier(satisfaction, isSatisfied, this);
    }

    @Override
    public String getTypeName() {
        return Component.translatable("gui.cftfc.need_type.rainfall").getString();    }

    @Override
    public List<ResourceLocation> getDefaultIcons() {
        return List.of(DEFAULT_ICON);
    }

    public double getMinRainfall() {
        return minRainfall;
    }

    public void setMinRainfall(double minRainfall) {
        this.minRainfall = minRainfall;
    }

    public double getMaxRainfall() {
        return maxRainfall;
    }

    public void setMaxRainfall(double maxRainfall) {
        this.maxRainfall = maxRainfall;
    }
}
