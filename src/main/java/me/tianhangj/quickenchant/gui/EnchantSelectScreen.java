package me.tianhangj.quickenchant.gui;

import me.tianhangj.quickenchant.automation.EnchantAutomation;
import me.tianhangj.quickenchant.command.ClientCommandHelper;
import me.tianhangj.quickenchant.inventory.InventoryScanner;
import me.tianhangj.quickenchant.predicate.EnchantPredicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The enchantment selection screen. Top: item type selector (identical items merged and shown as
 * "name*count"). Then the "exactly" toggle and a list of enchantment/level rows, mirroring
 * clientcommands' /cenchant predicate semantics.
 */
public class EnchantSelectScreen extends Screen {
    private static final int ANY_LEVEL = EnchantPredicate.Row.ANY_LEVEL;

    private static class RowDraft {
        EnchantPredicate.Mode mode = EnchantPredicate.Mode.WITH;
        @Nullable Holder<Enchantment> enchantment = null;
        int level = ANY_LEVEL;
    }

    private static class TypeState {
        boolean exactly = false;
        final List<RowDraft> rows = new ArrayList<>();
    }

    private record TypeEntry(Item item, int count) {
    }

    private final List<TypeEntry> types = new ArrayList<>();
    private final Map<Item, TypeState> states = new HashMap<>();
    private final @Nullable Screen parent;
    private int selected = 0;

