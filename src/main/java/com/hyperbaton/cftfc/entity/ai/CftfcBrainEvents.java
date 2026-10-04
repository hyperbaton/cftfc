package com.hyperbaton.cftfc.entity.ai;

import com.hyperbaton.cft.api.event.XoonglinBrainEvent;
import com.hyperbaton.cftfc.CftfcMod;
import com.hyperbaton.cftfc.entity.ai.behavior.BurnCharcoalBehavior;
import com.hyperbaton.cftfc.entity.ai.behavior.GrindBehavior;
import com.hyperbaton.cftfc.entity.ai.behavior.KeepFiresBehavior;
import com.hyperbaton.cftfc.entity.ai.behavior.PreserveBehavior;
import com.hyperbaton.cftfc.entity.ai.behavior.ProspectBehavior;
import com.hyperbaton.cftfc.entity.ai.behavior.TendFieldsBehavior;
import net.minecraft.world.entity.schedule.Activity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Adds CFTFC's jobs to the Xoonglin brain: the memory each job sets while there's work to do, and the
 * behavior in the WORK activity that does that work.
 */
@EventBusSubscriber(modid = CftfcMod.MOD_ID)
public class CftfcBrainEvents {

    @SubscribeEvent
    public static void registerMemories(XoonglinBrainEvent.RegisterMemories event) {
        event.addMemory(CftfcMemoryModuleTypes.MUST_KEEP_FIRES.get());
        event.addMemory(CftfcMemoryModuleTypes.MUST_BURN_CHARCOAL.get());
        event.addMemory(CftfcMemoryModuleTypes.MUST_GRIND.get());
        event.addMemory(CftfcMemoryModuleTypes.MUST_PROSPECT.get());
        event.addMemory(CftfcMemoryModuleTypes.MUST_PRESERVE.get());
        event.addMemory(CftfcMemoryModuleTypes.MUST_FARM.get());
    }

    @SubscribeEvent
    public static void addBehaviors(XoonglinBrainEvent.AddBehaviors event) {
        // Same priority as CFT's own job behaviors
        event.addBehavior(Activity.WORK, 2, new KeepFiresBehavior());
        event.addBehavior(Activity.WORK, 2, new BurnCharcoalBehavior());
        event.addBehavior(Activity.WORK, 2, new GrindBehavior());
        event.addBehavior(Activity.WORK, 2, new ProspectBehavior());
        event.addBehavior(Activity.WORK, 2, new PreserveBehavior());
        event.addBehavior(Activity.WORK, 2, new TendFieldsBehavior());
    }
}
