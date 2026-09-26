package me.tianhangj.quickenchant.command;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;

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
        Minecraft.getInstance().gui.getChat().addClientSystemMessage(message);
    }

    public static void addOverlayMessage(Component message, int time) {
        Gui gui = Minecraft.getInstance().gui;
        gui.setOverlayMessage(message, false);
        gui.overlayMessageTime = time;
    }
}
