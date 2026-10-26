package me.tianhangj.quickenchant.mixin.events;

import me.tianhangj.quickenchant.event.ClientConnectionEvents;
import me.tianhangj.quickenchant.event.ClientLevelEvents;
import me.tianhangj.quickenchant.event.MoreScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 主类注入（本文件是 26.1.2 / 26.2 形状）。
 *
 * <p>26.3 存在同名覆盖版本（src/versioned/26.3/java/...），因为
 * {@code Minecraft.setScreen} 在 26.2 被删除、屏幕状态搬到 {@code Gui}。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Unique
    private boolean isLevelLoaded = false;

    @Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At("HEAD"))
    public void onUpdateLevelInEngines(@Nullable ClientLevel level, boolean bl, CallbackInfo ci) {
        if (isLevelLoaded) {
            ClientLevelEvents.UNLOAD_LEVEL.invoker().onUnloadLevel(level == null);
        }
        isLevelLoaded = level != null;
        if (isLevelLoaded) {
            ClientLevelEvents.LOAD_LEVEL.invoker().onLoadLevel(level);
        }
    }

    // 26.1.2 / 26.2：屏幕切换的唯一入口是 Minecraft.setScreen，要拦"打开屏幕"必须注这里。
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    public void onOpenScreen(@Nullable Screen screen, CallbackInfo ci) {
        if (!MoreScreenEvents.BEFORE_ADD.invoker().beforeScreenAdd(screen)) {
            ci.cancel();
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("RETURN"))
    public void onDisconnect(CallbackInfo ci) {
        ClientConnectionEvents.DISCONNECT.invoker().onDisconnect();
    }
}
