package me.tianhangj.quickenchant.mixin.rngevents.droppableinventory;

import me.tianhangj.quickenchant.interfaces.IDroppableInventoryContainer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuMixin implements IDroppableInventoryContainer {

    @Shadow @Final private Container enchantSlots;

    @Override
    public Container getDroppableInventory() {
        return enchantSlots;
    }
}
