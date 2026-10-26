package me.tianhangj.quickenchant.gui;

import me.tianhangj.quickenchant.QuickEnchant;
import me.tianhangj.quickenchant.automation.PrepareFlow;
import me.tianhangj.quickenchant.inventory.InventoryScanner;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Step 1 of the prepare flow: an inventory-like screen where the player marks the shulker boxes
 * that hold the items to enchant. Shift+left-click toggles the mark; boxes without enchantable
 * (not yet enchanted) items cannot be marked. Confirm below the hotbar.
 */
public class BoxSelectScreen extends Screen {
    private static final int MARKED_FILL = 0x3000E000;
    private static final int MARKED_BORDER = 0xFF00E000;
    private static final int SLOT_BG = 0x8B8B8B8B;
    private static final int SLOT_BORDER = 0xFF373737;
    private static final int GRID_HOVER = 0x30FFFFFF;

    private record SlotRect(int invSlot, int x, int y) {
    }

    private final List<SlotRect> slotRects = new ArrayList<>();
    private final Set<Integer> marked = new HashSet<>();
    private final boolean inherited;
    private int previewInvSlot = -1;

    public BoxSelectScreen(boolean inherited) {
        super(Component.translatable("quick-enchant.flow.boxTitle"));
        this.inherited = inherited;
        marked.addAll(me.tianhangj.quickenchant.config.FlowState.selectedBoxes);
    }

