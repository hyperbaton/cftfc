package com.hyperbaton.cftfc;

import com.hyperbaton.cft.event.CftDatapackRegistryEvents;
import com.hyperbaton.cft.need.Need;
import com.hyperbaton.cftfc.need.RainfallNeed;
import com.hyperbaton.cftfc.need.TemperatureNeed;
import com.mojang.serialization.Codec;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = CftfcMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class CftfcRegistry {

    public static final DeferredRegister<Codec<? extends Need>> NEEDS_CODEC =
            DeferredRegister.create(CftDatapackRegistryEvents.NEED_CODEC_KEY, CftfcMod.MOD_ID);

    public static RegistryObject<Codec<TemperatureNeed>> TEMPERATURE_NEED = NEEDS_CODEC.register("temperature", () -> TemperatureNeed.TEMPERATURE_NEED_CODEC);
    public static RegistryObject<Codec<RainfallNeed>> RAINFALL_NEED = NEEDS_CODEC.register("rainfall", () -> RainfallNeed.RAINFALL_NEED_CODEC);

}

