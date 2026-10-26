package me.tianhangj.quickenchant.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.tianhangj.quickenchant.Configs;
import me.tianhangj.quickenchant.QuickEnchant;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ModConfig implements IConfigHandler {
    private static final String CONFIG_FILE_NAME = QuickEnchant.MOD_ID + ".json";
    private static final String GENERIC_KEY = QuickEnchant.MOD_ID + ".config.generic";

    private static final ModConfig INSTANCE = new ModConfig();

    public static ModConfig getInstance() {
        return INSTANCE;
    }

    public static class Generic {
        /** Item IDs (e.g. "minecraft:stick") whose stacks in the inventory may be auto-thrown to advance the RNG. */
        public static final ConfigStringList THROW_WHITELIST = new ConfigStringList("throwWhitelist", ImmutableList.of(),
                "Item IDs whose stacks in the inventory may be auto-thrown to advance the RNG. One ID per entry, e.g. minecraft:stick").apply(GENERIC_KEY);
        /**
         * Item used for the dummy enchant of the FIRST entry of a run. That dummy enchant is what
         * writes the table seed item 1 is enchanted with; from item 2 on the previous item's enchant
         * already writes the next seed, so no dummy is used. The dummy copy is dropped afterwards
         * (it carries random enchantments). Note the table only enchants items with no existing
         * enchantments, so the dummy must be a separate item from the real targets.
         */
        public static final ConfigString DUMMY_ITEM = new ConfigString("dummyItem", "minecraft:book",
                "Item used for the dummy enchant of the first item (writes its seed; the enchanted copy is dropped). Books are a good choice").apply(GENERIC_KEY);
        /** Whether the lectern bookshelf-count adjuster may be used. When off, the bookshelf count is never modified. */
        public static final ConfigBoolean LECTERN_ADJUSTMENT = new ConfigBoolean("lecternAdjustment", true,
                "Allow turning lectern pages to adjust the bookshelf count. When disabled the bookshelf count is never modified").apply(GENERIC_KEY);
        /** Show the enchantment prediction overlay and the Add Info button on the enchanting table screen. */
        public static final ConfigBoolean ENCHANTING_PREDICTION = new ConfigBoolean("enchantingPrediction", true,
                "Show the enchantment prediction overlay and the Add Info button on the enchanting table screen").apply(GENERIC_KEY);
        /** Keep the cracked player RNG maintained instead of resetting it on known RNG calls. */
        public static final ConfigBoolean PLAYER_RNG_MAINTENANCE = new ConfigBoolean("playerRNGMaintenance", true,
                "Keep the cracked player RNG maintained instead of resetting it on known RNG calls").apply(GENERIC_KEY);

        /**
         * Diagnostic mode. When enabled every phase transition, plan, RNG step and verification
         * result is logged with the concrete values needed to reproduce the run offline, and a
         * snapshot file is written to {@code <gameDir>/quick-enchant-debug/} whenever a
         * verification fails or the automation aborts.
         */
        public static final ConfigBoolean DEBUG = new ConfigBoolean("debug", false,
                "Log full automation state (plans, RNG steps, table predictions) and write snapshot files on failure. Use this to reproduce and report problems").apply(GENERIC_KEY);

        public static final ConfigInteger MAX_ENCHANT_ITEM_THROWS = new ConfigInteger("maxEnchantItemThrows", 64 * 256, 0, 1000000,
                "Maximum number of items that may be thrown while searching for the desired enchantments").apply(GENERIC_KEY);
        public static final ConfigInteger MIN_ENCHANT_BOOKSHELVES = new ConfigInteger("minEnchantBookshelves", 0, 0, 15,
                "Minimum bookshelf count to consider when searching for the desired enchantments").apply(GENERIC_KEY);
        public static final ConfigInteger MAX_ENCHANT_BOOKSHELVES = new ConfigInteger("maxEnchantBookshelves", 15, 0, 15,
                "Maximum bookshelf count to consider when searching for the desired enchantments").apply(GENERIC_KEY);
        public static final ConfigInteger MIN_ENCHANT_LEVELS = new ConfigInteger("minEnchantLevels", 1, 1, 30,
                "Minimum enchantment table level (slot cost) to consider").apply(GENERIC_KEY);
        public static final ConfigInteger MAX_ENCHANT_LEVELS = new ConfigInteger("maxEnchantLevels", 30, 1, 30,
                "Maximum enchantment table level (slot cost) to consider").apply(GENERIC_KEY);
        public static final ConfigInteger MAX_ENCHANT_SLOT = new ConfigInteger("maxEnchantSlot", 3, 1, 3,
                "Highest enchantment table slot (1-3) the automation may click").apply(GENERIC_KEY);
        public static final ConfigInteger MAX_THROWS_PER_TICK = new ConfigInteger("maxThrowsPerTick", 8, 1, 256,
                "Maximum number of items that may be thrown per tick while advancing the RNG (multiple throws from one stack in one tick are safe)").apply(GENERIC_KEY);

        public static final ConfigHotkey OPEN_SELECT_GUI = new ConfigHotkey("openSelectGui", "",
                "Open the enchantment selection screen").apply(GENERIC_KEY);
        public static final ConfigHotkey CONFIRM_EXECUTE = new ConfigHotkey("confirmExecute", "",
                "Confirm and start executing the planned enchantments").apply(GENERIC_KEY);
        public static final ConfigHotkey ABORT_AUTOMATION = new ConfigHotkey("abortAutomation", "",
                "Abort the running enchantment automation").apply(GENERIC_KEY);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                THROW_WHITELIST,
                DUMMY_ITEM,
                LECTERN_ADJUSTMENT,
                ENCHANTING_PREDICTION,
                PLAYER_RNG_MAINTENANCE,
                DEBUG,
                MAX_ENCHANT_ITEM_THROWS,
                MIN_ENCHANT_BOOKSHELVES,
                MAX_ENCHANT_BOOKSHELVES,
                MIN_ENCHANT_LEVELS,
                MAX_ENCHANT_LEVELS,
                MAX_ENCHANT_SLOT,
                MAX_THROWS_PER_TICK,
                OPEN_SELECT_GUI,
                CONFIRM_EXECUTE,
                ABORT_AUTOMATION
        );

        public static final List<IHotkey> HOTKEY_LIST = ImmutableList.of(
                OPEN_SELECT_GUI,
                CONFIRM_EXECUTE,
                ABORT_AUTOMATION
        );
    }

    @Override
    public void load() {
        loadFromFile();
        syncToFacade();
    }

    @Override
    public void save() {
        saveToFile();
    }

    public static void syncToFacade() {
        Configs.syncFromModConfig(getInstance());
    }

    private static void loadFromFile() {
        Path configFile = FileUtils.getConfigDirectory().resolve(CONFIG_FILE_NAME);

        if (Files.exists(configFile) && Files.isReadable(configFile)) {
            JsonElement element = JsonUtils.parseJsonFile(configFile);

            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Generic", Generic.OPTIONS);
            }
        }
    }

    private static void saveToFile() {
        Path dir = FileUtils.getConfigDirectory();

        if (!Files.exists(dir)) {
            FileUtils.createDirectoriesIfMissing(dir);
        }

        if (Files.isDirectory(dir)) {
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "Generic", Generic.OPTIONS);

            Path config = dir.resolve(CONFIG_FILE_NAME);
            JsonUtils.writeJsonToFile(root, config);
        }
    }
}
