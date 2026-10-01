package com.hyperbaton.cftfc.need.condition;

import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.need.condition.NeedCondition;
import com.hyperbaton.cft.util.CodecUtil;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.util.TfcClimateHelper;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.util.calendar.Season;
import net.minecraft.util.StringRepresentable;

import java.util.List;

public record SeasonCondition(List<Season> seasons) implements NeedCondition {
    public static final Codec<SeasonCondition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CodecUtil.singleOrList(StringRepresentable.fromEnum(Season::values)).fieldOf("seasons").forGetter(SeasonCondition::seasons)
    ).apply(instance, SeasonCondition::new));

    @Override
    public boolean test(XoonglinEntity mob) {
        return seasons.contains(TfcClimateHelper.getLocalMonth(mob.level(), mob.getOnPos()).getSeason());
    }

    @Override
    public Codec<? extends NeedCondition> conditionType() {
        return CftfcRegistry.SEASON_CONDITION.get();
    }
}
