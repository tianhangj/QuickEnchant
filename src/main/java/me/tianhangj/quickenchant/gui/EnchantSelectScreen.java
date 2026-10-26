package me.tianhangj.quickenchant.gui;

import me.tianhangj.quickenchant.Configs;
import me.tianhangj.quickenchant.automation.EnchantAutomation;
import me.tianhangj.quickenchant.automation.PrepareFlow;
import me.tianhangj.quickenchant.automation.SeedChain;
import me.tianhangj.quickenchant.features.EnchantmentCracker;
import me.tianhangj.quickenchant.command.ClientCommandHelper;
import me.tianhangj.quickenchant.config.FlowState;
import me.tianhangj.quickenchant.inventory.InventoryScanner;
import me.tianhangj.quickenchant.predicate.EnchantPredicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 * Step 2 of the prepare flow: per-item-type enchantment selection. Identical items are merged
 * ("name*count"); selections are prefilled from the persisted per-item-id config so the same
 * item id keeps its previously chosen parameters.
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
    private final List<Integer> selectedSlots;
    private boolean typesBuilt = false;
    private int selected = 0;
    private boolean infeasible = false;

    /** upper bound of the feasibility sweep (throws from a random starting seed) */
    private static final int MAX_FEASIBILITY_THROWS = 1024;

    public EnchantSelectScreen(List<Integer> selectedSlots, @Nullable Screen parent) {
        super(Component.translatable("quick-enchant.flow.enchantTitle"));
        this.parent = parent;
        this.selectedSlots = selectedSlots;
    }

    /** Built on first init: this.minecraft is only injected when the screen is initialized. */
    private void buildTypes() {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        if (this.minecraft != null && this.minecraft.player != null) {
            for (int invSlot : selectedSlots) {
                ItemStack boxStack = this.minecraft.player.getInventory().getItem(invSlot);
                if (!InventoryScanner.isShulkerBox(boxStack)) {
                    continue;
                }
                for (ItemStack stack : InventoryScanner.readBoxContents(boxStack)) {
                    if (InventoryScanner.isEnchantableCandidate(stack)) {
                        counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    }
                }
            }
        }
        counts.forEach((item, count) -> {
            types.add(new TypeEntry(item, count));
            states.put(item, prefill(item));
        });
    }

    /** Prefill this item's state from the persisted per-id config, if any. */
    private TypeState prefill(Item item) {
        TypeState state = new TypeState();
        FlowState.StoredEnchantConfig stored = FlowState.enchantConfigs.get(idOf(item));
        if (stored == null || this.minecraft == null || this.minecraft.player == null) {
            return state;
        }
        state.exactly = stored.exactly();
        var registry = this.minecraft.player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (FlowState.StoredRow row : stored.rows()) {
            RowDraft draft = new RowDraft();
            try {
                draft.mode = "WITHOUT".equals(row.mode()) ? EnchantPredicate.Mode.WITHOUT : EnchantPredicate.Mode.WITH;
                Identifier enchId = Identifier.parse(row.ench());
                registry.get(enchId).ifPresent(ref -> draft.enchantment = ref);
                draft.level = row.level();
            } catch (Exception ignored) {
                draft.enchantment = null;
            }
            if (draft.enchantment != null) {
                state.rows.add(draft);
            }
        }
        return state;
    }

    private static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
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
        if (!typesBuilt) {
            typesBuilt = true;
            buildTypes();
        }
        infeasible = computeInfeasible(types.get(selected).item());
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

        // confirm (persist + advance the flow to the crack step)
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
                // persist for the same item id next time
                List<FlowState.StoredRow> storedRows = new ArrayList<>();
                for (RowDraft row : ts.rows) {
                    if (row.enchantment != null) {
                        storedRows.add(new FlowState.StoredRow(
                                row.mode.name(),
                                row.enchantment.unwrapKey()
                                        .map(key -> key.identifier().toString())
                                        .orElse(""),
                                row.level));
                    }
                }
                FlowState.enchantConfigs.put(idOf(entry.item()),
                        new FlowState.StoredEnchantConfig(ts.exactly, storedRows));
            }
        }
        if (configs.isEmpty()) {
            return;
        }
        FlowState.save();
        PrepareFlow.confirmEnchants(configs);
        ClientCommandHelper.sendFeedback(Component.translatable("quick-enchant.flow.enchantsConfirmed")
                .withStyle(ChatFormatting.YELLOW));
        onClose();
    }

    /**
     * Live feasibility probe: take a random player starting seed and sweep the standard
     * manipulation (n throws advance the RNG, the dummy enchant writes the next nextInt into the
     * table) - if none of the resulting table seeds offers the configured enchantments, the
     * target is most likely unobtainable and a red warning is shown.
     */
    private boolean computeInfeasible(Item item) {
        TypeState ts = states.get(item);
        if (ts == null || ts.rows.isEmpty() || this.minecraft == null || this.minecraft.player == null) {
            return false;
        }
        EnchantPredicate predicate = new EnchantPredicate();
        predicate.setExactly(ts.exactly);
        for (RowDraft row : ts.rows) {
            if (row.enchantment != null) {
                predicate.addRow(new EnchantPredicate.Row(row.mode, row.enchantment, row.level));
            }
        }
        if (predicate.isEmpty()) {
            return false;
        }

        var registry = this.minecraft.player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        ItemStack stack = new ItemStack(item);
        int bMin = 0;
        int bMax = 15;
        if (!me.tianhangj.quickenchant.config.ModConfig.Generic.LECTERN_ADJUSTMENT.getBooleanValue()) {
            bMin = 15;
            bMax = 15;
        }

        long startSeed = new java.util.Random().nextLong();
        // The seed the dummy enchant writes after n throws is derived from startSeed every time;
        // stepping a shared Rand here would drift by one draw per candidate (the probe itself moves
        // the Rand), making the preview disagree with the solver and with the game.
        final int shelvesMin = bMin;
        final int shelvesMax = bMax;
        int found = SeedChain.findThrows(startSeed, 0L, MAX_FEASIBILITY_THROWS, candidate -> {
            for (int b = shelvesMin; b <= shelvesMax; b++) {
                for (int slot = 0; slot < 3; slot++) {
                    var list = EnchantmentCracker.simulateSlotList(registry, candidate, slot, b, stack);
                    if (list.isEmpty()) {
                        continue;
                    }
                    int cost = EnchantmentCracker.simulateSlotCost(candidate, slot, b, stack);
                    if (cost < Configs.getMinEnchantLevels() || cost > Configs.getMaxEnchantLevels()) {
                        continue;
                    }
                    if (predicate.test(list)) {
                        return true; // feasible
                    }
                }
            }
            return false;
        });
        return found < 0;
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
        if (infeasible) {
            graphics.centeredText(this.font, Component.translatable("quick-enchant.flow.infeasible")
                    .withStyle(ChatFormatting.RED), this.width / 2, this.height - 60, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            me.tianhangj.quickenchant.util.Compat.setScreen(this.minecraft, parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
