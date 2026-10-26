package me.tianhangj.quickenchant.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 版本差异的唯一集中点（本文件是 26.2 形状，覆盖 src/main/java 中的 26.1.2 版本）。
 *
 * <p>26.2 的三处破坏性变更：
 * <ul>
 *   <li>{@code Minecraft.screen} 字段与 {@code Minecraft.setScreen} 被删除，
 *       状态搬到 {@code Gui}（读 {@code gui.screen()}，写 {@code mc.setScreenAndShow}）。</li>
 *   <li>{@code Gui} 的 HUD 内容整体迁到新类 {@code Hud}（{@code gui.hud}）。</li>
 *   <li>{@code EntityType} 的全部静态常量迁到新类 {@code EntityTypes}。</li>
 * </ul>
 */
public final class Compat {
    private Compat() {
    }

    public static Screen getScreen(Minecraft mc) {
        return mc.gui.screen();
    }

    /**
     * 26.2 起只有 setScreenAndShow 可用 —— 它内部执行 gui.setScreen + renderFrame(false)。
     * 26.1.2 的 mc.setScreen 只切屏幕不渲染，这里语义略有差别（多一次渲染），
     * 但这是官方唯一保留的入口，且对自动化流程无副作用。
     */
    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreenAndShow(screen);
    }

    public static void addChatMessage(Minecraft mc, Component message) {
        mc.gui.hud.getChat().addClientSystemMessage(message);
    }

    public static void setOverlayMessage(Minecraft mc, Component message, int time) {
        mc.gui.hud.setOverlayMessage(message, false);
        mc.gui.hud.overlayMessageTime = time;
    }

    public static EntityType<?> itemEntityType() {
        return EntityTypes.ITEM;
    }

    /** 26.2 仍有 Player.drop(ItemStack, boolean) 这个便捷重载。 */
    public static ItemEntity dropItem(Player player, ItemStack stack, boolean dropAll) {
        return player.drop(stack, dropAll);
    }
}
