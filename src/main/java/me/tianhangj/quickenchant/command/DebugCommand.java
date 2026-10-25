package me.tianhangj.quickenchant.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.tianhangj.quickenchant.Configs;
import me.tianhangj.quickenchant.automation.EnchantAutomation;
import me.tianhangj.quickenchant.config.ModConfig;
import me.tianhangj.quickenchant.features.EnchantmentCracker;
import me.tianhangj.quickenchant.features.PlayerRandCracker;
import me.tianhangj.quickenchant.util.QEDebug;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.nio.file.Path;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

/**
 * Debug-only commands. These are always registered (so they can be tab-completed and discovered)
 * but every subcommand refuses to run unless the {@code debug} config option is enabled, which
 * keeps them from being usable as a way to poke at state during normal play.
 *
 * <p>They exist so that a failure can be reproduced and inspected without recompiling:
 * {@code state} shows the live automation/RNG state, {@code seed}/{@code xpseed} read and
 * override the two seeds, {@code menu} compares the open table against a fresh prediction and
 * says which part disagrees, and {@code dump} writes the full snapshot to a file.
 */
public class DebugCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("qedebug")
                .executes(ctx -> toggle(ctx.getSource()))
                .then(literal("on").executes(ctx -> setDebug(ctx.getSource(), true)))
                .then(literal("off").executes(ctx -> setDebug(ctx.getSource(), false)))
                .then(literal("state").executes(ctx -> state(ctx.getSource())))
                .then(literal("dump").executes(ctx -> dump(ctx.getSource())))
                .then(literal("history")
                        .executes(ctx -> history(ctx.getSource(), 40))
                        .then(argument("lines", IntegerArgumentType.integer(1, 400))
                                .executes(ctx -> history(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "lines")))))
                .then(literal("menu").executes(ctx -> menu(ctx.getSource())))
                .then(literal("seed")
                        .executes(ctx -> showSeed(ctx.getSource()))
                        .then(argument("value", StringArgumentType.word())
                                .executes(ctx -> setSeed(ctx.getSource(), StringArgumentType.getString(ctx, "value")))))
                .then(literal("xpseed")
                        .executes(ctx -> showXpSeed(ctx.getSource()))
                        .then(argument("value", StringArgumentType.word())
                                .executes(ctx -> setXpSeed(ctx.getSource(), StringArgumentType.getString(ctx, "value"))))));
    }

    // ===== gating =====

    private static boolean requireDebug(FabricClientCommandSource source) {
        if (QEDebug.enabled()) {
            return true;
        }
        source.sendFeedback(Component.translatable("quick-enchant.debug.disabled").withStyle(ChatFormatting.RED));
        return false;
    }

    // ===== toggle =====

    private static int toggle(FabricClientCommandSource source) {
        return setDebug(source, !ModConfig.Generic.DEBUG.getBooleanValue());
    }

    private static int setDebug(FabricClientCommandSource source, boolean value) {
        ModConfig.Generic.DEBUG.setBooleanValue(value);
        Configs.debug = value;
        ModConfig.getInstance().save();
        source.sendFeedback(Component.translatable(
                value ? "quick-enchant.debug.enabled" : "quick-enchant.debug.turnedOff",
                ModConfig.Generic.DEBUG.getBooleanValue() ? "on" : "off").withStyle(ChatFormatting.GREEN));
        if (value) {
            source.sendFeedback(Component.translatable("quick-enchant.debug.where").withStyle(ChatFormatting.GRAY));
        }
        QEDebug.log("debug %s via /qedebug", value ? "enabled" : "disabled");
        return Command.SINGLE_SUCCESS;
    }

    // ===== state =====

    private static int state(FabricClientCommandSource source) {
        if (!requireDebug(source)) {
            return 0;
        }
        source.sendFeedback(Component.literal("--- QuickEnchant state ---").withStyle(ChatFormatting.GOLD));
        source.sendFeedback(Component.literal("seed: " + QEDebug.seedState()).withStyle(ChatFormatting.GRAY));

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            source.sendFeedback(Component.literal("menu: " + QEDebug.menuState(
                    player.containerMenu instanceof EnchantmentMenu m ? m : null)).withStyle(ChatFormatting.GRAY));
            source.sendFeedback(Component.literal("xpLevel: " + player.experienceLevel
                    + " table=" + QEDebug.pos(EnchantmentCracker.enchantingTablePos)
                    + " lectern=" + QEDebug.pos(EnchantmentCracker.lecternPos)).withStyle(ChatFormatting.GRAY));
        } else {
            source.sendFeedback(Component.literal("player: null").withStyle(ChatFormatting.GRAY));
        }

        EnchantAutomation automation = EnchantAutomation.getRunning();
        String summary = automation == null ? "automation: not running" : "automation: " + automation.debugSummary();
        for (String line : summary.split("\n")) {
            source.sendFeedback(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        QEDebug.log("state requested via /qedebug state");
        return Command.SINGLE_SUCCESS;
    }

    private static int dump(FabricClientCommandSource source) {
        if (!requireDebug(source)) {
            return 0;
        }
        String name = "manual-" + System.currentTimeMillis() + ".txt";
        Path file = QEDebug.write(name, QEDebug.snapshot("manual dump via /qedebug dump"));
        if (file == null) {
            source.sendFeedback(Component.translatable("quick-enchant.debug.dumpFailed").withStyle(ChatFormatting.RED));
            return 0;
        }
        source.sendFeedback(Component.translatable("quick-enchant.debug.dumped", file.toAbsolutePath().toString())
                .withStyle(ChatFormatting.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private static int history(FabricClientCommandSource source, int lines) {
        if (!requireDebug(source)) {
            return 0;
        }
        List<String> history = QEDebug.history();
        if (history.isEmpty()) {
            source.sendFeedback(Component.translatable("quick-enchant.debug.noHistory").withStyle(ChatFormatting.GRAY));
            return Command.SINGLE_SUCCESS;
        }
        int from = Math.max(0, history.size() - lines);
        for (int i = from; i < history.size(); i++) {
            source.sendFeedback(Component.literal(history.get(i)).withStyle(ChatFormatting.DARK_GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ===== menu diagnosis =====

    /**
     * Recomputes the prediction for whatever currently sits in table slot 0 with the actual
     * bookshelf count, and reports how it differs from the live menu. This is the offline
     * "is my seed right?" check without having to read a log.
     */
    private static int menu(FabricClientCommandSource source) {
        if (!requireDebug(source)) {
            return 0;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(player.containerMenu instanceof EnchantmentMenu menu)) {
            source.sendFeedback(Component.translatable("quick-enchant.debug.noTable").withStyle(ChatFormatting.RED));
            return 0;
        }
        source.sendFeedback(Component.literal("menu: " + QEDebug.menuState(menu)).withStyle(ChatFormatting.GRAY));

        Integer xpSeed = QEDebug.xpSeedOrNull();
        if (xpSeed == null) {
            source.sendFeedback(Component.translatable("quick-enchant.debug.noXpSeed").withStyle(ChatFormatting.RED));
            return 0;
        }
        ItemStack stack = menu.getSlot(0).getItem();
        if (stack.isEmpty()) {
            source.sendFeedback(Component.translatable("quick-enchant.debug.slotEmpty").withStyle(ChatFormatting.RED));
            return 0;
        }

        var tablePos = EnchantmentCracker.enchantingTablePos;
        int bookshelves = tablePos == null ? 0 : EnchantmentCracker.getBookshelfCount(player.level(), tablePos);
        Registry<Enchantment> registry = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        EnchantmentCracker.TablePrediction prediction =
                EnchantmentCracker.predictTable(registry, xpSeed, stack, bookshelves);

        source.sendFeedback(Component.literal("predicted with xpSeed=" + QEDebug.xpSeed(xpSeed)
                + " bookshelves=" + bookshelves + ": " + QEDebug.prediction(prediction))
                .withStyle(ChatFormatting.GRAY));
        String diagnosis = QEDebug.diagnose(menu, prediction);
        for (String line : diagnosis.split("\n")) {
            source.sendFeedback(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        QEDebug.log("menu diagnosis at %s (%d bookshelves):\n  predicted: %s\n  actual:    %s\n  %s",
                QEDebug.pos(tablePos), bookshelves, QEDebug.prediction(prediction),
                QEDebug.menuState(menu), diagnosis);
        return Command.SINGLE_SUCCESS;
    }

    // ===== seeds =====

    private static int showSeed(FabricClientCommandSource source) {
        if (!requireDebug(source)) {
            return 0;
        }
        source.sendFeedback(Component.literal("playerSeed = 0x" + QEDebug.hex(PlayerRandCracker.getSeed())
                + " (" + Configs.playerCrackState.getSerializedName() + ")").withStyle(ChatFormatting.GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private static int setSeed(FabricClientCommandSource source, String value) {
        if (!requireDebug(source)) {
            return 0;
        }
        Long parsed = parseHex(value);
        if (parsed == null) {
            source.sendFeedback(Component.translatable("quick-enchant.debug.badNumber", value)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        long previous = PlayerRandCracker.getSeed();
        PlayerRandCracker.setSeed(parsed);
        Configs.playerCrackState = PlayerRandCracker.CrackState.CRACKED;
        source.sendFeedback(Component.literal("playerSeed: 0x" + QEDebug.hex(previous)
                + " -> 0x" + QEDebug.hex(parsed)).withStyle(ChatFormatting.GREEN));
        QEDebug.log("playerSeed overridden via /qedebug seed: 0x%s -> 0x%s", QEDebug.hex(previous), QEDebug.hex(parsed));
        return Command.SINGLE_SUCCESS;
    }

    private static int showXpSeed(FabricClientCommandSource source) {
        if (!requireDebug(source)) {
            return 0;
        }
        Integer xpSeed = QEDebug.xpSeedOrNull();
        source.sendFeedback(Component.literal("xpSeed = "
                + (xpSeed == null ? "unknown" : QEDebug.xpSeed(xpSeed))
                + " (" + Configs.enchCrackState.getSerializedName() + ")").withStyle(ChatFormatting.GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private static int setXpSeed(FabricClientCommandSource source, String value) {
        if (!requireDebug(source)) {
            return 0;
        }
        Long parsed = parseHex(value);
        if (parsed == null || parsed > Integer.MAX_VALUE || parsed < Integer.MIN_VALUE) {
            source.sendFeedback(Component.translatable("quick-enchant.debug.badNumber", value)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        int seed = (int) (long) parsed;
        EnchantmentCracker.setXpSeedForDebug(seed);
        source.sendFeedback(Component.literal("xpSeed = " + QEDebug.xpSeed(seed)).withStyle(ChatFormatting.GREEN));
        QEDebug.log("xpSeed overridden via /qedebug xpseed: %s", QEDebug.xpSeed(seed));
        return Command.SINGLE_SUCCESS;
    }

    /** Accepts plain decimal or 0x-prefixed hex. */
    private static Long parseHex(String value) {
        try {
            String trimmed = value.trim();
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return Long.parseUnsignedLong(trimmed.substring(2), 16);
            }
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
