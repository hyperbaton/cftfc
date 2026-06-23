package com.hyperbaton.cftfc.need.satisfaction;

import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.need.satisfaction.NeedSatisfier;
import com.hyperbaton.cftfc.need.HeatSourceNeed;
import net.dries007.tfc.common.blockentities.IHeatable;
import net.dries007.tfc.util.climate.Climate;
import net.minecraft.core.BlockPos;

public class HeatSourceNeedSatisfier extends NeedSatisfier<HeatSourceNeed> {
    public HeatSourceNeedSatisfier(double satisfaction, boolean isSatisfied, HeatSourceNeed need) {
        super(satisfaction, isSatisfied, need);
    }

    @Override
    public boolean satisfy(XoonglinEntity mob) {
        if (isWarmEnough(mob)) {
            super.satisfy(mob);
            return true;
        }

        if (hasValidHeatSource(mob)) {
            super.satisfy(mob);
            return true;
        }

        this.unsatisfy(this.getNeed().getFrequency(), mob);
        mob.decreaseHappiness(this.getNeed().getProvidedHappiness(), this.getNeed().getFrequency());
        return false;
    }

    private boolean isWarmEnough(XoonglinEntity mob) {
        return this.getNeed().getTemperatureThreshold().map(threshold -> {
            float ambientTemp = Climate.getInstantTemperature(mob.level(), mob.getOnPos());
            return ambientTemp >= threshold;
        }).orElse(false);
    }

    private boolean hasValidHeatSource(XoonglinEntity mob) {
        int radius = this.getNeed().getSearchRadius();
        BlockPos center = mob.getOnPos();

        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (mob.level().getBlockEntity(pos) instanceof IHeatable heatable) {
                float sourceTemp = heatable.getTemperature();
                if (sourceTemp >= this.getNeed().getMinSourceTemperature()
                        && sourceTemp <= this.getNeed().getMaxSourceTemperature()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void addMemoriesForSatisfaction(XoonglinEntity mob) {
    }
}
