package com.hyperbaton.cftfc.need.condition;

import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.need.condition.NeedCondition;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.util.climate.Climate;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;
import java.util.Optional;

public record TemperatureCondition(Optional<Double> min, Optional<Double> max, Mode mode) implements NeedCondition {
    public static final Codec<TemperatureCondition> CODEC = RecordCodecBuilder.<TemperatureCondition>create(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("min").forGetter(TemperatureCondition::min),
            Codec.DOUBLE.optionalFieldOf("max").forGetter(TemperatureCondition::max),
            Mode.CODEC.optionalFieldOf("mode", Mode.INSTANT).forGetter(TemperatureCondition::mode)
    ).apply(instance, TemperatureCondition::new)).validate(condition -> condition.min.isEmpty() && condition.max.isEmpty()
            ? DataResult.error(() -> "Temperature condition needs at least one of 'min' or 'max'")
            : DataResult.success(condition));

    @Override
    public boolean test(XoonglinEntity mob) {
        float temperature = switch (mode) {
            case INSTANT -> Climate.getInstantTemperature(mob.level(), mob.getOnPos());
            case AVERAGE -> Climate.getAverageTemperature(mob.level(), mob.getOnPos());
        };
        return min.map(m -> temperature >= m).orElse(true) && max.map(m -> temperature <= m).orElse(true);
    }

    @Override
    public Codec<? extends NeedCondition> conditionType() {
        return CftfcRegistry.TEMPERATURE_CONDITION.get();
    }

    public enum Mode implements StringRepresentable {
        INSTANT,
        AVERAGE;

        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
