package com.hyperbaton.cftfc.entity.ai;

import com.hyperbaton.cftfc.CftfcMod;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Optional;

public class CftfcMemoryModuleTypes {
    public static final DeferredRegister<MemoryModuleType<?>> MEMORY_TYPES =
            DeferredRegister.create(Registries.MEMORY_MODULE_TYPE, CftfcMod.MOD_ID);

    /** Set by the firekeeper job while there's work to do; triggers the keep fires behavior. */
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Boolean>> MUST_KEEP_FIRES =
            MEMORY_TYPES.register("must_keep_fires", () -> new MemoryModuleType<>(Optional.of(Codec.BOOL)));
}
