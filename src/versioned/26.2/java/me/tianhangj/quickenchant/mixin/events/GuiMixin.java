package me.tianhangj.quickenchant.mixin.events;

import me.tianhangj.quickenchant.event.MoreScreenEvents;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2+ 的“打开屏幕”拦截点。
 *
 * <p>26.2 起 {@code Minecraft.setScreen} 被删除，屏幕状态搬到 {@code Gui}：
 * <ul>
 *   <li>{@code Gui.setScreen} 是唯一实际入口（{@code Minecraft.setScreenAndShow} 内部调它）。</li>
 *   <li>因此在这里拦截，覆盖 setScreen 与 setScreenAndShow 两条调用路径。</li>
 * </ul>
 * 本文件只存在于 src/versioned/26.3/，仅 26.3 构建会编入。
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    public void onOpenScreen(@Nullable Screen screen, CallbackInfo ci) {
        if (!MoreScreenEvents.BEFORE_ADD.invoker().beforeScreenAdd(screen)) {
            ci.cancel();
        }
    }
}
