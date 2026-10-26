package me.tianhangj.quickenchant.mixin.rngevents;

import me.tianhangj.quickenchant.features.PlayerRandCracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MultiPlayerGameMode 注入（本文件是 26.3 形状）。
 *
 * <p>相对 26.2 多出的注入：26.3 删除了 {@code LocalPlayer.drop(boolean)}，
 * 客户端丢物入口改成 {@code MultiPlayerGameMode.dropItem(LocalPlayer, boolean)}，
 * 因此丢物的 RNG 捕获搬到这里。
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "destroyBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;playerWillDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/level/block/state/BlockState;"))
    public void onDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        assert player != null;
        Level level = player.level();
        ItemStack stack = player.getMainHandItem();
        if (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)) {
            BlockState state = level.getBlockState(pos);
            if (state.getDestroySpeed(level, pos) != 0) {
                PlayerRandCracker.onItemDamage(1, player, stack);
            }
        }
    }

    // 26.3：丢物入口
    @Inject(method = "dropItem", at = @At("HEAD"))
    private void onDropItem(LocalPlayer player, boolean dropAll, CallbackInfo ci) {
        PlayerRandCracker.onDropItem();
    }

    @Inject(method = "startPrediction", at = @At("HEAD"))
    private void preStartPrediction(CallbackInfo ci) {
        PlayerRandCracker.isPredictingBlockBreaking = true;
    }

    @Inject(method = "startPrediction", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V", shift = At.Shift.AFTER))
    private void postStartPrediction(CallbackInfo ci) {
        PlayerRandCracker.postSendBlockBreakingPredictionPacket();
    }

    @Inject(method = "startPrediction", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/prediction/BlockStatePredictionHandler;close()V"))
    private void startPredictionFinally(CallbackInfo ci) {
        PlayerRandCracker.isPredictingBlockBreaking = false;
    }
}
