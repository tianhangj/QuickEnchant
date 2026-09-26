package me.tianhangj.quickenchant.inventory;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import me.tianhangj.quickenchant.config.ModConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side inventory scan. Shulker box contents are read directly from the item's
 * data components, so no container needs to be opened for classification.
 */
public class InventoryScanner {
    public enum BoxKind {
        ITEMS,
        LAPIS,
        OTHER
    }

    public record BoxInfo(int invSlot, ItemStack boxStack, List<ItemStack> contents, BoxKind kind) {
    }

    public record WhitelistStack(int invSlot, ItemStack stack) {
    }

    public record Snapshot(List<BoxInfo> itemBoxes, List<BoxInfo> lapisBoxes, List<WhitelistStack> whitelistStacks,
                           Map<ItemStack, Integer> enchantableCounts) {
    }

    public static boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    public static List<ItemStack> readBoxContents(ItemStack boxStack) {
        ItemContainerContents contents = boxStack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return List.of();
        }
        List<ItemStack> items = new ArrayList<>();
        contents.nonEmptyItemCopyStream().forEach(items::add);
        return items;
    }

    public static boolean isEnchantableCandidate(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.ENCHANTABLE) && !EnchantmentHelper.hasAnyEnchantments(stack);
    }

    public static boolean isLapis(ItemStack stack) {
        return !stack.isEmpty() && stack.is(net.minecraft.world.item.Items.LAPIS_LAZULI);
    }

    private static BoxKind classify(List<ItemStack> contents) {
        // A box counts as an item box while it holds at least one enchantable, not-yet-enchanted
        // stack: after each enchant the enchanted copy is returned to the same box, so real boxes
        // are mixed and must not be classified by an all-or-nothing rule.
        boolean anyLapis = false;
        boolean anyEnchantable = false;
        for (ItemStack stack : contents) {
            if (isLapis(stack)) {
                anyLapis = true;
            } else if (isEnchantableCandidate(stack)) {
                anyEnchantable = true;
            }
        }
        if (anyEnchantable) {
            return BoxKind.ITEMS;
        }
        return anyLapis ? BoxKind.LAPIS : BoxKind.OTHER;
    }

    public static List<Identifier> getWhitelist() {
        List<Identifier> ids = new ArrayList<>();
        for (String entry : ModConfig.Generic.THROW_WHITELIST.getStrings()) {
            try {
                ids.add(Identifier.parse(entry.trim()));
            } catch (Exception ignored) {
            }
        }
        return ids;
    }

    public static boolean isWhitelisted(ItemStack stack, List<Identifier> whitelist) {
        if (stack.isEmpty()) {
            return false;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return whitelist.contains(id);
    }

    public static Snapshot scan(LocalPlayer player) {
        List<BoxInfo> itemBoxes = new ArrayList<>();
        List<BoxInfo> lapisBoxes = new ArrayList<>();
        List<WhitelistStack> whitelistStacks = new ArrayList<>();
        Map<ItemStack, Integer> enchantableCounts = new HashMap<>();
        List<Identifier> whitelist = getWhitelist();

        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            if (isShulkerBox(stack)) {
                List<ItemStack> contents = readBoxContents(stack);
                BoxKind kind = classify(contents);
                switch (kind) {
                    case ITEMS -> itemBoxes.add(new BoxInfo(slot, stack, contents, kind));
                    case LAPIS -> lapisBoxes.add(new BoxInfo(slot, stack, contents, kind));
                    case OTHER -> {
                    }
                }
            } else if (isWhitelisted(stack, whitelist)) {
                whitelistStacks.add(new WhitelistStack(slot, stack));
            }
        }

        // merged enchantable item counts across all item boxes (GUI shows "name*count")
        for (BoxInfo box : itemBoxes) {
            for (ItemStack stack : box.contents()) {
                if (isEnchantableCandidate(stack)) {
                    enchantableCounts.merge(stack.copyWithCount(1), stack.getCount(), Integer::sum);
                }
            }
        }

        return new Snapshot(itemBoxes, lapisBoxes, whitelistStacks, enchantableCounts);
    }
}
