package com.hyperbaton.cftfc.need.condition;

import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.need.condition.NeedCondition;
import com.hyperbaton.cft.util.CodecUtil;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.util.TfcClimateHelper;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.util.calendar.Month;

import java.util.List;

public record MonthCondition(List<Month> months) implements NeedCondition {
    public static final Codec<MonthCondition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CodecUtil.singleOrList(Month.CODEC).fieldOf("months").forGetter(MonthCondition::months)
    ).apply(instance, MonthCondition::new));

    @Override
    public boolean test(XoonglinEntity mob) {
        return months.contains(TfcClimateHelper.getLocalMonth(mob.level(), mob.getOnPos()));
    }

    @Override
    public Codec<? extends NeedCondition> conditionType() {
        return CftfcRegistry.MONTH_CONDITION.get();
    }
}
