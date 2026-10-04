package com.hyperbaton.cftfc.need;

import com.hyperbaton.cft.need.Need;
import com.hyperbaton.cft.need.satisfaction.NeedSatisfier;
import com.hyperbaton.cft.util.CodecUtil;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.ingredient.NutrientIngredient;
import com.hyperbaton.cftfc.need.satisfaction.NutritionNeedSatisfier;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.common.component.food.Nutrient;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * A balanced diet: the Xoonglin remembers its last meals, and is satisfied while they provide enough of each
 * nutrient it needs. It eats the food it carries that best balances its diet, and fetches food rich in the
 * nutrients it lacks from its home.
 */
public class NutritionNeed extends Need {

    public static final Codec<NutritionNeed> NUTRITION_NEED_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            propertiesCodec(),
            CodecUtil.singleOrList(NutrientIngredient.NUTRIENT_CODEC).fieldOf("nutrients").forGetter(NutritionNeed::getNutrients),
            Codec.intRange(1, 16).optionalFieldOf("meals", 5).forGetter(NutritionNeed::getMeals),
            Codec.floatRange(0f, Float.MAX_VALUE).optionalFieldOf("min_amount", NutrientIngredient.DEFAULT_MIN)
                    .forGetter(NutritionNeed::getMinAmount)
    ).apply(instance, NutritionNeed::new));

    private final List<Nutrient> nutrients;
    private final int meals;
    private final float minAmount;

    public NutritionNeed(Properties properties, List<Nutrient> nutrients, int meals, float minAmount) {
        super(properties);
        this.nutrients = List.copyOf(nutrients);
        this.meals = meals;
        this.minAmount = minAmount;
    }

    /** The nutrients its diet has to provide. */
    public List<Nutrient> getNutrients() {
        return nutrients;
    }

    /** How many of its last meals it remembers. */
    public int getMeals() {
        return meals;
    }

    /** How much of each nutrient its remembered meals have to provide together. */
    public float getMinAmount() {
        return minAmount;
    }

    @Override
    public Codec<? extends Need> needType() {
        return CftfcRegistry.NUTRITION_NEED.get();
    }

    @Override
    public NeedSatisfier<? extends Need> createSatisfier(double satisfaction, boolean isSatisfied) {
        return new NutritionNeedSatisfier(satisfaction, isSatisfied, this);
    }

    @Override
    public String getTypeName() {
        return Component.translatable("gui.cftfc.need_type.nutrition").getString();
    }

    @Override
    public List<ResourceLocation> getDefaultIcons() {
        return nutrients.stream().map(NutritionNeed::iconFor).toList();
    }

    private static ResourceLocation iconFor(Nutrient nutrient) {
        String food = switch (nutrient) {
            case GRAIN -> "wheat_bread";
            case FRUIT -> "red_apple";
            case VEGETABLES -> "carrot";
            case PROTEIN -> "cooked_beef";
            case DAIRY -> "cheese";
        };
        return ResourceLocation.fromNamespaceAndPath("tfc", "food/" + food);
    }
}
