package com.hyperbaton.cftfc.util;

import net.dries007.tfc.client.overworld.SolarCalculator;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.calendar.Month;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.KoppenClimateClassification;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class TfcClimateHelper {
    private TfcClimateHelper() {
    }

    // SolarCalculator lives in TFC's client package, but TFC itself calls it server-side (crops, plants, fauna).
    public static boolean isNorthernHemisphere(Level level, BlockPos pos) {
        return SolarCalculator.getInNorthernHemisphere(pos, level);
    }

    /**
     * The month as experienced at this position: in the southern hemisphere, seasons are flipped.
     */
    public static Month getLocalMonth(Level level, BlockPos pos) {
        return Calendars.get(level).getHemispheralCalendarMonthOfYear(isNorthernHemisphere(level, pos));
    }

    public static KoppenClimateClassification getClimate(Level level, BlockPos pos) {
        return KoppenClimateClassification.classify(
                Climate.getAverageTemperature(level, pos),
                Climate.getAverageRainfall(level, pos),
                Climate.getRainfallVariance(level, pos),
                isNorthernHemisphere(level, pos));
    }
}
