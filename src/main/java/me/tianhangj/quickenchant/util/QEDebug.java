package me.tianhangj.quickenchant.util;

import me.tianhangj.quickenchant.Configs;
import me.tianhangj.quickenchant.QuickEnchant;
import me.tianhangj.quickenchant.automation.EnchantAutomation;
import me.tianhangj.quickenchant.automation.EnchantSolver;
import me.tianhangj.quickenchant.config.ModConfig;
import me.tianhangj.quickenchant.features.EnchantmentCracker;
import me.tianhangj.quickenchant.features.PlayerRandCracker;
import me.tianhangj.quickenchant.inventory.InventoryScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Diagnostic output for the enchantment automation.
 *
 * <p>Everything here is gated behind the {@code debug} config option. The goal is that a single
 * run with debug enabled is enough to reproduce and diagnose a failure later: every phase
 * transition, every plan (including both table predictions), every RNG step that moves the player
 * seed or the table seed, every throw confirmation and every verification mismatch is logged with
 * the concrete values that were expected and observed. On a mismatch or abort a full snapshot is
 * additionally written to {@code <gameDir>/quick-enchant-debug/} so it can be attached to a report.
 *
 * <p>Nothing in this class may throw: it is diagnostic code and must never break a run.
 */
public final class QEDebug {
    /** How many lines the ring buffer keeps for {@code /qedbg history} and for snapshots. */
    private static final int MAX_HISTORY = 600;
    private static final String DIR_NAME = "quick-enchant-debug";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Deque<String> HISTORY = new ArrayDeque<>(MAX_HISTORY);

    private QEDebug() {
    }

    // ===== gating =====

    /**
     * Whether diagnostics are on. Reads the malilib option directly (falling back to the facade)
     * so that toggling the switch in the config screen takes effect immediately, without needing
     * a config reload.
     */
    public static boolean enabled() {
        try {
            return ModConfig.Generic.DEBUG.getBooleanValue();
        } catch (Throwable t) {
            return Configs.debug;
        }
    }

    /** Logs one diagnostic line when debug is enabled. Never throws. */
    public static void log(String format, Object... args) {
        if (!enabled()) {
            return;
        }
        try {
            emit(format(format, args));
        } catch (Throwable ignored) {
        }
    }

    private static void emit(String message) {
        if (HISTORY.size() >= MAX_HISTORY) {
            HISTORY.removeFirst();
        }
        HISTORY.addLast(LocalTime.now().format(TIME) + " " + message);
        QuickEnchant.LOGGER.info("[qe-debug] {}", message);
    }

    private static String format(String format, Object... args) {
        return args == null || args.length == 0 ? format : String.format(Locale.ROOT, format, args);
    }

    public static List<String> history() {
        return new ArrayList<>(HISTORY);
    }

    // ===== value formatting =====

    /** 48-bit LCG state as 12 hex digits. */
    public static String hex(long value) {
        return String.format(Locale.ROOT, "%012X", value & 0xFFFFFFFFFFFFL);
    }

    public static String xpSeed(int seed) {
        return String.format(Locale.ROOT, "%08X", seed);
    }

    public static String pos(@Nullable BlockPos pos) {
        return pos == null ? "null" : "[" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "]";
    }

    /** e.g. {@code minecraft:sharpness=5}. */
    public static String enchantment(Holder<Enchantment> holder, int level) {
        String name = holder.unwrapKey()
                .map(key -> key.identifier().toString())
                .orElse("<unregistered>");
        return name + "=" + level;
    }

    public static String enchantments(@Nullable List<EnchantmentInstance> list) {
        if (list == null) {
            return "null";
        }
        if (list.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            EnchantmentInstance instance = list.get(i);
            sb.append(enchantment(instance.enchantment(), instance.level()));
        }
        return sb.append(']').toString();
    }

    public static String stack(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id + "x" + stack.getCount();
    }

