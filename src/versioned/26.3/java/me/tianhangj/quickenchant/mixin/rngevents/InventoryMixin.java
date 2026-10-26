package me.tianhangj.quickenchant.mixin.rngevents;

import me.tianhangj.quickenchant.features.PlayerRandCracker;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 本文件是 26.3 形状。
 *
 * <p>26.3 把 {@code placeItemBackInInventory(ItemStack, boolean)} 的 boolean 换成了
 * {@link Prediction}，变为 {@code (ItemStack, Prediction)}；
 * 同时新增了 {@code (ItemStack, boolean, Prediction)} 重载。
 *
 * <p>这里改注入三参重载：它才是同时带「notifiesClient」和预测语义的完整版本；
 * 单参 {@code (ItemStack, Prediction)} 会直接委托给它。两者若都注入会重复计数，
 * 因此只挂三参这一个。
 */
@Mixin(Inventory.class)
public class InventoryMixin {

    @Shadow @Final public NonNullList<ItemStack> items;
    @Shadow @Final public Player player;

    @Inject(method = "placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)V", at = @At("HEAD"))
    public void onOfferOrDrop(ItemStack stack, boolean notifiesClient, Prediction prediction, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer)) {
            int stackSize = stack.getCount();
            for (ItemStack item : items) {
                if (item.isEmpty()) {
                    stackSize -= stack.getMaxStackSize();
                } else if (ItemStack.isSameItemSameComponents(item, stack)) {
                    stackSize -= stack.getMaxStackSize() - item.getCount();
                }
            }
            if (stackSize > 0) {
                PlayerRandCracker.onDropItem();
            }
        }
    }

}
