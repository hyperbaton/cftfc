package com.hyperbaton.cftfc.job;

import com.hyperbaton.cft.job.Job;
import com.hyperbaton.cftfc.CftfcRegistry;
import com.hyperbaton.cftfc.entity.ai.CftfcMemoryModuleTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.common.component.food.FoodCapability;
import net.dries007.tfc.common.component.food.FoodTraits;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;

import java.util.List;
import java.util.Optional;

import static com.hyperbaton.cft.need.codec.CftCodec.INGREDIENT_CODEC;

/**
 * The Xoonglin preserves food in its pantry, as TFC does: it salts meat and fish with salt, brines food in
 * barrels of brine, and pickles brined food in barrels of vinegar. All three traits stay on the food after it
 * leaves the barrel. It takes the food and salt from the pantry's chests and stores the result there; the
 * barrels must be kept filled with brine or vinegar.
 */
public class PreserverJob extends WorkplaceJob {

    /** How much of the barrel's brine or vinegar each item takes, in mB, as in TFC's barrel recipes. */
    public static final int FLUID_PER_ITEM = 125;

    private static final TagKey<Item> CAN_BE_SALTED = itemTag("tfc", "foods/can_be_salted");
    private static final List<TagKey<Item>> PRESERVABLE = List.of(
            itemTag("c", "foods/fruit"), itemTag("c", "foods/vegetable"),
            itemTag("c", "foods/meat"), itemTag("c", "foods/fish"));
    private static final ResourceLocation SALT = ResourceLocation.fromNamespaceAndPath("tfc", "powder/salt");
    private static final ResourceLocation BRINE = ResourceLocation.fromNamespaceAndPath("tfc", "brine");
    private static final ResourceLocation VINEGAR = ResourceLocation.fromNamespaceAndPath("tfc", "vinegar");

    public static final Codec<PreserverJob> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            propertiesCodec(),
            Codec.DOUBLE.fieldOf("hours_per_day").forGetter(PreserverJob::getHoursPerDay),
            ResourceLocation.CODEC.fieldOf("required_structure").forGetter(PreserverJob::getRequiredStructureType),
            INGREDIENT_CODEC.listOf().optionalFieldOf("foods", List.of()).forGetter(j -> j.foods),
            Codec.BOOL.optionalFieldOf("salt", true).forGetter(j -> j.salt),
            Codec.BOOL.optionalFieldOf("brine", true).forGetter(j -> j.brine),
            Codec.BOOL.optionalFieldOf("pickle", true).forGetter(j -> j.pickle),
            Codec.intRange(1, 64).optionalFieldOf("load", 16).forGetter(PreserverJob::getLoad)
    ).apply(inst, PreserverJob::new));

    /** What a barrel's liquid does to food sealed in it. */
    public enum Process {
        BRINING,
        PICKLING
    }

    private final List<Ingredient> foods;
    private final boolean salt;
    private final boolean brine;
    private final boolean pickle;
    private final int load;

    public PreserverJob(Properties properties, double hoursPerDay, ResourceLocation requiredStructure,
                        List<Ingredient> foods, boolean salt, boolean brine, boolean pickle, int load) {
        super(properties, hoursPerDay, requiredStructure);
        this.foods = List.copyOf(foods);
        this.salt = salt;
        this.brine = brine;
        this.pickle = pickle;
        this.load = load;
    }

    /** The most items it seals in a barrel at once. */
    public int getLoad() {
        return load;
    }

    public static boolean isSalt(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(SALT);
    }

    /** Whether it would salt this food, as TFC's salting recipe does. */
    public boolean salts(ItemStack stack) {
        return salt && stack.is(CAN_BE_SALTED) && isFresh(stack)
                && !FoodCapability.hasTrait(stack, FoodTraits.SALTED);
    }

    /** What sealing food in a barrel of this liquid does, if it's a liquid it works with. */
    public Optional<Process> processFor(Fluid fluid) {
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
        if (brine && BRINE.equals(id)) return Optional.of(Process.BRINING);
        if (pickle && VINEGAR.equals(id)) return Optional.of(Process.PICKLING);
        return Optional.empty();
    }

    /** Whether the process would do anything to this food, as TFC's barrel recipes check it. */
    public boolean processes(Process process, ItemStack stack) {
        if (!isFresh(stack) || PRESERVABLE.stream().noneMatch(stack::is)) return false;
        boolean brined = FoodCapability.hasTrait(stack, FoodTraits.BRINED);
        return switch (process) {
            case BRINING -> !brined;
            case PICKLING -> brined && !FoodCapability.hasTrait(stack, FoodTraits.PICKLED);
        };
    }

    private boolean isFresh(ItemStack stack) {
        return FoodCapability.has(stack) && !FoodCapability.isRotten(stack)
                && (foods.isEmpty() || foods.stream().anyMatch(ingredient -> ingredient.test(stack)));
    }

    private static TagKey<Item> itemTag(String namespace, String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    @Override
    public MemoryModuleType<Boolean> getWorkMemory() {
        return CftfcMemoryModuleTypes.MUST_PRESERVE.get();
    }

    @Override
    public Codec<? extends Job> jobType() {
        return CftfcRegistry.PRESERVER_JOB.get();
    }
}