    @Override
    protected void init() {
        QuickEnchant.LOGGER.info("BoxSelectScreen init: inherited={}, marked={}", inherited, marked.size());
        slotRects.clear();
        // main inventory 3x9 (container slots 9-35)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slotRects.add(new SlotRect(9 + row * 9 + col, 8 + col * 18, 84 + row * 18));
            }
        }
        // hotbar (container slots 0-8)
        for (int col = 0; col < 9; col++) {
            slotRects.add(new SlotRect(col, 8 + col * 18, 142));
        }
        // offhand (container slot 40)
        slotRects.add(new SlotRect(40, 77, 62));

        int centerX = this.width / 2;
        addRenderableWidget(Button.builder(Component.translatable("quick-enchant.flow.confirmBoxes"), b -> confirm())
                .bounds(centerX - 60, 170, 120, 20).build());
    }

    private static boolean isValidItemBox(ItemStack stack) {
        if (!InventoryScanner.isShulkerBox(stack)) {
            return false;
        }
        return InventoryScanner.readBoxContents(stack).stream().anyMatch(InventoryScanner::isEnchantableCandidate);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            for (SlotRect rect : slotRects) {
                if (event.x() >= rect.x() && event.x() < rect.x() + 16 && event.y() >= rect.y() && event.y() < rect.y() + 16) {
                    ItemStack stack = this.minecraft.player.getInventory().getItem(rect.invSlot());
                    if (event.hasShiftDown()) {
                        if (isValidItemBox(stack)) {
                            if (!marked.remove(rect.invSlot())) {
                                marked.add(rect.invSlot());
                            }
                        }
                    } else if (InventoryScanner.isShulkerBox(stack)) {
                        // plain left-click: toggle the right-side content preview
                        previewInvSlot = previewInvSlot == rect.invSlot() ? -1 : rect.invSlot();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void confirm() {
        if (marked.isEmpty()) {
            return;
        }
        List<Integer> slots = new ArrayList<>(marked);
        slots.sort(Integer::compareTo);
        me.tianhangj.quickenchant.config.FlowState.selectedBoxes.clear();
        me.tianhangj.quickenchant.config.FlowState.selectedBoxes.addAll(slots);
        me.tianhangj.quickenchant.config.FlowState.save();
        PrepareFlow.confirmBoxes(slots);
        me.tianhangj.quickenchant.util.Compat.setScreen(this.minecraft, new EnchantSelectScreen(slots, null));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // dim backdrop for the grid area
        graphics.fill(0, 76, this.width, 168, 0x60000000);

        graphics.centeredText(this.font, this.title, this.width / 2, 60, 0xFFFFFF);
        graphics.centeredText(this.font, Component.translatable("quick-enchant.flow.boxHint"), this.width / 2, 72, 0xFFA0A0A0);

        this.hoveredSlot = -1;
        for (SlotRect rect : slotRects) {
            ItemStack stack = this.minecraft.player.getInventory().getItem(rect.invSlot());
            boolean hovered = mouseX >= rect.x() && mouseX < rect.x() + 16 && mouseY >= rect.y() && mouseY < rect.y() + 16;

            graphics.fill(rect.x() - 1, rect.y() - 1, rect.x() + 17, rect.y() + 17, SLOT_BORDER);
            graphics.fill(rect.x(), rect.y(), rect.x() + 16, rect.y() + 16, SLOT_BG);
            if (!stack.isEmpty()) {
                graphics.item(stack, rect.x(), rect.y());
            }

            boolean valid = isValidItemBox(stack);
            if (marked.contains(rect.invSlot())) {
                graphics.fill(rect.x(), rect.y(), rect.x() + 16, rect.y() + 16, MARKED_FILL);
                // green border
                graphics.fill(rect.x() - 1, rect.y() - 1, rect.x() + 17, rect.y(), MARKED_BORDER);
                graphics.fill(rect.x() - 1, rect.y() + 16, rect.x() + 17, rect.y() + 17, MARKED_BORDER);
                graphics.fill(rect.x() - 1, rect.y(), rect.x(), rect.y() + 16, MARKED_BORDER);
                graphics.fill(rect.x() + 16, rect.y(), rect.x() + 17, rect.y() + 16, MARKED_BORDER);
            } else if (hovered && valid) {
                graphics.fill(rect.x(), rect.y(), rect.x() + 16, rect.y() + 16, GRID_HOVER);
            }

            if (hovered) {
                this.hoveredSlot = rect.invSlot();
            }
        }

        // right-side content preview of the left-clicked box
        if (previewInvSlot >= 0 && this.minecraft.player != null) {
            ItemStack previewStack = this.minecraft.player.getInventory().getItem(previewInvSlot);
            if (!InventoryScanner.isShulkerBox(previewStack)) {
                previewInvSlot = -1;
            } else {
                List<ItemStack> contents = InventoryScanner.readBoxContents(previewStack);
                int px = this.width - 178;
                int py = 66;
                graphics.fill(px - 6, py - 14, px + 170, py + 96, 0xC0101010);
                graphics.text(this.font, previewStack.getHoverName(), px, py - 11, 0xFFFFFF, true);
                int valid = 0;
                for (int i = 0; i < contents.size() && i < 27; i++) {
                    ItemStack content = contents.get(i);
                    int cx = px + (i % 9) * 18;
                    int cy = py + (i / 9) * 18;
                    graphics.fill(cx - 1, cy - 1, cx + 17, cy + 17, SLOT_BORDER);
                    graphics.fill(cx, cy, cx + 16, cy + 16, SLOT_BG);
                    graphics.item(content, cx, cy);
                    if (InventoryScanner.isEnchantableCandidate(content)) {
                        valid++;
                    } else {
                        // ignored: not enchantable or already enchanted
                        graphics.fill(cx, cy, cx + 16, cy + 16, 0x70A00000);
                    }
                }
                graphics.text(this.font, Component.translatable("quick-enchant.flow.previewValid", valid),
                        px, py + 58, 0xFF80FF80, true);
            }
        }

        // hovered box summary
        if (this.hoveredSlot >= 0 && this.minecraft.player != null) {
            ItemStack stack = this.minecraft.player.getInventory().getItem(this.hoveredSlot);
            if (InventoryScanner.isShulkerBox(stack)) {
                var counts = new java.util.LinkedHashMap<ItemStack, Integer>();
                for (ItemStack content : InventoryScanner.readBoxContents(stack)) {
                    if (InventoryScanner.isEnchantableCandidate(content)) {
                        counts.merge(content.copyWithCount(1), content.getCount(), Integer::sum);
                    }
                }
                if (!counts.isEmpty()) {
                    Component summary = Component.empty();
                    boolean first = true;
                    for (var entry : counts.entrySet()) {
                        if (!first) {
                            summary = summary.copy().append(", ");
                        }
                        summary = summary.copy()
                                .append(entry.getKey().getItem().getName(new ItemStack(entry.getKey().getItem())))
                                .append("*" + entry.getValue());
                        first = false;
                    }
                    graphics.setTooltipForNextFrame(summary, mouseX, mouseY);
                }
            }
        }
    }

    private int hoveredSlot = -1;

    @Override
    public void onClose() {
        // closing without confirming simply cancels step 1; the HUD keeps hinting
        if (this.minecraft != null) {
            me.tianhangj.quickenchant.util.Compat.setScreen(this.minecraft, null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
