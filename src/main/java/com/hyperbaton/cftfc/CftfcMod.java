package com.hyperbaton.cftfc;

import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(CftfcMod.MOD_ID)
public class CftfcMod {
    public static final String MOD_ID = "cftfc";

    public CftfcMod(IEventBus modEventBus, ModContainer modContainer) {
        CftfcRegistry.NEEDS_CODEC.register(modEventBus);
        CftfcRegistry.NEED_CONDITIONS_CODEC.register(modEventBus);
        CftfcRegistry.JOBS_CODEC.register(modEventBus);
        CftfcRegistry.INGREDIENT_TYPES.register(modEventBus);
        CftfcMemoryModuleTypes.MEMORY_TYPES.register(modEventBus);
    }
}
