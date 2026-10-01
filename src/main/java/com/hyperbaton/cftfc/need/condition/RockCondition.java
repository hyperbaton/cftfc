package com.hyperbaton.cftfc.need.condition;

import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.need.condition.NeedCondition;
import com.hyperbaton.cft.util.CodecUtil;
import com.hyperbaton.cft.util.RegistryEntries;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.common.blocks.rock.Rock;
import net.dries007.tfc.common.blocks.rock.RockCategory;
import net.dries007.tfc.world.chunkdata.ChunkData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Optional;

/**
 * Matches the TFC rock layer under the Xoonglin, either by its raw rock block (or block tag), or by rock category.
 */
public record RockCondition(RegistryEntries<Block> rocks, List<RockCategory> categories) implements NeedCondition {
    public static final Codec<RockCondition> CODEC = RecordCodecBuilder.<RockCondition>create(instance -> instance.group(
            RegistryEntries.codec(Registries.BLOCK).optionalFieldOf("rocks", RegistryEntries.empty()).forGetter(RockCondition::rocks),
            CodecUtil.singleOrList(StringRepresentable.fromEnum(RockCategory::values)).optionalFieldOf("categories", List.of()).forGetter(RockCondition::categories)
    ).apply(instance, RockCondition::new)).validate(condition -> condition.rocks.isEmpty() && condition.categories.isEmpty()
            ? DataResult.error(() -> "Rock condition needs at least one of 'rocks' or 'categories'")
            : DataResult.success(condition));

    @Override
    public boolean test(XoonglinEntity mob) {
        BlockPos pos = mob.getOnPos();
        Block raw = ChunkData.get(mob.level(), pos).getRockData().getRock(pos).raw();
        if (!rocks.isEmpty() && rocks.contains(raw.builtInRegistryHolder())) {
            return true;
        }
        return !categories.isEmpty() && categoryOf(raw).map(categories::contains).orElse(false);
    }

    @Override
    public Codec<? extends NeedCondition> conditionType() {
        return CftfcRegistry.ROCK_CONDITION.get();
    }

    private static Optional<RockCategory> categoryOf(Block raw) {
        for (Rock rock : Rock.VALUES) {
            if (rock.getBlock(Rock.BlockType.RAW).get() == raw) {
                return Optional.of(rock.category());
            }
        }
        return Optional.empty();
    }
}
