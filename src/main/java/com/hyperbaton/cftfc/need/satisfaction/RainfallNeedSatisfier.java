package com.hyperbaton.cftfc.need.satisfaction;

import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.need.satisfaction.NeedSatisfier;
import com.hyperbaton.cftfc.need.RainfallNeed;
import net.dries007.tfc.util.climate.Climate;

public class RainfallNeedSatisfier extends NeedSatisfier<RainfallNeed> {
    public RainfallNeedSatisfier(double satisfaction, boolean isSatisfied, RainfallNeed need) {
        super(satisfaction, isSatisfied, need);
    }

    @Override
    public boolean satisfy(XoonglinEntity mob) {

        float rainfall = Climate.getRainfall(mob.level(), mob.getOnPos());

        if (rainfall >= this.getNeed().getMinRainfall() && rainfall <= this.getNeed().getMaxRainfall()) {
            super.satisfy(mob);
        } else {
            this.unsatisfy(this.getNeed().getFrequency(), mob);
            mob.decreaseHappiness(this.getNeed().getProvidedHappiness(), this.getNeed().getFrequency());
            return false;
        }
        return true;
    }

    @Override
    public void addMemoriesForSatisfaction(XoonglinEntity mob) {

    }
}
