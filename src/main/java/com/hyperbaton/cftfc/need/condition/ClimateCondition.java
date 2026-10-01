package com.hyperbaton.cftfc.need.condition;

import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.need.condition.NeedCondition;
import com.hyperbaton.cft.util.CodecUtil;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.util.TfcClimateHelper;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.util.climate.KoppenClimateClassification;
import net.minecraft.util.StringRepresentable;

import java.util.List;

/**
 * Matches the Köppen climate classification of the Xoonglin's location, as shown on TFC's F3 screen.
 */
public record ClimateCondition(List<KoppenClimateClassification> climates) implements NeedCondition {
    public static final Codec<ClimateCondition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CodecUtil.singleOrList(StringRepresentable.fromEnum(KoppenClimateClassification::values)).fieldOf("climates").forGetter(ClimateCondition::climates)
    ).apply(instance, ClimateCondition::new));

    @Override
    public boolean test(XoonglinEntity mob) {
        return climates.contains(TfcClimateHelper.getClimate(mob.level(), mob.getOnPos()));
    }

    @Override
    public Codec<? extends NeedCondition> conditionType() {
        return CftfcRegistry.CLIMATE_CONDITION.get();
    }
}
