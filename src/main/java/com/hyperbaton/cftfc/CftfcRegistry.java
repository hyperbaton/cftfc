package com.hyperbaton.cftfc;

import com.hyperbaton.cft.event.CftDatapackRegistryEvents;
import com.hyperbaton.cft.need.Need;
import com.hyperbaton.cftfc.need.HeatSourceNeed;
import com.hyperbaton.cftfc.need.RainfallNeed;
import com.hyperbaton.cftfc.need.TemperatureNeed;
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
}
