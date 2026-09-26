package me.tianhangj.quickenchant;

import me.tianhangj.quickenchant.features.EnchantmentCracker;
import me.tianhangj.quickenchant.features.PlayerRandCracker;
import me.tianhangj.quickenchant.config.ModConfig;
import net.minecraft.util.Mth;

/**
 * Compatibility facade mimicking the config surface of the ported clientcommands code.
 * Temporary crack states live here; tunable options are backed by the malilib config
 * in {@link ModConfig}.
 */
public class Configs {
    // ===== temporary runtime state =====
    public static EnchantmentCracker.CrackState enchCrackState = EnchantmentCracker.CrackState.UNCRACKED;
    public static PlayerRandCracker.CrackState playerCrackState = PlayerRandCracker.CrackState.UNCRACKED;
    public static boolean enchantingPrediction = false;
    public static boolean playerRNGMaintenance = true;

    // ===== tunables (persisted via ModConfig) =====
    private static int maxEnchantItemThrows = 64 * 256;
    private static int minEnchantBookshelves = 0;
    private static int maxEnchantBookshelves = 15;
    private static int minEnchantLevels = 1;
    private static int maxEnchantLevels = 30;
    private static int maxEnchantSlot = 3;
    private static float itemThrowsPerTick = 1;

    public static int getMaxEnchantItemThrows() {
        return maxEnchantItemThrows;
    }

    public static void setMaxEnchantItemThrows(int maxEnchantItemThrows) {
        Configs.maxEnchantItemThrows = Mth.clamp(maxEnchantItemThrows, 0, 1000000);
    }

    public static int getMinEnchantBookshelves() {
        return minEnchantBookshelves;
    }

    public static void setMinEnchantBookshelves(int minEnchantBookshelves) {
        Configs.minEnchantBookshelves = Mth.clamp(minEnchantBookshelves, 0, 15);
        Configs.maxEnchantBookshelves = Math.max(Configs.maxEnchantBookshelves, Configs.minEnchantBookshelves);
    }

    public static int getMaxEnchantBookshelves() {
        return maxEnchantBookshelves;
    }

    public static void setMaxEnchantBookshelves(int maxEnchantBookshelves) {
        Configs.maxEnchantBookshelves = Mth.clamp(maxEnchantBookshelves, 0, 15);
        Configs.minEnchantBookshelves = Math.min(Configs.minEnchantBookshelves, Configs.maxEnchantBookshelves);
    }

    public static int getMinEnchantLevels() {
        return minEnchantLevels;
    }

    public static void setMinEnchantLevels(int minEnchantLevels) {
        Configs.minEnchantLevels = Mth.clamp(minEnchantLevels, 1, 30);
        Configs.maxEnchantLevels = Math.max(Configs.maxEnchantLevels, Configs.minEnchantLevels);
    }

    public static int getMaxEnchantLevels() {
        return maxEnchantLevels;
    }

    public static void setMaxEnchantLevels(int maxEnchantLevels) {
        Configs.maxEnchantLevels = Mth.clamp(maxEnchantLevels, 1, 30);
        Configs.minEnchantLevels = Math.min(Configs.minEnchantLevels, Configs.maxEnchantLevels);
    }

    public static int getMaxEnchantSlot() {
        return maxEnchantSlot;
    }

    public static void setMaxEnchantSlot(int maxEnchantSlot) {
        Configs.maxEnchantSlot = Mth.clamp(maxEnchantSlot, 1, 3);
    }

    public static float getItemThrowsPerTick() {
        return itemThrowsPerTick;
    }

    public static void setItemThrowsPerTick(float itemThrowsPerTick) {
        Configs.itemThrowsPerTick = Mth.clamp(itemThrowsPerTick, 0.05f, 64.0f);
    }

    /**
     * Push persisted malilib values into the facade. Called when the malilib config loads.
     */
    public static void syncFromModConfig(ModConfig config) {
        setMaxEnchantItemThrows(ModConfig.Generic.MAX_ENCHANT_ITEM_THROWS.getIntegerValue());
        setMinEnchantBookshelves(ModConfig.Generic.MIN_ENCHANT_BOOKSHELVES.getIntegerValue());
        setMaxEnchantBookshelves(ModConfig.Generic.MAX_ENCHANT_BOOKSHELVES.getIntegerValue());
        setMinEnchantLevels(ModConfig.Generic.MIN_ENCHANT_LEVELS.getIntegerValue());
        setMaxEnchantLevels(ModConfig.Generic.MAX_ENCHANT_LEVELS.getIntegerValue());
        setMaxEnchantSlot(ModConfig.Generic.MAX_ENCHANT_SLOT.getIntegerValue());
        setItemThrowsPerTick((float) ModConfig.Generic.ITEM_THROWS_PER_TICK.getDoubleValue());
        enchantingPrediction = ModConfig.Generic.ENCHANTING_PREDICTION.getBooleanValue();
        playerRNGMaintenance = ModConfig.Generic.PLAYER_RNG_MAINTENANCE.getBooleanValue();
    }
}
