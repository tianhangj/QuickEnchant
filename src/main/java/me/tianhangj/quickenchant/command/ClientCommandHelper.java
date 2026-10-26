package me.tianhangj.quickenchant.command;

import me.tianhangj.quickenchant.util.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 命令反馈输出。所有 HUD/聊天访问都经由 {@link Compat}，
 * 因此本文件不随 Minecraft 版本变化。
 */
public class ClientCommandHelper {
    public static void sendError(Component error) {
        sendFeedback(Component.literal("").append(error).withStyle(ChatFormatting.RED));
    }

    public static void sendHelp(Component help) {
        sendFeedback(Component.literal("").append(help).withStyle(ChatFormatting.AQUA));
    }

    public static void sendFeedback(String message, Object... args) {
        sendFeedback(Component.translatable(message, args));
    }

    public static void sendFeedback(Component message) {
        Compat.addChatMessage(Minecraft.getInstance(), message);
    }

    public static void addOverlayMessage(Component message, int time) {
        Compat.setOverlayMessage(Minecraft.getInstance(), message, time);
    }
}
