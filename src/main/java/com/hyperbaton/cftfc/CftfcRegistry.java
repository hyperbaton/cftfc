package com.hyperbaton.cftfc;

import com.hyperbaton.cft.event.CftDatapackRegistryEvents;
import com.hyperbaton.cft.need.Need;
import com.hyperbaton.cft.need.condition.NeedCondition;
import com.hyperbaton.cftfc.need.HeatSourceNeed;
import com.hyperbaton.cftfc.need.RainfallNeed;
import com.hyperbaton.cftfc.need.TemperatureNeed;
import com.hyperbaton.cftfc.need.condition.ClimateCondition;
import com.hyperbaton.cftfc.need.condition.MonthCondition;
import com.hyperbaton.cftfc.need.condition.RockCondition;
import com.hyperbaton.cftfc.need.condition.SeasonCondition;
import com.hyperbaton.cftfc.need.condition.TemperatureCondition;
import com.mojang.serialization.Codec;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CftfcRegistry {

    public static final DeferredRegister<Codec<? extends Need>> NEEDS_CODEC =
            DeferredRegister.create(CftDatapackRegistryEvents.NEED_CODEC_KEY, CftfcMod.MOD_ID);

    public static DeferredHolder<Codec<? extends Need>, Codec<TemperatureNeed>> TEMPERATURE_NEED =
            NEEDS_CODEC.register("temperature", () -> TemperatureNeed.TEMPERATURE_NEED_CODEC);

    public static DeferredHolder<Codec<? extends Need>, Codec<RainfallNeed>> RAINFALL_NEED =
            NEEDS_CODEC.register("rainfall", () -> RainfallNeed.RAINFALL_NEED_CODEC);

    public static DeferredHolder<Codec<? extends Need>, Codec<HeatSourceNeed>> HEAT_SOURCE_NEED =
            NEEDS_CODEC.register("heat_source", () -> HeatSourceNeed.HEAT_SOURCE_NEED_CODEC);

    public static final DeferredRegister<Codec<? extends NeedCondition>> NEED_CONDITIONS_CODEC =
            DeferredRegister.create(CftDatapackRegistryEvents.NEED_CONDITION_CODEC_KEY, CftfcMod.MOD_ID);

    public static DeferredHolder<Codec<? extends NeedCondition>, Codec<SeasonCondition>> SEASON_CONDITION =
            NEED_CONDITIONS_CODEC.register("season", () -> SeasonCondition.CODEC);

    public static DeferredHolder<Codec<? extends NeedCondition>, Codec<MonthCondition>> MONTH_CONDITION =
            NEED_CONDITIONS_CODEC.register("month", () -> MonthCondition.CODEC);

    public static DeferredHolder<Codec<? extends NeedCondition>, Codec<TemperatureCondition>> TEMPERATURE_CONDITION =
            NEED_CONDITIONS_CODEC.register("temperature", () -> TemperatureCondition.CODEC);

    public static DeferredHolder<Codec<? extends NeedCondition>, Codec<ClimateCondition>> CLIMATE_CONDITION =
            NEED_CONDITIONS_CODEC.register("climate", () -> ClimateCondition.CODEC);

    public static DeferredHolder<Codec<? extends NeedCondition>, Codec<RockCondition>> ROCK_CONDITION =
            NEED_CONDITIONS_CODEC.register("rock", () -> RockCondition.CODEC);
}