    public EnchantSelectScreen(InventoryScanner.Snapshot snapshot, @Nullable Screen parent) {
        super(Component.translatable("quick-enchant.gui.title"));
        this.parent = parent;
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (InventoryScanner.BoxInfo box : snapshot.itemBoxes()) {
            for (ItemStack stack : box.contents()) {
                if (InventoryScanner.isEnchantableCandidate(stack)) {
                    counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }
        counts.forEach((item, count) -> {
            types.add(new TypeEntry(item, count));
            states.put(item, new TypeState());
        });
    }

    private TypeState state() {
        return states.get(types.get(selected).item());
    }

    private List<Holder<Enchantment>> enchantmentOptions(Item item) {
        List<Holder<Enchantment>> options = new ArrayList<>();
        if (minecraft == null || minecraft.player == null) {
            return options;
        }
        var registry = minecraft.player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Iterable<Holder<Enchantment>> tag = registry.getTagOrEmpty(EnchantmentTags.IN_ENCHANTING_TABLE);
        ItemStack stack = new ItemStack(item);
        for (Holder<Enchantment> ench : tag) {
            if (ench.value().isPrimaryItem(stack) || stack.is(Items.BOOK)) {
                options.add(ench);
            }
        }
        return options;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 30;

        // item type selector (merged, "name*count")
        addRenderableWidget(Button.builder(typeLabel(), b -> {
            selected = (selected + 1) % types.size();
            rebuildWidgets();
        }).bounds(centerX - 100, y, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            selected = (selected + types.size() - 1) % types.size();
            rebuildWidgets();
        }).bounds(centerX - 124, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            selected = (selected + 1) % types.size();
            rebuildWidgets();
        }).bounds(centerX + 104, y, 20, 20).build());
        y += 26;

        // exactly toggle
        TypeState state = state();
        addRenderableWidget(Button.builder(exactlyLabel(state), b -> {
            state.exactly = !state.exactly;
            b.setMessage(exactlyLabel(state));
        }).bounds(centerX - 100, y, 200, 20).build());
        y += 26;

        // enchantment rows
        Item item = types.get(selected).item();
        List<Holder<Enchantment>> options = enchantmentOptions(item);
        int rowIndex = 0;
        for (RowDraft row : state.rows) {
            int rowY = y + rowIndex * 24;
            if (rowY > this.height - 64) {
                break; // simple layout without scrolling for now
            }
            final int idx = rowIndex;
            addRenderableWidget(Button.builder(modeLabel(row), b -> {
                row.mode = row.mode == EnchantPredicate.Mode.WITH ? EnchantPredicate.Mode.WITHOUT : EnchantPredicate.Mode.WITH;
                rebuildWidgets();
            }).bounds(centerX - 100, rowY, 50, 20).build());
            addRenderableWidget(Button.builder(enchLabel(row), b -> {
                if (!options.isEmpty()) {
                    if (row.enchantment == null) {
                        row.enchantment = options.getFirst();
                    } else {
                        int next = (options.indexOf(row.enchantment) + 1) % options.size();
                        row.enchantment = options.get(next);
                    }
                    row.level = ANY_LEVEL;
                    rebuildWidgets();
                }
            }).bounds(centerX - 46, rowY, 110, 20).build());
            addRenderableWidget(Button.builder(levelLabel(row), b -> {
                if (row.enchantment != null) {
                    int max = row.enchantment.value().getMaxLevel();
                    row.level = row.level >= max ? ANY_LEVEL : row.level + 1;
                    rebuildWidgets();
                }
            }).bounds(centerX + 68, rowY, 40, 20).build());
            addRenderableWidget(Button.builder(Component.literal("x"), b -> {
                state.rows.remove(idx);
                rebuildWidgets();
            }).bounds(centerX + 112, rowY, 20, 20).build());
            rowIndex++;
        }

        // add row
        addRenderableWidget(Button.builder(Component.translatable("quick-enchant.gui.addRow"), b -> {
            List<Holder<Enchantment>> opts = enchantmentOptions(item);
            if (!opts.isEmpty()) {
                RowDraft row = new RowDraft();
                row.enchantment = opts.getFirst();
                state.rows.add(row);
                rebuildWidgets();
            }
        }).bounds(centerX - 100, this.height - 52, 80, 20).build());

        // confirm (arm; execution starts on the configured confirm hotkey)
        addRenderableWidget(Button.builder(Component.translatable("quick-enchant.gui.confirm"), b -> confirm()).bounds(centerX - 16, this.height - 52, 100, 20).build());
        // cancel
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(centerX + 88, this.height - 52, 60, 20).build());
    }

    private void confirm() {
        List<EnchantAutomation.TypeConfig> configs = new ArrayList<>();
        for (TypeEntry entry : types) {
            TypeState ts = states.get(entry.item());
            if (ts.rows.isEmpty()) {
                continue;
            }
            EnchantPredicate predicate = new EnchantPredicate();
            predicate.setExactly(ts.exactly);
            for (RowDraft row : ts.rows) {
                if (row.enchantment != null) {
                    predicate.addRow(new EnchantPredicate.Row(row.mode, row.enchantment, row.level));
                }
            }
            if (!predicate.isEmpty()) {
                configs.add(new EnchantAutomation.TypeConfig(entry.item(), predicate));
            }
        }
        if (configs.isEmpty()) {
            return;
        }
        EnchantAutomation.setPending(configs);
        String hotkey = me.tianhangj.quickenchant.config.ModConfig.Generic.CONFIRM_EXECUTE.getKeybind().getKeysDisplayString();
        ClientCommandHelper.sendFeedback(Component.translatable("quick-enchant.gui.armed", hotkey).withStyle(ChatFormatting.YELLOW));
        onClose();
    }

    private Component typeLabel() {
        TypeEntry entry = types.get(selected);
        return entry.item().getName(new ItemStack(entry.item())).copy().append("*" + entry.count());
    }

    private Component exactlyLabel(TypeState state) {
        return Component.translatable("quick-enchant.gui.exactly", state.exactly
                ? Component.translatable("options.on").withStyle(ChatFormatting.GREEN)
                : Component.translatable("options.off").withStyle(ChatFormatting.RED));
    }

    private Component modeLabel(RowDraft row) {
        return row.mode == EnchantPredicate.Mode.WITH
                ? Component.translatable("quick-enchant.gui.mode.with")
                : Component.translatable("quick-enchant.gui.mode.without");
    }

    private Component enchLabel(RowDraft row) {
        if (row.enchantment == null) {
            return Component.literal("-");
        }
        return row.enchantment.value().description();
    }

    private Component levelLabel(RowDraft row) {
        return row.level == ANY_LEVEL ? Component.translatable("quick-enchant.gui.level.any") : Component.literal(String.valueOf(row.level));
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
