package me.tianhangj.quickenchant.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 版本差异的唯一集中点（本文件是 26.1.2 形状）。
 *
 * <p>本仓库其它所有代码 —— RNG 破解、附魔求解、自动化状态机、GUI、命令、配置 ——
 * 都只调用这里的方法，不直接触碰会随版本变化的原版 API。这样核心逻辑永远只有一份。
 *
 * <p>26.2 与 26.3 存在同名覆盖版本：
 * <pre>
 *   src/versioned/26.2/java/.../util/Compat.java
 *   src/versioned/26.3/java/.../util/Compat.java
 * </pre>
 * Gradle 会把对应目录叠加到源码集之上，同名文件即覆盖。因此本文件仅在 26.1.2 构建中生效。
 */
public final class Compat {
    private Compat() {
    }

    // ===== 屏幕（26.2 起 Minecraft.screen 字段与 setScreen 被删除，状态搬到 Gui） =====

    /** 取当前屏幕，可能为 null。 */
    public static Screen getScreen(Minecraft mc) {
        return mc.screen;
    }

    /** 切换屏幕（null 表示关闭当前屏幕）。 */
    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    // ===== 聊天与 HUD 覆盖文字（26.2 起 Gui 的 HUD 内容整体迁到 Gui.hud） =====

    /** 往聊天框加一条客户端消息。 */
    public static void addChatMessage(Minecraft mc, Component message) {
        mc.gui.getChat().addClientSystemMessage(message);
    }

    /** 在物品栏上方显示覆盖文字，持续 time 个 tick。 */
    public static void setOverlayMessage(Minecraft mc, Component message, int time) {
        mc.gui.setOverlayMessage(message, false);
        mc.gui.overlayMessageTime = time;
    }

    // ===== 实体类型（26.2 起 EntityType 的全部静态常量迁到 EntityTypes） =====

    /** 掉落物（ItemEntity）的实体类型。 */
    public static EntityType<?> itemEntityType() {
        return EntityType.ITEM;
    }

    // ===== 丢物（26.3 起丢物入口与签名变更） =====

    /**
     * 令玩家丢出一个物品。
     *
     * <p>注意：三个版本的客户端实现都不会在客户端创建 ItemEntity，
     * 因此这一步的玩家 RNG 步数语义在各版本中一致，不能按版本调整步数账。
     */
    public static ItemEntity dropItem(Player player, ItemStack stack, boolean dropAll) {
        return player.drop(stack, dropAll);
    }
}
