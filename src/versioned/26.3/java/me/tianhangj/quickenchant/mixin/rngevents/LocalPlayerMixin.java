package me.tianhangj.quickenchant.mixin.rngevents;

import com.mojang.authlib.GameProfile;
import me.tianhangj.quickenchant.features.PlayerRandCracker;
import me.tianhangj.quickenchant.util.MultiVersionCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LocalPlayer 注入（本文件是 26.3 形状）。
 *
 * <p>26.3 删除了 {@code LocalPlayer.drop(boolean)}：客户端丢物入口整体搬到
 * {@code MultiPlayerGameMode.dropItem(LocalPlayer, boolean)}，
 * 因此“丢物”的 RNG 捕获转移到 {@code MultiPlayerGameModeMixin}。
 * 本类只保留与版本无关的肩膀鹦鹉检测。
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin extends AbstractClientPlayer {

    public LocalPlayerMixin(ClientLevel level, GameProfile profile) {
        super(level, profile);
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if (MultiVersionCompat.INSTANCE.getProtocolVersion() >= MultiVersionCompat.V1_21_9) {
            if (this.getShoulderParrotLeft().isPresent() || this.getShoulderParrotRight().isPresent()) {
                PlayerRandCracker.resetCracker(PlayerRandCracker.RNGCallType.SHOULDER_PARROT);
            }
        }
    }
}
