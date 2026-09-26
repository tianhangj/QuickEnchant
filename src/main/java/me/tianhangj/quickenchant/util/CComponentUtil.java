package me.tianhangj.quickenchant.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * Slimmed-down port of clientcommands' CComponentUtil with only the clickable command
 * text helpers QuickEnchant uses.
 */
public final class CComponentUtil {
    private CComponentUtil() {
    }

    public static Component getCommandTextComponent(String translationKey, String command) {
        return getCommandTextComponent(Component.translatable(translationKey), command);
    }

    public static Component getCommandTextComponent(MutableComponent component, String command) {
        return component.withStyle(style -> style.applyFormat(ChatFormatting.UNDERLINE)
            .withColor(ChatFormatting.GREEN)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }
}
