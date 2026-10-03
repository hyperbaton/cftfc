package com.hyperbaton.cftfc.job;

import net.dries007.tfc.common.blockentities.AbstractFirepitBlockEntity;
import net.dries007.tfc.common.blockentities.CharcoalForgeBlockEntity;
import net.dries007.tfc.common.blockentities.InventoryBlockEntity;
import net.dries007.tfc.common.blocks.devices.CharcoalForgeBlock;
import net.dries007.tfc.common.blocks.devices.FirepitBlock;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * A TFC fire a firekeeper can tend: a firepit (and the grill and pot built on one), or a charcoal forge.
 * Both keep their fuel in a range of single-item slots and are lit with {@code light()}, but differ in
 * where fuel goes in: a firepit takes it in its last fuel slot and slides it down towards the burning
 * one a tick later, while a forge takes it in any empty fuel slot.
 */
public final class TendableFire {
    private final InventoryBlockEntity<?> blockEntity;
    private final int firstFuelSlot;
    private final int lastFuelSlot;
    private final boolean insertsInLastSlot;

    private TendableFire(InventoryBlockEntity<?> blockEntity, int firstFuelSlot, int lastFuelSlot, boolean insertsInLastSlot) {
        this.blockEntity = blockEntity;
        this.firstFuelSlot = firstFuelSlot;
        this.lastFuelSlot = lastFuelSlot;
        this.insertsInLastSlot = insertsInLastSlot;
    }

    public static Optional<TendableFire> of(BlockEntity blockEntity) {
        if (blockEntity instanceof AbstractFirepitBlockEntity<?> firepit) {
            return Optional.of(new TendableFire(firepit, AbstractFirepitBlockEntity.SLOT_FUEL_CONSUME,
                    AbstractFirepitBlockEntity.SLOT_FUEL_INPUT, true));
        }
        if (blockEntity instanceof CharcoalForgeBlockEntity forge) {
            return Optional.of(new TendableFire(forge, CharcoalForgeBlockEntity.SLOT_FUEL_MIN,
                    CharcoalForgeBlockEntity.SLOT_FUEL_MAX, false));
        }
        return Optional.empty();
    }

    public int fuelCount() {
        int count = 0;
        for (int slot = firstFuelSlot; slot <= lastFuelSlot; slot++) {
            if (!blockEntity.getInventory().getStackInSlot(slot).isEmpty()) count++;
        }
        return count;
    }

    public int fuelCapacity() {
        return lastFuelSlot - firstFuelSlot + 1;
    }

    /** Whether this item is fuel for this fire, whether or not there's room for it right now. */
    public boolean acceptsFuel(ItemStack stack) {
        return blockEntity.isItemValid(insertsInLastSlot ? lastFuelSlot : firstFuelSlot, stack);
    }

    /** Whether one of this item can go in right now. */
    public boolean canInsert(ItemStack stack) {
        int slot = insertionSlot();
        return slot >= 0 && blockEntity.isItemValid(slot, stack);
    }

    /** Puts one item of the stack in, shrinking the stack. Check {@link #canInsert} first. */
    public void insert(ItemStack stack) {
        int slot = insertionSlot();
        blockEntity.getInventory().setStackInSlot(slot, stack.split(1));
        blockEntity.setAndUpdateSlots(slot);
        blockEntity.setChanged();
    }

    public boolean isLit() {
        BlockState state = blockEntity.getBlockState();
        if (blockEntity instanceof CharcoalForgeBlockEntity) {
            return state.getOptionalValue(CharcoalForgeBlock.HEAT).orElse(0) > 0;
        }
        return state.getOptionalValue(FirepitBlock.LIT).orElse(false);
    }

    /** Lights the fire with its own fuel; fails if it has none. */
    public boolean light() {
        BlockState state = blockEntity.getBlockState();
        if (blockEntity instanceof AbstractFirepitBlockEntity<?> firepit) return firepit.light(state);
        if (blockEntity instanceof CharcoalForgeBlockEntity forge) return forge.light(state);
        return false;
    }

    private int insertionSlot() {
        if (insertsInLastSlot) {
            return blockEntity.getInventory().getStackInSlot(lastFuelSlot).isEmpty() ? lastFuelSlot : -1;
        }
        for (int slot = firstFuelSlot; slot <= lastFuelSlot; slot++) {
            if (blockEntity.getInventory().getStackInSlot(slot).isEmpty()) return slot;
        }
        return -1;
    }
}
