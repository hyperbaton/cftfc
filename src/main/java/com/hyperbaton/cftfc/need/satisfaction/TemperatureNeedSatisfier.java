package com.hyperbaton.cftfc.need.satisfaction;

import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.need.satisfaction.NeedSatisfier;
import com.hyperbaton.cftfc.need.TemperatureNeed;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.calendar.Season;
import net.dries007.tfc.util.climate.Climate;

public class TemperatureNeedSatisfier extends NeedSatisfier<TemperatureNeed> {
    public TemperatureNeedSatisfier(double satisfaction, boolean isSatisfied, TemperatureNeed need) {
        super(satisfaction, isSatisfied, need);
    }

    @Override
    public boolean satisfy(XoonglinEntity mob) {

        float averageTemperature = Climate.getAverageTemperature(mob.level(), mob.getOnPos());

        float currentTemperature = Climate.getTemperature(mob.level(), mob.getOnPos());

        Season currentSeason = Calendars.get(mob.level()).getCalendarMonthOfYear().getSeason();

        if (adequateAverageTemperature(averageTemperature) && adequateCurrentTemperature(currentTemperature, currentSeason)) {
            super.satisfy(mob);
        } else {
            this.unsatisfy(this.getNeed().getFrequency(), mob);
            mob.decreaseHappiness(this.getNeed().getProvidedHappiness(), this.getNeed().getFrequency());
            return false;
        }

        return true;
    }

    private boolean adequateCurrentTemperature(float currentTemperature, Season currentSeason) {
        return this.getNeed().getSeasonalLimits().map(seasonalLimits -> switch (currentSeason) {
            case SPRING -> currentTemperature >= seasonalLimits.minSpringTemperature() &&
                    currentTemperature <= seasonalLimits.maxSpringTemperature();
            case SUMMER -> currentTemperature >= seasonalLimits.minSummerTemperature() &&
                    currentTemperature <= seasonalLimits.maxSummerTemperature();
            case FALL -> currentTemperature >= seasonalLimits.minFallTemperature() &&
                    currentTemperature <= seasonalLimits.maxFallTemperature();
            case WINTER -> currentTemperature >= seasonalLimits.minWinterTemperature() &&
                    currentTemperature <= seasonalLimits.maxWinterTemperature();
        }).orElse(true);
    }

    private boolean adequateAverageTemperature(float averageTemperature) {
        return averageTemperature >= this.getNeed().getMinAverageTemperature() && averageTemperature <= this.getNeed().getMaxAverageTemperature();
    }

    @Override
    public void addMemoriesForSatisfaction(XoonglinEntity mob) {

    }
}
