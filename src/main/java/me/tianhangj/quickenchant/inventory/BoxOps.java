package me.tianhangj.quickenchant.inventory;

import net.kyrptonaught.quickshulker.client.ClientUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Container interaction helpers for shulker boxes inside the player inventory, driven by the
 * QuickShulker mod (the open request is server-authoritative). All operations are stepwise:
 * they send a click/packet and the automation engine polls for the resulting state.
 */
public final class BoxOps {
    private BoxOps() {
    }

    public static boolean isShulkerMenuOpen(LocalPlayer player) {
        return player.containerMenu instanceof ShulkerBoxMenu;
    }

    /**
     * Send the QuickShulker open request for the box at the given player-inventory slot.
     * Must be called while no other container screen is open (containerMenu == inventoryMenu).
     */
    public static boolean openBox(LocalPlayer player, int playerInvSlot) {
        if (player.containerMenu != player.inventoryMenu) {
            return false;
        }
        for (Slot slot : player.inventoryMenu.slots) {
            if (slot.container == player.getInventory() && slot.getContainerSlot() == playerInvSlot) {
                ItemStack stack = slot.getItem();
                if (InventoryScanner.isShulkerBox(stack)) {
                    return ClientUtil.CheckAndSend(stack, slot.index);
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Shift-click (quick move) a slot in the currently open menu.
     */
    public static void quickMove(AbstractContainerMenu menu, int menuSlotIndex, LocalPlayer player) {
        Minecraft.getInstance().gameMode.handleContainerInput(menu.containerId, menuSlotIndex, 0, ContainerInput.QUICK_MOVE, player);
    }

    /**
     * Map a player-inventory slot index to its slot index within the given menu, or null.
     */
    @Nullable
    public static Integer findInventorySlotInMenu(AbstractContainerMenu menu, LocalPlayer player, int playerInvSlot) {
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory() && slot.getContainerSlot() == playerInvSlot) {
                return slot.index;
            }
        }
        return null;
    }

    /**
     * Close the currently open container (if any). The cursor must be empty; closing with a
     * carried stack would drop it and consume RNG.
     */
    public static void close(LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    public static boolean isClosed(LocalPlayer player) {
        return player.containerMenu == player.inventoryMenu;
    }
}
