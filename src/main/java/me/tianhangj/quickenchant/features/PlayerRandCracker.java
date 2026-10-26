package me.tianhangj.quickenchant.features;

import me.tianhangj.quickenchant.Configs;
import me.tianhangj.quickenchant.util.CUtil;
import me.tianhangj.quickenchant.util.CComponentUtil;
import me.tianhangj.quickenchant.util.Compat;
import me.tianhangj.quickenchant.util.MultiVersionCompat;
import me.tianhangj.quickenchant.util.QEDebug;
import me.tianhangj.quickenchant.command.ClientCommandHelper;
import me.tianhangj.quickenchant.event.ClientLevelEvents;
import me.tianhangj.quickenchant.interfaces.ICreativeSlot;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class PlayerRandCracker {

    // ===== RNG IMPLEMENTATION ===== //

    public static final long MULTIPLIER = 0x5deece66dL;
    public static final long ADDEND = 0xbL;
    public static final long MASK = (1L << 48) - 1;

    private static long seed;

    private static int next(int bits) {
        seed = (seed * MULTIPLIER + ADDEND) & MASK;
        return (int) (seed >>> (48 - bits));
    }

    public static int nextInt() {
        return next(32);
    }

    public static int nextInt(int bound) {
        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) next(31)) >> 31);
        }

        int bits, val;
        do {
            bits = next(31);
            val = bits % bound;
        } while (bits - val + (bound-1) < 0);

        return val;
    }

    public static float nextFloat() {
        return next(24) / (float) (1 << 24);
    }

    public static void setSeed(long seed) {
        PlayerRandCracker.seed = seed;
    }

    public static long getSeed() {
        return seed;
    }


    // ===== RESET DETECTION + PLAYER RNG MAINTENANCE ===== //

    public static final Event<RNGCallListener> RNG_CALLED_EVENT = EventFactory.createArrayBacked(RNGCallListener.class, listeners -> event -> {
        for (RNGCallListener listener : listeners) {
            listener.onCall(event);
        }
    });

    public static boolean isPredictingBlockBreaking = false;
    @Nullable
    private static Runnable postBlockBreakPredictAction = null;

    public static void registerEvents() {
        ClientLevelEvents.LOAD_LEVEL.register(level -> {
            resetCracker(RNGCallType.RECREATED);
        });
        RNG_CALLED_EVENT.register(PlayerRandCracker::throwItemsUntilOnRNGCallEvent);
    }

    public static void postSendBlockBreakingPredictionPacket() {
        if (postBlockBreakPredictAction != null) {
            postBlockBreakPredictAction.run();
            postBlockBreakPredictAction = null;
        }
    }

    // TODO: update-sensitive: call hierarchy of Player.random and Player.getRandom()

    public static void resetCracker(RNGCallType reason) {
        resetCracker(reason, true);
    }

    // isResettingUnconditionally should be true iff canMaintainPlayerRNG wasn't previously called
    private static void resetCracker(RNGCallType reason, boolean isResettingUnconditionally) {
        if (isResettingUnconditionally) {
            RNG_CALLED_EVENT.invoker().onCall(new RNGCallEvent(reason, false));
        }
        if (Configs.playerCrackState != PlayerRandCracker.CrackState.UNCRACKED) {
            ClientCommandHelper.sendError(Component.translatable("playerManip.reset", reason.resetMessage));
            Configs.playerCrackState = PlayerRandCracker.CrackState.UNCRACKED;
        }
    }

    /**
     * Forgets the cracked player seed outright, silently and unconditionally.
     *
     * <p>Used when a run proves the seed model wrong: the player seed and the table seed are a
     * matched pair, so discarding only one - or keeping both when they are simply wrong - would
     * make the next run misposition the RNG in exactly the same way. Unlike
     * {@link #resetCracker(RNGCallType)} this prints nothing and does not fire the RNG-call event
     * (the caller reports the reason itself, and the event would trip the abort handling again).
     */
    public static void forgetSeed() {
        Configs.playerCrackState = CrackState.UNCRACKED;
        seed = 0L;
    }

    public static void onDropItem() {
        if (canMaintainPlayerRNG(RNGCallType.DROP_ITEM)) {
            long before = seed;
            for (int i = 0; i < 4; i++) {
                nextInt();
            }
            QEDebug.log("playerRNG: DROP_ITEM seed 0x%s -> 0x%s (4 steps)",
                    QEDebug.hex(before), QEDebug.hex(seed));
        } else {
            QEDebug.log("playerRNG: DROP_ITEM could not be maintained; player crack reset");
            resetCracker(RNGCallType.DROP_ITEM, false);
        }
    }

    public static void onConsume(ItemStack stack, Vec3 pos, int particleCount, int itemUseTimeLeft, Consumable consumable) {
        RNGCallType callType = switch (consumable.animation()) {
            case EAT -> RNGCallType.FOOD;
            case DRINK -> RNGCallType.DRINK;
            default -> RNGCallType.CONSUME;
        };

        if (canMaintainPlayerRNG(callType)) {
            if (itemUseTimeLeft < 0 && particleCount != 16) {
                // We have accounted for all eating ticks, that on the server should be calculated
                // Sometimes if the connection is laggy we eat more than 24 ticks so just hope for the best
                return;
            }

            // random calls for the consume sounds
            for (int i = 0; i < 3; i++) {
                nextInt();
            }
            if (consumable.hasConsumeParticles()) {
                // random calls for the particles
                for (int i = 0; i < particleCount * 3; i++) {
                    nextInt();
                }
            }
        } else {
            resetCracker(callType, false);
        }
    }

    public static void onEquipItem() {
        if (canMaintainPlayerRNG(RNGCallType.EQUIP_ITEM)) {
            nextInt();
            nextInt();
        } else {
            resetCracker(RNGCallType.EQUIP_ITEM, false);
        }
    }

    public static void onAnvilUse() {
        if (canMaintainPlayerRNG(RNGCallType.ANVIL)) {
            nextInt();
        } else {
            resetCracker(RNGCallType.ANVIL, false);
        }
    }

    public static void onCrossbowUse() {
        if (canMaintainPlayerRNG(RNGCallType.CROSSBOW)) {
            nextInt();
        } else {
            resetCracker(RNGCallType.CROSSBOW, false);
        }
    }

    public static void onXpOrb() {
        // TODO: is there a way to be smarter about this?
        resetCracker(RNGCallType.XP);
    }

    // no-op on 26.1.x: bane of arthropods no longer calls the player RNG (pre-1.21 only)
    public static void onBaneOfArthropods() {
    }

    // no-op on 26.1.x: item damage (incl. unbreaking) uses the server level RNG, not the player RNG
    public static void onItemDamage(int amount, LivingEntity holder, ItemStack stack) {
    }

    public static void onItemDamageUncertain(int minAmount, int maxAmount, LivingEntity holder, ItemStack stack) {
    }

    public static void onEnchantedItem() {
        if (canMaintainPlayerRNG(RNGCallType.ENCHANTING)) {
            long before = seed;
            int value = nextInt();
            QEDebug.log("playerRNG: ENCHANTING nextInt=0x%s seed 0x%s -> 0x%s (this value becomes the table seed)",
                    QEDebug.xpSeed(value), QEDebug.hex(before), QEDebug.hex(seed));
        } else {
            QEDebug.log("playerRNG: ENCHANTING could not be maintained; player crack reset");
            resetCracker(RNGCallType.ENCHANTING, false);
        }
    }

    private static boolean canMaintainPlayerRNG(RNGCallType callType) {
        RNGCallEvent event = new RNGCallEvent(callType, Configs.playerRNGMaintenance && Configs.playerCrackState.knowsSeed());
        RNG_CALLED_EVENT.invoker().onCall(event);
        if (event.isMaintained && Configs.playerCrackState.knowsSeed()) {
            Configs.playerCrackState = CrackState.CRACKED;
            return true;
        } else {
            return event.isMaintainedEvenIfSeedUnknown;
        }
    }


    // ===== UTILITIES ===== //

    private static boolean isThrowItemsUntilThrowingItem = false;

    private static void throwItemsUntilOnRNGCallEvent(RNGCallEvent event) {
        if (isThrowItemsUntilThrowingItem && event.type == RNGCallType.DROP_ITEM) {
            event.setMaintained();
        }
    }

    public static ThrowItemsResult throwItemsUntil(Predicate<Random> condition, int max) {
        if (!Configs.playerCrackState.knowsSeed()) {
            return new ThrowItemsResult(ThrowItemsResult.Type.UNKNOWN_SEED);
        }
        Configs.playerCrackState = CrackState.CRACKED;

        long seed = PlayerRandCracker.seed;
        Random rand = new Random(seed ^ MULTIPLIER);

        int itemsNeeded = 0;
        for (; itemsNeeded <= max && !condition.test(rand); itemsNeeded++) {
            for (int i = 0; i < 4; i++) {
                seed = (seed * MULTIPLIER + ADDEND) & MASK;
            }
            rand.setSeed(seed ^ MULTIPLIER);
        }
        if (itemsNeeded > max) {
            return new ThrowItemsResult(ThrowItemsResult.Type.NOT_POSSIBLE, itemsNeeded);
        }
        for (int i = 0; i < itemsNeeded; i++) {
            ThrowItemsResult result;
            isThrowItemsUntilThrowingItem = true;
            try {
                result = throwItem();
            } finally {
                isThrowItemsUntilThrowingItem = false;
            }
            if (!result.isSuccess()) {
                return result;
            }
        }

        return new ThrowItemsResult(ThrowItemsResult.Type.SUCCESS);
    }

    public static ThrowItemsResult throwItem() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        MultiPlayerGameMode interactionManager = mc.gameMode;
        assert player != null && interactionManager != null;

        Screen currentScreen = Compat.getScreen(mc);
        boolean isInContainer = currentScreen instanceof AbstractContainerScreen && !(currentScreen instanceof CreativeModeInventoryScreen);
        boolean useCreativeThrow = player.hasInfiniteMaterials() && !isInContainer;
        if (useCreativeThrow) {
            // the client throttle is set a bit below the server throttle so we shouldn't get a desync here
            if (!player.canDropItems()) {
                return new ThrowItemsResult(ThrowItemsResult.Type.THROTTLED);
            }

            ItemStack stackToDrop = new ItemStack(Items.COBBLESTONE);
            Compat.dropItem(player, stackToDrop, true);
            interactionManager.handleCreativeModeItemDrop(stackToDrop);
            return new ThrowItemsResult(ThrowItemsResult.Type.SUCCESS);
        }

        Slot matchingSlot = throwSlotFilter != null
                ? getFilteredThrowSlot(player.containerMenu.slots)
                : getBestItemThrowSlot(player.containerMenu.slots);
        if (matchingSlot == null) {
            return new ThrowItemsResult(ThrowItemsResult.Type.NOT_ENOUGH_ITEMS);
        }
        interactionManager.handleContainerInput(player.containerMenu.containerId,
                matchingSlot.index, 0, ContainerInput.THROW, player);

        return new ThrowItemsResult(ThrowItemsResult.Type.SUCCESS);
    }

    /**
     * When non-null, item throws are restricted to slots passing this predicate (used by the
     * automation to throw whitelist items only). The keep-one rule is applied on top.
     */
    @Nullable
    public static Predicate<Slot> throwSlotFilter = null;

    @Nullable
    private static Slot getFilteredThrowSlot(List<Slot> slots) {
        return slots.stream()
                .filter(slot -> slot.hasItem() && !(slot instanceof ICreativeSlot))
                .filter(throwSlotFilter::test)
                // keep-one rule: never throw the last item of a stack
                .filter(slot -> slot.getItem().getCount() > 1)
                .max(Comparator.comparingInt((Slot slot) -> slot.getItem().getCount()))
                .orElse(null);
    }

    public static void unthrowItem() {
        seed = (seed * 0xdba6ed0471f1L + 0x25493d2c3b3cL) & MASK;
    }

    @Nullable
    public static Slot getBestItemThrowSlot(List<Slot> slots) {
        slots = slots.stream().filter(slot -> {
            if (!slot.hasItem()) {
                return false;
            }
            if (slot instanceof ICreativeSlot) {
                return false;
            }
            if (EnchantmentHelper.has(slot.getItem(), EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
                return false;
            }
            if (slot.getItem().getItem() == Items.CHORUS_FRUIT) {
                return false;
            }
            return true;
        }).collect(Collectors.toList());

        Map<Item, Integer> itemCounts = new HashMap<>();
        for (Slot slot : slots) {
            itemCounts.put(slot.getItem().getItem(), itemCounts.getOrDefault(slot.getItem().getItem(), 0) + slot.getItem().getCount());
        }
        if (itemCounts.isEmpty()) {
            return null;
        }
        Item preferredItem = itemCounts.keySet().stream().max(Comparator.comparingInt(Item::getDefaultMaxStackSize).thenComparing(itemCounts::get)).get();
        //noinspection OptionalGetWithoutIsPresent
        return slots.stream().filter(slot -> slot.getItem().getItem() == preferredItem).findFirst().get();
    }

    @Nullable
    private static final Field RANDOM_SEED;
    static {
        Field randomSeedField;
        try {
            randomSeedField = Random.class.getDeclaredField("seed");
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
        try {
            randomSeedField.setAccessible(true);
        } catch (Exception e) {
            // Java 14+ can't access private fields in these classes
            randomSeedField = null;
        }
        RANDOM_SEED = randomSeedField;
    }
    public static OptionalLong getSeed(Random rand) {
        if (RANDOM_SEED == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(((AtomicLong) RANDOM_SEED.get(rand)).get());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public static class ThrowItemsResult {
        private final Type type;
        private final Object[] messageArgs;

        public ThrowItemsResult(Type type, Object... messageArgs) {
            this.type = type;
            this.messageArgs = messageArgs;
        }

        public boolean isSuccess() {
            return type.success;
        }

        public Type getType() {
            return type;
        }

        public void sendErrorMessage() {
            for (MutableComponent message : type.messageCreator.apply(messageArgs)) {
                ClientCommandHelper.sendFeedback(message);
            }
        }

        public enum Type {
            NOT_ENOUGH_ITEMS(false, args -> List.of(
                Component.translatable("playerManip.notEnoughItems", args).withStyle(ChatFormatting.RED),
                Component.translatable("playerManip.notEnoughItems.help").withStyle(ChatFormatting.AQUA)
            )),
            NOT_POSSIBLE(false, "playerManip.throwError"),
            THROTTLED(false, args -> List.of(
                Component.translatable("playerManip.throttled", args).withStyle(ChatFormatting.RED),
                Component.translatable("playerManip.throttled.help").withStyle(ChatFormatting.AQUA)
            )),
            UNKNOWN_SEED(false, args -> List.of(Component.translatable("playerManip.uncracked")
                .append(" ")
                .append(CComponentUtil.getCommandTextComponent("commands.client.crack", "/ccrackrng"))
                .withStyle(ChatFormatting.RED))),
            SUCCESS(true, (Function<Object[], List<MutableComponent>>) null),
            ;

            private final boolean success;
            private final Function<Object[], List<MutableComponent>> messageCreator;

            Type(boolean success, String translationKey) {
                this(success, args -> List.of(Component.translatable(translationKey, args).withStyle(ChatFormatting.RED)));
            }

            Type(boolean success, Function<Object[], List<MutableComponent>> messageCreator) {
                this.success = success;
                this.messageCreator = messageCreator;
            }
        }
    }

    public enum CrackState implements StringRepresentable {
        UNCRACKED("uncracked"),
        CRACKED("cracked", true),
        ENCH_CRACKING_1("ench_cracking_1"),
        HALF_CRACKED("half_cracked"),
        ENCH_CRACKING_2("ench_cracking_2"),
        CRACKING("cracking"),
        EATING("eating"),
        ;

        private final String name;
        private final boolean knowsSeed;
        CrackState(String name) {
            this(name, false);
        }
        CrackState(String name, boolean knowsSeed) {
            this.name = name;
            this.knowsSeed = knowsSeed;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        public boolean knowsSeed() {
            return knowsSeed;
        }
    }

    @FunctionalInterface
    public interface RNGCallListener {
        void onCall(RNGCallEvent event);
    }

    public static final class RNGCallEvent {
        private final RNGCallType type;
        private boolean isMaintained;
        private boolean isMaintainedEvenIfSeedUnknown = false;

        public RNGCallEvent(RNGCallType type, boolean isMaintained) {
            this.type = type;
            this.isMaintained = isMaintained;
        }

        public RNGCallType getType() {
            return type;
        }

        public void setMaintained() {
            this.isMaintained = true;
        }

        public void setMaintainedEvenIfSeedUnknown() {
            this.isMaintainedEvenIfSeedUnknown = true;
        }
    }

    public enum RNGCallType {
        ADVANCEMENT("advancement"),
        AMETHYST_CHIME("amethystChime"),
        ANVIL("anvil"),
        BANE_OF_ARTHROPODS("baneOfArthropods"),
        CONSUME("consume"),
        CROSSBOW("crossbow"),
        DRINK("drink"),
        DROP_ITEM("dropItem"),
        ENCHANTING("enchanting"),
        ENTER_WATER("enterWater"),
        ENTITY_CRAMMING("entityCramming"),
        EQUIP_ITEM("equipItem"),
        FALL_FLYING("fallFlying"),
        FOOD("food"),
        FROST_WALKER("frostWalker"),
        GIVE("give"),
        ITEM_BREAK("itemBreak"),
        MENDING("mending"),
        PLAYER_HURT("playerHurt"),
        POTION("potion"),
        RECREATED("recreated"),
        RESPIRATION("respiration"),
        SHIELD("shield"),
        SHOULDER_PARROT("shoulderParrot"),
        SOUL_SPEED("soulSpeed"),
        SPRINT("sprint"),
        SWIM("swim"),
        UNBREAKING("unbreaking"),
        XP("xp"),
        ;

        private final Component resetMessage;
        RNGCallType(String resetMessage) {
            this.resetMessage = Component.translatable("playerManip.reset." + resetMessage);
        }

        public Component getResetMessage() {
            return resetMessage;
        }
    }

}
