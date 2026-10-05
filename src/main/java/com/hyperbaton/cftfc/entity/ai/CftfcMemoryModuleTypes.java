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

    /** Set by the charcoal burner job while there's work to do; triggers the burn charcoal behavior. */
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Boolean>> MUST_BURN_CHARCOAL =
            MEMORY_TYPES.register("must_burn_charcoal", () -> new MemoryModuleType<>(Optional.of(Codec.BOOL)));

    /** Set by the miller job while there's work to do; triggers the grind behavior. */
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Boolean>> MUST_GRIND =
            MEMORY_TYPES.register("must_grind", () -> new MemoryModuleType<>(Optional.of(Codec.BOOL)));

    /** Set by the prospector job while there's work to do; triggers the prospect behavior. */
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Boolean>> MUST_PROSPECT =
            MEMORY_TYPES.register("must_prospect", () -> new MemoryModuleType<>(Optional.of(Codec.BOOL)));

    /** Set by the preserver job while there's work to do; triggers the preserve behavior. */
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Boolean>> MUST_PRESERVE =
            MEMORY_TYPES.register("must_preserve", () -> new MemoryModuleType<>(Optional.of(Codec.BOOL)));

    /** Set by the farmer job while there's work to do; triggers the tend fields behavior. */
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Boolean>> MUST_FARM =
            MEMORY_TYPES.register("must_farm", () -> new MemoryModuleType<>(Optional.of(Codec.BOOL)));

    /** Set by the composter job while there's work to do; triggers the compost behavior. */
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Boolean>> MUST_COMPOST =
            MEMORY_TYPES.register("must_compost", () -> new MemoryModuleType<>(Optional.of(Codec.BOOL)));

    /** Set by the barrel keeper job while there's work to do; triggers the keep barrels behavior. */
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Boolean>> MUST_KEEP_BARRELS =
            MEMORY_TYPES.register("must_keep_barrels", () -> new MemoryModuleType<>(Optional.of(Codec.BOOL)));
}
