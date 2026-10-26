package me.tianhangj.quickenchant.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Prediction;

/**
 * 版本差异的唯一集中点（本文件是 26.3 形状，覆盖 src/main/java 中的 26.1.2 版本）。
 *
 * <p>相对 26.2 只多一处破坏性变更：
 * <ul>
 *   <li>{@code Player.drop(ItemStack, boolean)} 被删除，
 *       改为 {@code LivingEntity.drop(ItemStack, boolean, Prediction)}。</li>
 * </ul>
 * 传 {@link Prediction#PREDICTED} 表示客户端预测执行（与原行为一致）；
 * 该参数只影响 swing 的第三个形参，不改变客户端是否创建 ItemEntity，
 * 因此 RNG 步数语义与 26.2 完全相同。
 */
public final class Compat {
    private Compat() {
    }

    public static Screen getScreen(Minecraft mc) {
        return mc.gui.screen();
    }

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

    /** 26.3 的 drop 需要显式的 Prediction 参数。 */
    public static ItemEntity dropItem(Player player, ItemStack stack, boolean dropAll) {
        return player.drop(stack, dropAll, Prediction.PREDICTED);
    }
}
