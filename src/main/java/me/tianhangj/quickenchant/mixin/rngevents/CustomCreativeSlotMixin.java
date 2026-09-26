package me.tianhangj.quickenchant.mixin.rngevents;

import me.tianhangj.quickenchant.interfaces.ICreativeSlot;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$CustomCreativeSlot")
public class CustomCreativeSlotMixin implements ICreativeSlot {
}
