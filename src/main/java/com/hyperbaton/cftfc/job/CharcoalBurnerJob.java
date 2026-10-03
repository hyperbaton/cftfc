package com.hyperbaton.cftfc.job;

import com.hyperbaton.cft.job.Job;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.common.TFCTags;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

import static com.hyperbaton.cft.need.codec.CftCodec.INGREDIENT_CODEC;

/**
 * The Xoonglin makes charcoal in the pit of its charcoal yard: it fills the pit's holes with log piles,
 * covers them, lights the pit, and once TFC has turned the logs into charcoal, digs it out into the
 * yard's chests and starts over.
 */
public class CharcoalBurnerJob extends WorkplaceJob {

    private static final List<Ingredient> DEFAULT_COVER = List.of(
            Ingredient.of(TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("tfc", "dirt"))));

    public static final Codec<CharcoalBurnerJob> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            propertiesCodec(),
            Codec.DOUBLE.fieldOf("hours_per_day").forGetter(CharcoalBurnerJob::getHoursPerDay),
            ResourceLocation.CODEC.fieldOf("required_structure").forGetter(CharcoalBurnerJob::getRequiredStructureType),
            Codec.intRange(1, 16).optionalFieldOf("logs_per_pile", 16).forGetter(CharcoalBurnerJob::getLogsPerPile),
            INGREDIENT_CODEC.listOf().optionalFieldOf("logs", List.of()).forGetter(j -> j.logs),
            INGREDIENT_CODEC.listOf().optionalFieldOf("cover", DEFAULT_COVER).forGetter(j -> j.cover),
            Codec.intRange(1, 256).optionalFieldOf("carry", 64).forGetter(CharcoalBurnerJob::getCarry)
    ).apply(inst, CharcoalBurnerJob::new));

    private final int logsPerPile;
    private final List<Ingredient> logs;
    private final List<Ingredient> cover;
    private final int carry;

    public CharcoalBurnerJob(Properties properties, double hoursPerDay, ResourceLocation requiredStructure,
                             int logsPerPile, List<Ingredient> logs, List<Ingredient> cover, int carry) {
        super(properties, hoursPerDay, requiredStructure);
        this.logsPerPile = logsPerPile;
        this.logs = List.copyOf(logs);
        this.cover = List.copyOf(cover);
        this.carry = carry;
    }

    /** How many logs it puts in each log pile before covering it; a log pile holds up to 16. */
    public int getLogsPerPile() {
        return logsPerPile;
    }

    /** How many logs it takes from the yard's chests at once. */
    public int getCarry() {
        return carry;
    }

    /** Logs it may stack in a log pile. */
    public boolean acceptsLog(ItemStack stack) {
        return stack.is(TFCTags.Items.LOG_PILE_LOGS)
                && (logs.isEmpty() || logs.stream().anyMatch(ingredient -> ingredient.test(stack)));
    }

    /** Blocks it may cover the log piles with; whether one keeps the fire in is checked when placing it. */
    public boolean acceptsCover(ItemStack stack) {
        return stack.getItem() instanceof BlockItem && cover.stream().anyMatch(ingredient -> ingredient.test(stack));
    }

    @Override
    public MemoryModuleType<Boolean> getWorkMemory() {
        return CftfcMemoryModuleTypes.MUST_BURN_CHARCOAL.get();
    }

    @Override
    public Codec<? extends Job> jobType() {
        return CftfcRegistry.CHARCOAL_BURNER_JOB.get();
    }
}
