package me.tianhangj.quickenchant.mixin.rngevents.droppableinventory;

import me.tianhangj.quickenchant.interfaces.IDroppableInventoryContainer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.StonecutterMenu;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(StonecutterMenu.class)
public class StonecutterMenuMixin implements IDroppableInventoryContainer {

    @Override
    public Container getDroppableInventory() {
        return ((StonecutterMenu) (Object) this).container;
    }
}
