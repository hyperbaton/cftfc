package com.hyperbaton.cftfc.need;

import com.hyperbaton.cft.need.Need;
import com.hyperbaton.cft.need.satisfaction.NeedSatisfier;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.need.satisfaction.RainfallNeedSatisfier;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class RainfallNeed extends Need {
    public static final Codec<RainfallNeed> RAINFALL_NEED_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(RainfallNeed::getId),
            Codec.DOUBLE.fieldOf("damage").forGetter(RainfallNeed::getDamage),
            Codec.DOUBLE.fieldOf("damage_threshold").forGetter(RainfallNeed::getDamageThreshold),
            Codec.DOUBLE.fieldOf("provided_happiness").forGetter(RainfallNeed::getProvidedHappiness),
            Codec.DOUBLE.fieldOf("satisfaction_threshold").forGetter(RainfallNeed::getSatisfactionThreshold),
            Codec.DOUBLE.fieldOf("frequency").forGetter(RainfallNeed::getFrequency),
            Codec.BOOL.optionalFieldOf("hidden", DEFAULT_HIDDEN).forGetter(RainfallNeed::isHidden),
            Codec.DOUBLE.fieldOf("min_rainfall").forGetter(RainfallNeed::getMinRainfall),
            Codec.DOUBLE.fieldOf("max_rainfall").forGetter(RainfallNeed::getMaxRainfall)
    ).apply(instance, RainfallNeed::new));

    private double minRainfall;
    private double maxRainfall;

    public RainfallNeed(String id, double damage, double damageThreshold, double providedHappiness,
                        double satisfactionThreshold, double frequency, boolean hidden,
                        double minRainfall, double maxRainfall) {
        super(id, damage, damageThreshold, providedHappiness, satisfactionThreshold, frequency, hidden);
        this.minRainfall = minRainfall;
        this.maxRainfall = maxRainfall;
    }

    @Override
    public Codec<? extends Need> needType() {
        return CftfcRegistry.RAINFALL_NEED.get();
    }

    @Override
    public NeedSatisfier<? extends Need> createSatisfier() {
        return createSatisfier(this.getSatisfactionThreshold(), false);
    }

    @Override
    public NeedSatisfier<? extends Need> createSatisfier(double satisfaction, boolean isSatisfied) {
        return new RainfallNeedSatisfier(satisfaction, isSatisfied, this);
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
