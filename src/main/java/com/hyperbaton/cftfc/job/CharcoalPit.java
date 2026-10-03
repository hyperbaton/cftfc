package com.hyperbaton.cftfc.job;

import com.hyperbaton.cft.structure.Structure;
import net.dries007.tfc.common.TFCTags;
import net.dries007.tfc.common.blockentities.LogPileBlockEntity;
import net.dries007.tfc.common.blocks.TFCBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The charcoal pit of a structure: holes two blocks deep in its ground. The log pile goes at the bottom
 * of each hole, and its cover on top, level with the ground. A hole only counts if TFC would burn a log
 * pile in it into charcoal: every side and the bottom of the pile must be insulated (by a non-flammable
 * block with a sturdy face, or by another pile), or the fire escapes.
 */
public final class CharcoalPit {
    private CharcoalPit() {
    }

    public enum CellState {
        /** Nothing at the bottom of the hole. */
        EMPTY,
        /** A log pile with fewer logs than it should have. */
        FILLING,
        /** A full log pile, not covered yet. */
        PILED,
        /** A full, covered log pile, ready to be lit. */
        READY,
        BURNING,
        CHARCOAL
    }

    /** A hole of the pit: the log pile goes in {@code pile}, and its cover right above it, in {@code cover}. */
    public record Cell(BlockPos pile, BlockPos cover) {
    }

    public static List<Cell> find(Level level, Structure structure) {
        Set<BlockPos> piles = new HashSet<>();
        for (BlockPos pos : structure.getAllBlockPositions()) {
            BlockPos pile = pos.below();
            BlockState top = level.getBlockState(pos);
            if ((top.isAir() || insulates(level, pos, Direction.DOWN))
                    && isPitContent(level.getBlockState(pile))
                    && insulates(level, pile.below(), Direction.UP)) {
                piles.add(pile);
            }
        }

        // Drop holes with a side open to the outside, until only properly insulated ones are left
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Iterator<BlockPos> it = piles.iterator(); it.hasNext(); ) {
                BlockPos pile = it.next();
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbor = pile.relative(side);
                    if (!piles.contains(neighbor) && !insulates(level, neighbor, side.getOpposite())) {
                        it.remove();
                        changed = true;
                        break;
                    }
                }
            }
        }
        return piles.stream().map(pile -> new Cell(pile, pile.above())).toList();
    }

    public static CellState stateOf(Level level, Cell cell, int logsPerPile) {
        BlockState pile = level.getBlockState(cell.pile());
        if (pile.is(TFCBlocks.BURNING_LOG_PILE.get())) return CellState.BURNING;
        if (pile.is(TFCBlocks.CHARCOAL_PILE.get())) return CellState.CHARCOAL;
        if (level.getBlockEntity(cell.pile()) instanceof LogPileBlockEntity logs) {
            if (logs.logCount() < logsPerPile) return CellState.FILLING;
            return isCovered(level, cell) ? CellState.READY : CellState.PILED;
        }
        return CellState.EMPTY;
    }

    public static int logCount(Level level, Cell cell) {
        return level.getBlockEntity(cell.pile()) instanceof LogPileBlockEntity logs ? logs.logCount() : 0;
    }

    public static boolean isCovered(Level level, Cell cell) {
        return !level.getBlockState(cell.cover()).isAir();
    }

    /** Whether the block keeps the fire of a burning log pile in, on the given face, as TFC checks it. */
    public static boolean insulates(Level level, BlockPos pos, Direction face) {
        return insulates(level.getBlockState(pos), level, pos, face);
    }

    public static boolean insulates(BlockState state, Level level, BlockPos pos, Direction face) {
        return state.is(TFCTags.Blocks.CHARCOAL_PIT_INSULATION)
                || (!state.isFlammable(level, pos, face) && state.isFaceSturdy(level, pos, face));
    }

    private static boolean isPitContent(BlockState state) {
        return state.isAir()
                || state.is(TFCBlocks.LOG_PILE.get())
                || state.is(TFCBlocks.BURNING_LOG_PILE.get())
                || state.is(TFCBlocks.CHARCOAL_PILE.get());
    }
}