    /** All three slots of a prediction, as one line. */
    public static String prediction(EnchantmentCracker.@Nullable TablePrediction prediction) {
        if (prediction == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("costs=").append(Arrays.toString(prediction.costs()));
        sb.append(" clues=").append(Arrays.toString(prediction.enchantClueIds()));
        sb.append(" levels=").append(Arrays.toString(prediction.levelClues()));
        sb.append(" lists=[");
        for (int i = 0; i < prediction.slotLists().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(enchantments(prediction.slotLists().get(i)));
        }
        return sb.append(']').toString();
    }

    /** The live menu's synced slots, as one line. */
    public static String menuState(@Nullable EnchantmentMenu menu) {
        if (menu == null) {
            return "menu=null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(menu.containerId);
        sb.append(" item=").append(stack(menu.getSlot(0).getItem()));
        sb.append(" lapis=").append(stack(menu.getSlot(1).getItem()));
        sb.append(" carried=").append(stack(menu.getCarried()));
        sb.append(" costs=").append(Arrays.toString(menu.costs));
        sb.append(" clues=").append(Arrays.toString(menu.enchantClue));
        sb.append(" levels=").append(Arrays.toString(menu.levelClue));
        return sb.toString();
    }

    /**
     * Per-slot explanation of why a prediction does not match the menu. This is the single most
     * useful diagnostic when the automation aborts with "verification failed": it shows whether
     * only the costs differ (wrong table seed), only the clue differs (same costs but a different
     * enchantment list), or everything differs (wrong item / wrong bookshelf count).
     */
    public static String diagnose(@Nullable EnchantmentMenu menu, EnchantmentCracker.@Nullable TablePrediction prediction) {
        if (menu == null || prediction == null) {
            return "cannot diagnose (menu or prediction is null)";
        }
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            boolean costOk = menu.costs[i] == prediction.costs()[i];
            boolean clueOk = menu.enchantClue[i] == prediction.enchantClueIds()[i];
            boolean levelOk = menu.levelClue[i] == prediction.levelClues()[i];
            if (costOk && clueOk && levelOk) {
                continue;
            }
            any = true;
            sb.append("\n  slot ").append(i).append(": ");
            sb.append("cost expected=").append(prediction.costs()[i]).append(" actual=").append(menu.costs[i])
                    .append(costOk ? " (ok)" : " (MISMATCH)");
            sb.append(", clue expected=").append(prediction.enchantClueIds()[i]).append(" actual=").append(menu.enchantClue[i])
                    .append(clueOk ? " (ok)" : " (MISMATCH)");
            sb.append(", level expected=").append(prediction.levelClues()[i]).append(" actual=").append(menu.levelClue[i])
                    .append(levelOk ? " (ok)" : " (MISMATCH)");
            List<List<EnchantmentInstance>> lists = prediction.slotLists();
            sb.append("\n         expected list: ")
                    .append(enchantments(i < lists.size() ? lists.get(i) : List.of()));
        }
        if (!any) {
            return "all three slots match";
        }
        sb.append("\n  hint: costs differ => wrong table seed;");
        sb.append(" costs equal but clue differs => wrong enchantment list (item or seed);");
        sb.append(" costs all 0 => the slot item is not enchantable or the menu did not resync yet");
        return sb.toString();
    }

    // ===== state snapshot =====

    public static String seedState() {
        StringBuilder sb = new StringBuilder();
        sb.append("playerSeed=0x").append(hex(PlayerRandCracker.getSeed()));
        sb.append(" (").append(Configs.playerCrackState.getSerializedName()).append(')');
        Integer xp = xpSeedOrNull();
        sb.append(" xpSeed=").append(xp == null ? "unknown" : xpSeed(xp));
        sb.append(" (").append(Configs.enchCrackState.getSerializedName()).append(')');
        return sb.toString();
    }

    /**
     * The cracked table seed, or null when it is not known. Guarded because callers must not throw
     * while the crack state is mid-transition.
     */
    public static @Nullable Integer xpSeedOrNull() {
        try {
            if (Configs.enchCrackState != EnchantmentCracker.CrackState.CRACKED) {
                return null;
            }
            return EnchantmentCracker.getCurrentXpSeed();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Full state dump: everything needed to reproduce the current situation offline. */
    public static String snapshot(String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== QuickEnchant debug snapshot ===\n");
        sb.append("time: ").append(LocalTime.now().format(TIME)).append('\n');
        sb.append("reason: ").append(reason).append('\n');
        sb.append("seed: ").append(seedState()).append('\n');

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        sb.append("world: ").append(mc.level == null ? "null" : mc.level.dimension().identifier().toString()).append('\n');
        sb.append("player: ").append(player == null ? "null" : player.getName().getString()).append('\n');

        BlockPos table = EnchantmentCracker.enchantingTablePos;
        sb.append("enchantingTable: ").append(pos(table)).append('\n');
        sb.append("lectern: ").append(pos(EnchantmentCracker.lecternPos)).append('\n');
        if (table != null && player != null && mc.level != null) {
            sb.append("bookshelves(actual): ").append(EnchantmentCracker.getBookshelfCount(mc.level, table)).append('\n');
        }

        EnchantAutomation automation = EnchantAutomation.getRunning();
        sb.append("automation: ").append(automation == null ? "not running" : automation.debugSummary()).append('\n');

        if (player != null) {
            sb.append("menuType: ").append(player.containerMenu.getClass().getSimpleName()).append('\n');
            sb.append("menu: ").append(menuState(player.containerMenu instanceof EnchantmentMenu m ? m : null)).append('\n');
            sb.append("xpLevel: ").append(player.experienceLevel).append('\n');

            sb.append("\n-- inventory --\n");
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                sb.append("  slot ").append(slot).append(": ").append(stack(stack));
                if (InventoryScanner.isShulkerBox(stack)) {
                    List<String> contents = new ArrayList<>();
                    for (ItemStack content : InventoryScanner.readBoxContents(stack)) {
                        contents.add(stack(content));
                    }
                    sb.append(" contents=").append(contents);
                }
                sb.append('\n');
            }
        }

        sb.append("\n-- last debug lines --\n");
        List<String> lines = history();
        for (int i = Math.max(0, lines.size() - 150); i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    // ===== file output =====

    /** Directory used for dumps, not created until something is written. */
    public static Path directory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(DIR_NAME);
    }

    /** Writes a file into the debug directory. Returns null when writing failed (never throws). */
    public static @Nullable Path write(String fileName, String content) {
        try {
            Path dir = directory();
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException | RuntimeException e) {
            QuickEnchant.LOGGER.warn("failed to write debug file {}", fileName, e);
            return null;
        }
    }

    /** Writes a full snapshot for the given reason and logs where it went. Never throws. */
    public static void dump(String reason, String fileName) {
        if (!enabled()) {
            return;
        }
        try {
            Path file = write(fileName, snapshot(reason));
            if (file != null) {
                QuickEnchant.LOGGER.info("[qe-debug] snapshot written to {}", file.toAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
    }

    // ===== plan formatting =====

    public static String plan(EnchantSolver.@Nullable Plan plan) {
        if (plan == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("itemThrows=").append(plan.itemThrows())
                .append(" dummySlot=").append(plan.usesDummy() ? plan.dummySlot() : "none")
                .append(" realSlot=").append(plan.realSlot())
                .append(" bookshelves=").append(plan.bookshelves())
                .append(" windowThrows=").append(plan.windowThrows())
                .append("\n  xpSeed=").append(xpSeed(plan.xpSeed()))
                .append("\n  realList=").append(enchantments(plan.realList()));
        if (plan.placePrediction() != null) {
            sb.append("\n  placePrediction=").append(prediction(plan.placePrediction()));
        }
        sb.append("\n  swapPrediction=").append(prediction(plan.swapPrediction()));
        return sb.toString();
    }
}
