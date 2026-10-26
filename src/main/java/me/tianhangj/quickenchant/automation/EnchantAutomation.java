package me.tianhangj.quickenchant.automation;

import me.tianhangj.quickenchant.Configs;
import me.tianhangj.quickenchant.QuickEnchant;
import me.tianhangj.quickenchant.command.ClientCommandHelper;
import me.tianhangj.quickenchant.config.ModConfig;
import me.tianhangj.quickenchant.features.EnchantmentCracker;
import me.tianhangj.quickenchant.features.PlayerRandCracker;
import me.tianhangj.quickenchant.features.SuggestionsHook;
import me.tianhangj.quickenchant.inventory.BoxOps;
import me.tianhangj.quickenchant.inventory.InventoryScanner;
import me.tianhangj.quickenchant.predicate.EnchantPredicate;
import me.tianhangj.quickenchant.task.ItemThrowTask;
import me.tianhangj.quickenchant.task.LongTask;
import me.tianhangj.quickenchant.task.TaskManager;
import me.tianhangj.quickenchant.util.QEDebug;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The automation engine: one LongTask driving a per-item state machine.
 * Per item: solve the chain step, take the item out of its box, throw whitelist items to advance
 * the RNG, adjust the lectern bookshelf count, enchant with in-table verification, and return the
 * item to its original box.
 *
 * <p>Only the FIRST entry of a run uses a dummy enchant: at that point no previous enchant exists,
 * so something has to write the seed the first item is enchanted with. Its plan places the dummy,
 * enchants it, drops the enchanted copy (4 player-RNG steps), then throws the window throws inside
 * the open table so this item's own enchant writes the NEXT entry's seed. From the second entry on
 * the previous enchant already did that job, so every later entry is a pure chain step without a
 * dummy - a second dummy would be a wasted table operation.
 *
 * <p>If a later entry's seed offers nothing for it, the chain is broken: throwing cannot change the
 * seed the table already holds, so the run stops instead of skipping ahead.
 */
public class EnchantAutomation extends LongTask {
    private static final int WAIT_TIMEOUT_TICKS = 200;
    /** grace ticks before comparing synced menu state against predictions (server sync latency) */
    private static final int VERIFY_DELAY_TICKS = 6;
    /** grace ticks for a freshly opened menu's initial content sync before declaring a mismatch */
    private static final int MENU_CONTENT_GRACE_TICKS = 5;
    /**
     * Re-click pacing: container clicks are re-sent at most once per this many ticks. Flooding
     * clicks every tick makes the server answer each stale-stateId click with a full state
     * resync, which both wastes bandwidth and reverts the client's local prediction, delaying
     * the very change the phase is waiting for (observed as probabilistic PLACE_REAL timeouts
     * on dedicated servers).
     */
    private static final int CLICK_RETRY_TICKS = 40;
    private static final int LAPIS_RESERVE = 6;

    /** One configured enchantment request for a merged item type (from the selection GUI). */
    public record TypeConfig(Item item, EnchantPredicate predicate) {
    }

    private enum Phase {
        RESOLVE,
        FETCH_DUMMY_OPEN, FETCH_DUMMY_TAKE, FETCH_DUMMY_CLOSE,
        REFILL_LAPIS_OPEN, REFILL_LAPIS_TAKE, REFILL_LAPIS_CLOSE,
        OPEN_ITEM_BOX, TAKE_ITEM, CLOSE_ITEM_BOX,
        THROWING,
        BOOKSHELF_OPEN, BOOKSHELF_SET_PAGE, BOOKSHELF_WAIT_PAGE, BOOKSHELF_CLOSE, BOOKSHELF_WAIT_ACTUAL,
        TABLE_OPEN, PLACE_LAPIS, PLACE_FIRST, VERIFY_FIRST,
        DUMMY, DUMMY_VERIFY, TAKE_DUMMY_OUT, DROP_DUMMY, PLACE_REAL, VERIFY_SWAPPED, WINDOW_THROWING,
        ENCHANT, ENCHANT_WAIT, ENCHANT_VERIFY,
        TAKE_ITEM_OUT, TABLE_CLOSE,
        RETURN_OPEN, RETURN_PUT, RETURN_CLOSE,
        NEXT_ITEM,
        DONE
    }

    private Phase phase = Phase.RESOLVE;
    private int phaseTicks = 0;
    private int queueIndex = 0;
    private final List<TypeConfig> configs;
    private final List<Item> queue = new ArrayList<>();
    private final Set<Item> skippedItems = new HashSet<>();

    private final Item dummyItem;
    private int dummyInvSlot = -1;
    private int dummyBoxSlot = -1;

    private EnchantSolver.Plan plan;
    private int boxSlot = -1;
    private int itemInvSlot = -1;
    /**
     * True until the first entry of this run has been enchanted successfully. Only that entry may
     * use a dummy enchant (nothing has written a seed before it); every later entry relies on the
     * previous enchant having written its seed already.
     */
    private boolean firstEntry = true;
    private @Nullable ItemThrowTask throwTask;
    private final List<Integer> selectedSlots = new ArrayList<>();
    private boolean completedNormally = false;

    /**
     * Round-trip fence for container closes: the server processes ServerboundContainerClosePacket
     * asynchronously, and QuickShulker's open request indexes into the server's CURRENT container
     * menu - opening a box too soon after closing one would index into the stale menu and never
     * open anything.
     */
    @Nullable
    private CompletableFuture<Void> closeFence = null;
    private int closeFenceTicks = 0;

    private boolean cleanedUp = false;

    /** true while the dummy copy is being dropped - its DROP_ITEM call is intended and mirror-maintained */
    private boolean droppingDummy = false;

    private static @Nullable EnchantAutomation running = null;

    static {
        // the dummy drop has no ItemThrowTask listening, so the engine itself marks its DROP_ITEM
        // call as maintained (otherwise the player crack state would reset)
        PlayerRandCracker.RNG_CALLED_EVENT.register(EnchantAutomation::onSelfRngCall);
    }

    private static void onSelfRngCall(PlayerRandCracker.RNGCallEvent event) {
        EnchantAutomation automation = running;
        if (automation != null && automation.droppingDummy
                && event.getType() == PlayerRandCracker.RNGCallType.DROP_ITEM) {
            event.setMaintained();
        }
    }

    public static @Nullable EnchantAutomation getRunning() {
        return running;
    }

    /** /qestart: starts the automation with the boxes and enchantments confirmed in /qeprepare. */
    public static void beginRun(List<Integer> selectedSlots, List<TypeConfig> configs) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || running != null) {
            return;
        }
        if (Configs.enchCrackState != EnchantmentCracker.CrackState.CRACKED || !Configs.playerCrackState.knowsSeed()) {
            ClientCommandHelper.sendError(Component.translatable("quick-enchant.run.notCracked"));
            return;
        }
        if (EnchantmentCracker.enchantingTablePos == null) {
            ClientCommandHelper.sendError(Component.translatable("quick-enchant.run.noTable"));
            return;
        }

        EnchantAutomation automation = new EnchantAutomation(configs, selectedSlots);
        if (!automation.buildQueue(player)) {
            ClientCommandHelper.sendError(Component.translatable("quick-enchant.run.noItems"));
            return;
        }
        try {
            TaskManager.addTask("enchantAutomation", automation);
        } catch (Exception e) {
            ClientCommandHelper.sendError(Component.translatable("quick-enchant.run.conflict"));
            return;
        }
        EnchantmentCracker.attachAutomationTask(automation);
        running = automation;
        PrepareFlow.beginRun();

        // crash-safe: persist the run as "not ended normally" together with the box selection,
        // so the next /qeprepare inherits it unless this run finishes cleanly
        me.tianhangj.quickenchant.config.FlowState.lastRunNormalEnd = false;
        me.tianhangj.quickenchant.config.FlowState.selectedBoxes.clear();
        me.tianhangj.quickenchant.config.FlowState.selectedBoxes.addAll(selectedSlots);
        me.tianhangj.quickenchant.config.FlowState.save();

        ClientCommandHelper.sendFeedback(Component.translatable("quick-enchant.run.started", automation.queue.size()));
    }

    /** Flatten the configured types into a per-item execution queue, counting only the selected boxes. */
    private boolean buildQueue(LocalPlayer player) {
        for (TypeConfig config : configs) {
            int count = 0;
            for (int invSlot : selectedSlots) {
                ItemStack boxStack = player.getInventory().getItem(invSlot);
                if (!InventoryScanner.isShulkerBox(boxStack)) {
                    continue;
                }
                for (ItemStack stack : InventoryScanner.readBoxContents(boxStack)) {
                    if (stack.is(config.item()) && InventoryScanner.isEnchantableCandidate(stack)) {
                        count += stack.getCount();
                    }
                }
            }
            for (int i = 0; i < count; i++) {
                queue.add(config.item());
            }
        }
        return !queue.isEmpty();
    }

    public static void abort(String reasonKey) {
        abort(reasonKey, false);
    }

    /**
     * Abort for a run that has proven the seed model WRONG (an enchantment or a table offer did not
     * match the prediction).
     *
     * <p>The player seed and the table seed are a matched pair: the table seed is derived from the
     * player seed, so a mismatch means at least one of them is no longer the server's real value.
     * Keeping them would make the next run throw items to a position that is wrong in exactly the
     * same way - so both are discarded and the user has to re-crack before running again. Aborts
     * with an external cause (no lapis, box did not open, aborted by hotkey, ...) keep the seeds,
     * because the model is still correct there and re-cracking would be pure busywork.
     */
    public static void abortDesync(String reasonKey) {
        abort(reasonKey, true);
    }

    /** {@link #abortDesync(String)} with the offending item appended to the message. */
    public static void abortDesync(String reasonKey, Component detail) {
        abort(reasonKey, true, detail);
    }

    private static void abort(String reasonKey, boolean clearSeeds) {
        abort(reasonKey, clearSeeds, null);
    }

    private static void abort(String reasonKey, boolean clearSeeds, @Nullable Component detail) {
        EnchantAutomation automation = running;
        if (automation != null) {
            Component message = Component.translatable(reasonKey);
            if (detail != null) {
                message = message.copy().append(" ").append(detail);
            }
            message = message.copy().append(" ").append(Component.translatable("quick-enchant.run.phase", automation.phase));
            ClientCommandHelper.sendError(message);
            QuickEnchant.LOGGER.info("automation aborted: {} phase {}", reasonKey, automation.phase);
            QEDebug.dump("abort " + reasonKey + " in phase " + automation.phase,
                    "abort-" + automation.phase + "-" + System.currentTimeMillis() + ".txt");
            automation.cleanup();
            automation._break();
        }
        if (clearSeeds) {
            clearSeedState();
        }
    }

    /**
     * Drops the cracked table seed and player seed so the next run cannot inherit them. Runs after
     * cleanup so the abort message is already on screen, and prints one line saying why.
     */
    private static void clearSeedState() {
        QuickEnchant.LOGGER.info("clearing seed state after a prediction mismatch: {}", QEDebug.seedState());
        EnchantmentCracker.resetCracker();
        PlayerRandCracker.forgetSeed();
        ClientCommandHelper.sendError(Component.translatable("quick-enchant.run.seedsCleared"));
    }

    /** One-line phase/plan summary, used by the debug snapshot and {@code /qedbg state}. */
    public String debugSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("phase=").append(phase)
                .append(" phaseTicks=").append(phaseTicks)
                .append(" queue=").append(queueIndex + 1).append('/').append(queue.size())
                .append(" boxSlot=").append(boxSlot)
                .append(" dummyInvSlot=").append(dummyInvSlot)
                .append(" dummyBoxSlot=").append(dummyBoxSlot)
                .append(" firstEntry=").append(firstEntry)
                .append(" throwTask=").append(throwTask == null ? "none" : "active");
        if (plan != null) {
            sb.append("\nplan: ").append(QEDebug.plan(plan));
        }
        return sb.toString();
    }

    private EnchantAutomation(List<TypeConfig> configs, List<Integer> selectedSlots) {
        this.configs = configs;
        this.selectedSlots.addAll(selectedSlots);
        Item parsed = Items.AIR;
        try {
            parsed = BuiltInRegistries.ITEM.get(Identifier.parse(ModConfig.Generic.DUMMY_ITEM.getStringValue().trim()))
                    .map(net.minecraft.core.Holder::value).orElse(Items.AIR);
        } catch (Exception ignored) {
        }
        this.dummyItem = parsed;
    }

    @Override
    public void initialize() {
    }

    @Override
    public boolean condition() {
        return phase != Phase.DONE;
    }

    @Override
    public void increment() {
    }

    @Override
    public boolean stopOnLevelUnload(boolean isDisconnect) {
        return true;
    }

    @Override
    public void onCompleted() {
        // called after natural completion, abort(), or an unexpected-RNG-call break
        cleanup();
    }

    private void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            BoxOps.close(mc.player);
        }
        if (throwTask != null) {
            throwTask._break();
            throwTask.onCompleted();
            throwTask = null;
        }
        PlayerRandCracker.throwSlotFilter = null;
        EnchantmentCracker.setCurrentlyThrowingItems(false);
        EnchantmentCracker.attachAutomationTask(null);
        closeFence = null;
        running = null;
        phase = Phase.DONE;

        // the box selection is inherited by the next /qeprepare only when this run ended abnormally
        me.tianhangj.quickenchant.config.FlowState.lastRunNormalEnd = completedNormally;
        if (completedNormally) {
            me.tianhangj.quickenchant.config.FlowState.selectedBoxes.clear();
        }
        me.tianhangj.quickenchant.config.FlowState.save();
        PrepareFlow.endRun();
    }

    @Override
    public void body() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            cleanup();
            return;
        }

        // requirement: the player looks straight down during the whole process
        player.setXRot(90.0f);

        phaseTicks++;
        if (throwTask == null && phaseTicks > WAIT_TIMEOUT_TICKS) {
            if (phase == Phase.TAKE_ITEM || phase == Phase.REFILL_LAPIS_TAKE
                    || phase == Phase.FETCH_DUMMY_TAKE || phase == Phase.RETURN_PUT) {
                ClientCommandHelper.sendError(Component.translatable("quick-enchant.run.boxMenuTimeout"));
            }
            ClientCommandHelper.sendError(Component.translatable("quick-enchant.run.timeout", phase));
            cleanup();
            _break();
            return;
        }

        switch (phase) {
            case RESOLVE -> resolve(player);
            case FETCH_DUMMY_OPEN -> fetchDummyOpen(player);
            case FETCH_DUMMY_TAKE -> fetchDummyTake(player);
            case FETCH_DUMMY_CLOSE -> closeAndWait(player, afterPreparation(player));
            case REFILL_LAPIS_OPEN -> refillLapisOpen(player);
            case REFILL_LAPIS_TAKE -> refillLapisTake(player);
            case REFILL_LAPIS_CLOSE -> closeAndWait(player, afterPreparation(player));
            case OPEN_ITEM_BOX -> openItemBox(player);
            case TAKE_ITEM -> takeItem(player);
            case CLOSE_ITEM_BOX -> closeAndWait(player, Phase.THROWING);
            case THROWING -> doThrowing(player, plan == null ? 0 : plan.itemThrows(), Phase.BOOKSHELF_OPEN);
            case BOOKSHELF_OPEN -> bookshelfOpen(player);
            case BOOKSHELF_SET_PAGE -> bookshelfSetPage(player);
            case BOOKSHELF_WAIT_PAGE -> bookshelfWaitPage(player);
            case BOOKSHELF_CLOSE -> closeAndWait(player, Phase.BOOKSHELF_WAIT_ACTUAL);
            case BOOKSHELF_WAIT_ACTUAL -> bookshelfWaitActual(player);
            case TABLE_OPEN -> tableOpen(player);
            case PLACE_LAPIS -> placeLapis(player);
            case PLACE_FIRST -> placeFirst(player);
            case VERIFY_FIRST -> verifyAndContinue(
                    plan == null ? null : (plan.usesDummy() ? plan.placePrediction() : plan.swapPrediction()),
                    plan != null && plan.usesDummy() ? Phase.DUMMY : phaseAfterVerify());
            case DUMMY -> dummyEnchant(player);
            case DUMMY_VERIFY -> verifyDummyCleared(player);
            case TAKE_DUMMY_OUT -> takeDummyOut(player);
            case DROP_DUMMY -> dropDummy(player);
            case PLACE_REAL -> placeReal(player);
            case VERIFY_SWAPPED -> verifyAndContinue(plan == null ? null : plan.swapPrediction(), phaseAfterVerify());
            case WINDOW_THROWING -> doThrowing(player, plan == null ? 0 : plan.windowThrows(), Phase.ENCHANT);
            case ENCHANT -> enchant(player);
            case ENCHANT_WAIT -> enchantWait(player);
            case ENCHANT_VERIFY -> enchantVerify(player);
            case TAKE_ITEM_OUT -> takeItemOut(player);
            case TABLE_CLOSE -> closeAndWait(player, Phase.RETURN_OPEN);
            case RETURN_OPEN -> returnOpen(player);
            case RETURN_PUT -> returnPut(player);
            case RETURN_CLOSE -> closeAndWait(player, Phase.NEXT_ITEM);
            case NEXT_ITEM -> nextItem();
            default -> finish(true);
        }

        // Yield back to the game. TaskManager re-runs body() in a tight loop within the SAME tick
        // unless a delay is scheduled - and asynchronous state (server menus, fences) can never
        // advance mid-tick, so without this the phase timeout fires instantly.
        scheduleDelay();
    }

    /** true on the tick a (re-)click should be sent: first tick, then every CLICK_RETRY_TICKS. */
    private boolean clickGate() {
        return (phaseTicks - 1) % CLICK_RETRY_TICKS == 0;
    }

    private void resetPhase(Phase next) {
        if (QuickEnchant.LOGGER.isInfoEnabled()) {
            QuickEnchant.LOGGER.info("automation phase: {} -> {} (after {} ticks)", phase, next, phaseTicks);
        }
        QEDebug.log("phase %s -> %s after %d ticks | %s", phase, next, phaseTicks, QEDebug.seedState());
        phase = next;
        phaseTicks = 0;
    }

    /**
     * After the final in-table verification: the window throws prime the NEXT entry's seed and must
     * happen with the real item already in the table (the enchant that follows writes exactly what
     * they leave behind), so they come before the real enchant - or straight to it when the plan
     * needs none (always the case on the chain path, which throws before opening the table).
     */
    private Phase phaseAfterVerify() {
        return plan != null && plan.windowThrows() > 0 ? Phase.WINDOW_THROWING : Phase.ENCHANT;
    }

    /**
     * Next phase after the dummy fetch or the lapis refill finished. Refilling lapis before taking
     * the item out keeps the item in the box while the inventory is rearranged.
     */
    private Phase afterPreparation(LocalPlayer player) {
        return countLapis(player) < LAPIS_RESERVE ? Phase.REFILL_LAPIS_OPEN : Phase.OPEN_ITEM_BOX;
    }

    // ===== resolve =====

    private void resolve(LocalPlayer player) {
        // a stale server-side menu (any container closed a tick ago) would make the open-box
        // request index into the wrong server menu and never open anything
        if (!syncClosed(player)) {
            return;
        }
        if (queueIndex >= queue.size()) {
            finish(true);
            return;
        }
        Item item = queue.get(queueIndex);
        if (skippedItems.contains(item)) {
            queueIndex++;
            return;
        }

        boxSlot = -1;
        for (int invSlot : selectedSlots) {
            ItemStack boxStack = player.getInventory().getItem(invSlot);
            if (!InventoryScanner.isShulkerBox(boxStack)) {
                continue;
            }
            boolean contains = InventoryScanner.readBoxContents(boxStack).stream()
                    .anyMatch(s -> s.is(item) && InventoryScanner.isEnchantableCandidate(s));
            if (contains) {
                boxSlot = invSlot;
                break;
            }
        }
        if (boxSlot == -1) {
            // out of items of this type; skip the rest of the queue entries for it
            skippedItems.add(item);
            ClientCommandHelper.sendError(Component.translatable("quick-enchant.run.itemGoneSoft", item.getName(new ItemStack(item))));
            return; // next tick skips the remaining entries
        }

        int actual = EnchantmentCracker.getBookshelfCount(player.level(), EnchantmentCracker.enchantingTablePos);
        int min = Configs.getMinEnchantBookshelves();
        int max = Configs.getMaxEnchantBookshelves();
        boolean lecternAvailable = ModConfig.Generic.LECTERN_ADJUSTMENT.getBooleanValue() && EnchantmentCracker.lecternPos != null;
        if (!lecternAvailable) {
            // bookshelf count cannot be modified: only accept plans for the actual count
            min = actual;
            max = actual;
        }

        EnchantPredicate predicate = predicateFor(item);
        if (predicate == null) {
            skippedItems.add(item);
            return;
        }

        // single-step lookahead: pass the next queue entry so the solver can prime its seed
        Item nextItem = null;
        EnchantPredicate nextPredicate = null;
        for (int j = queueIndex + 1; j < queue.size(); j++) {
            Item candidate = queue.get(j);
            if (skippedItems.contains(candidate)) {
                continue;
            }
            EnchantPredicate candidatePredicate = predicateFor(candidate);
            if (candidatePredicate == null) {
                continue; // not enchantable by this run; it will be skipped, so look past it
            }
            nextItem = candidate;
            nextPredicate = candidatePredicate;
            break;
        }

        plan = EnchantSolver.solve(player, item, predicate, min, max, dummyItem, firstEntry,
                nextItem, nextPredicate);
        if (plan == null) {
            // No plan ends the run either way - failures terminate, they never skip - but the two
            // causes are different. A later entry means the chain is broken: the seed in the table
            // offers nothing for it and no throw count can change that seed, which proves the seed
            // model wrong, so both seeds are discarded. The first entry has no chain to break: it
            // was simply never solvable at this seed within the budget, the model is still fine.
            QEDebug.log("no plan for %s (queue %d/%d, firstEntry=%s) - terminating the run",
                    item, queueIndex + 1, queue.size(), firstEntry);
            if (firstEntry) {
                abort("quick-enchant.run.unsolvable", false, item.getName(new ItemStack(item)));
            } else {
                abort("quick-enchant.run.chainBroken", true, item.getName(new ItemStack(item)));
            }
            return;
        }

        QEDebug.log("resolved plan for %s (queue %d/%d, boxSlot=%d, bookshelves=%d..%d actual=%d, "
                        + "firstEntry=%s):\n%s",
                QEDebug.stack(new ItemStack(item)), queueIndex + 1, queue.size(), boxSlot,
                min, max, actual, firstEntry, QEDebug.plan(plan));

        if (plan.usesDummy()) {
            // The first entry cannot be enchanted without a dummy, and the seeds are still valid
            // here (nothing ran), so this terminates instead of clearing them.
            if (!new ItemStack(dummyItem).has(DataComponents.ENCHANTABLE)) {
                abort("quick-enchant.run.badDummyItem");
                return;
            }
            // locate a dummy item: first the inventory, then any box
            dummyInvSlot = findDummyInInventory(player);
            if (dummyInvSlot < 0) {
                dummyBoxSlot = findDummyBox(player);
                if (dummyBoxSlot < 0) {
                    abort("quick-enchant.run.noDummyItem");
                    return;
                }
                resetPhase(Phase.FETCH_DUMMY_OPEN);
                return;
            }
        } else {
            dummyInvSlot = -1;
            dummyBoxSlot = -1;
        }

        resetPhase(afterPreparation(player));
    }

    // ===== dummy item fetch =====

    private void fetchDummyOpen(LocalPlayer player) {
        if (!BoxOps.openBox(player, dummyBoxSlot)) {
            abort("quick-enchant.run.boxOpenFailed");
            return;
        }
        resetPhase(Phase.FETCH_DUMMY_TAKE);
    }

    private void fetchDummyTake(LocalPlayer player) {
        if (!BoxOps.isShulkerMenuOpen(player)) {
            return; // wait for the server to open the menu
        }
        var menu = player.containerMenu;
        for (int slot = 0; slot < 27; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.is(dummyItem) && !stack.isEnchanted()) {
                BoxOps.quickMove(menu, slot, player);
                resetPhase(Phase.FETCH_DUMMY_CLOSE);
                return;
            }
        }
        if (phaseTicks > MENU_CONTENT_GRACE_TICKS) {
            abort("quick-enchant.run.noDummyItem");
        }
    }

    // ===== lapis refill =====

    private void refillLapisOpen(LocalPlayer player) {
        InventoryScanner.Snapshot snapshot = InventoryScanner.scan(player);
        if (snapshot.lapisBoxes().isEmpty()) {
            abort("quick-enchant.run.noLapis");
            return;
        }
        if (!BoxOps.openBox(player, snapshot.lapisBoxes().getFirst().invSlot())) {
            abort("quick-enchant.run.boxOpenFailed");
            return;
        }
        resetPhase(Phase.REFILL_LAPIS_TAKE);
    }

    private void refillLapisTake(LocalPlayer player) {
        if (!BoxOps.isShulkerMenuOpen(player)) {
            return; // wait for the server to open the menu
        }
        var menu = player.containerMenu;
        for (int slot = 0; slot < 27; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (InventoryScanner.isLapis(stack)) {
                if (clickGate()) {
                    BoxOps.quickMove(menu, slot, player);
                }
                return;
            }
        }
        if (phaseTicks > MENU_CONTENT_GRACE_TICKS) {
            abort("quick-enchant.run.noLapis");
        }
    }

    // ===== take the item out of its box =====

    private void openItemBox(LocalPlayer player) {
        if (!BoxOps.openBox(player, boxSlot)) {
            abort("quick-enchant.run.boxOpenFailed");
            return;
        }
        resetPhase(Phase.TAKE_ITEM);
    }

    private void takeItem(LocalPlayer player) {
        if (!BoxOps.isShulkerMenuOpen(player)) {
            return; // wait for the server to open the menu
        }
        var menu = player.containerMenu;
        Item item = queue.get(queueIndex);
        for (int slot = 0; slot < 27; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.is(item) && InventoryScanner.isEnchantableCandidate(stack)) {
                BoxOps.quickMove(menu, slot, player);
                resetPhase(Phase.CLOSE_ITEM_BOX);
                return;
            }
        }
        // the initial content sync can lag the screen packet by a tick - only give up once the
        // menu had time to fill
        if (phaseTicks > MENU_CONTENT_GRACE_TICKS) {
            abort("quick-enchant.run.itemGone");
        }
    }

    /**
     * Close any open container and wait until the server has acknowledged the close (round-trip
     * fence via a command-suggestion request). Falls back to proceeding after a short grace period
     * if the server never answers the fence.
     */
    private boolean syncClosed(LocalPlayer player) {
        if (!BoxOps.isClosed(player)) {
            BoxOps.close(player);
            closeFence = SuggestionsHook.fence();
            closeFenceTicks = 0;
            return false;
        }
        if (closeFence != null) {
            closeFenceTicks++;
            if (!closeFence.isDone() && closeFenceTicks <= 40) {
                return false;
            }
            closeFence = null;
        }
        return true;
    }

    private void closeAndWait(LocalPlayer player, Phase next) {
        if (syncClosed(player)) {
            resetPhase(next);
        }
    }

    // ===== throwing =====

    private void doThrowing(LocalPlayer player, int count, Phase next) {
        if (count <= 0) {
            QEDebug.log("throwing skipped (count=0) in %s; the table seed is unchanged", phase);
            resetPhase(next);
            return;
        }

        if (throwTask == null) {
            QEDebug.log("throwing %d items starting from playerSeed=0x%s", count, QEDebug.hex(PlayerRandCracker.getSeed()));
            PlayerRandCracker.throwSlotFilter = this::isSafeThrowSlot;
            EnchantmentCracker.setCurrentlyThrowingItems(true);
            throwTask = new ItemThrowTask(count, ItemThrowTask.FLAG_WAIT_FOR_ITEMS) {
                @Override
                protected void onUnexpectedRNGCall(PlayerRandCracker.RNGCallType callType) {
                    QEDebug.log("throwing aborted: unexpected %s call, playerSeed=0x%s", callType, QEDebug.hex(PlayerRandCracker.getSeed()));
                    ClientCommandHelper.sendError(Component.translatable("commands.cenchant.unexpectedCall", callType.getResetMessage()));
                    EnchantAutomation.abort("quick-enchant.run.aborted");
                }

                @Override
                protected void onFailedToThrowItem(PlayerRandCracker.ThrowItemsResult throwItemsResult) {
                    if (throwItemsResult.getType() != PlayerRandCracker.ThrowItemsResult.Type.NOT_ENOUGH_ITEMS) {
                        QEDebug.log("throwing failed: %s", throwItemsResult.getType());
                        throwItemsResult.sendErrorMessage();
                        EnchantAutomation.abort("quick-enchant.run.aborted");
                    }
                    // NOT_ENOUGH_ITEMS with FLAG_WAIT_FOR_ITEMS: keep waiting until a whitelist
                    // stack has a spare item (thrown items are picked back up or restocked)
                }

                @Override
                protected void onItemThrown(int current, int total) {
                    QEDebug.log("thrown %d/%d, playerSeed=0x%s", current, total, QEDebug.hex(PlayerRandCracker.getSeed()));
                }

                @Override
                protected void onItemSpawn(net.minecraft.network.protocol.game.ClientboundAddEntityPacket packet) {
                    QEDebug.log("server confirmed item spawn at [%.2f %.2f %.2f]".formatted(
                            (float) packet.getX(), (float) packet.getY(), (float) packet.getZ()));
                }
            };
            throwTask.initialize();
        }

        // drive the throw task manually, one tick at a time
        throwTask.body();
        throwTask.unscheduleDelay();
        if (throwTask.isCompleted()) {
            QEDebug.log("throwing finished: %d items, playerSeed=0x%s", count, QEDebug.hex(PlayerRandCracker.getSeed()));
            throwTask.onCompleted();
            throwTask = null;
            PlayerRandCracker.throwSlotFilter = null;
            EnchantmentCracker.setCurrentlyThrowingItems(false);
            resetPhase(next);
        }
    }

    /**
     * Whitelist slots only, and never the enchanting table's own item/lapis slots (they are part
     * of the open menu while the window throws run).
     */
    private boolean isSafeThrowSlot(Slot slot) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof EnchantmentMenu && slot.container != player.getInventory()) {
            return false;
        }
        return InventoryScanner.isWhitelisted(slot.getItem(), InventoryScanner.getWhitelist());
    }

    // ===== bookshelf adjustment via lectern =====

    private void bookshelfOpen(LocalPlayer player) {
        if (plan == null) {
            finish(false);
            return;
        }
        BlockPos tablePos = EnchantmentCracker.enchantingTablePos;
        int actual = tablePos == null ? -1 : EnchantmentCracker.getBookshelfCount(player.level(), tablePos);
        if (actual == plan.bookshelves()) {
            resetPhase(Phase.TABLE_OPEN);
            return;
        }
        BlockPos lecternPos = EnchantmentCracker.lecternPos;
        if (lecternPos == null || !ModConfig.Generic.LECTERN_ADJUSTMENT.getBooleanValue()) {
            ClientCommandHelper.sendError(Component.translatable("quick-enchant.run.bookshelfMismatch", actual, plan.bookshelves()));
            cleanup();
            _break();
            return;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(lecternPos), Direction.UP, lecternPos, false);
        Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        resetPhase(Phase.BOOKSHELF_SET_PAGE);
    }

    private void bookshelfSetPage(LocalPlayer player) {
        if (!(player.containerMenu instanceof LecternMenu lecternMenu)) {
            return; // wait for the menu to open
        }
        if (plan == null) {
            cleanup();
            _break();
            return;
        }
        // page x == x bookshelves; button 100+x jumps directly to the page
        Minecraft.getInstance().gameMode.handleInventoryButtonClick(lecternMenu.containerId, 100 + plan.bookshelves());
        resetPhase(Phase.BOOKSHELF_WAIT_PAGE);
    }

    private void bookshelfWaitPage(LocalPlayer player) {
        if (!(player.containerMenu instanceof LecternMenu lecternMenu)) {
            return;
        }
        if (plan != null && lecternMenu.getPage() == plan.bookshelves()) {
            resetPhase(Phase.BOOKSHELF_CLOSE);
        }
    }

    private void bookshelfWaitActual(LocalPlayer player) {
        if (plan == null) {
            cleanup();
            _break();
            return;
        }
        BlockPos tablePos = EnchantmentCracker.enchantingTablePos;
        if (tablePos == null) {
            abort("quick-enchant.run.noTable");
            return;
        }
        int actual = EnchantmentCracker.getBookshelfCount(player.level(), tablePos);
        if (actual == plan.bookshelves()) {
            // page verified via the lectern DataSlot and the real count verified by scanning
            resetPhase(Phase.TABLE_OPEN);
        }
        // else: wait for the redstone circuit to finish moving the bookshelves
    }

    // ===== table =====

    private void tableOpen(LocalPlayer player) {
        BlockPos tablePos = EnchantmentCracker.enchantingTablePos;
        if (tablePos == null) {
            abort("quick-enchant.run.noTable");
            return;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(tablePos), Direction.UP, tablePos, false);
        Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        resetPhase(Phase.PLACE_LAPIS);
    }

    private void placeLapis(LocalPlayer player) {
        if (!(player.containerMenu instanceof EnchantmentMenu menu)) {
            return; // wait for the table menu
        }
        ItemStack lapisSlot = menu.getSlot(1).getItem();
        if (InventoryScanner.isLapis(lapisSlot) && lapisSlot.getCount() >= LAPIS_RESERVE) {
            resetPhase(Phase.PLACE_FIRST);
            return;
        }
        int lapisInvSlot = findInInventory(player, Items.LAPIS_LAZULI);
        if (lapisInvSlot < 0) {
            abort("quick-enchant.run.noLapis");
            return;
        }
        Integer menuSlot = BoxOps.findInventorySlotInMenu(menu, player, lapisInvSlot);
        if (menuSlot == null) {
            abort("quick-enchant.run.slotNotFound");
            return;
        }
        BoxOps.quickMove(menu, menuSlot, player);
    }

    /** Place the dummy item (when the plan needs one) or the real item into table slot 0. */
    private void placeFirst(LocalPlayer player) {
        if (plan == null) {
            cleanup();
            _break();
            return;
        }
        if (!plan.usesDummy()) {
            placeReal(player);
            return;
        }
        if (!(player.containerMenu instanceof EnchantmentMenu menu)) {
            return;
        }
        if (menu.getSlot(0).hasItem()) {
            resetPhase(Phase.VERIFY_FIRST);
            return;
        }
        dummyInvSlot = findDummyInInventory(player);
        if (dummyInvSlot < 0) {
            abort("quick-enchant.run.noDummyItem");
            return;
        }
        Integer menuSlot = BoxOps.findInventorySlotInMenu(menu, player, dummyInvSlot);
        if (menuSlot == null) {
            abort("quick-enchant.run.slotNotFound");
            return;
        }
        if (clickGate()) {
            BoxOps.quickMove(menu, menuSlot, player);
        }
    }

    /** Place the real (still unenchanted) item into table slot 0. */
    private void placeReal(LocalPlayer player) {
        if (!(player.containerMenu instanceof EnchantmentMenu menu)) {
            return;
        }
        if (plan == null) {
            cleanup();
            _break();
            return;
        }
        if (menu.getSlot(0).hasItem()) {
            // the dummy was dropped, so whatever sits here now should be the real item
            resetPhase(plan.usesDummy() ? Phase.VERIFY_SWAPPED : Phase.VERIFY_FIRST);
            return;
        }
        if (itemInvSlot < 0 || !player.getInventory().getItem(itemInvSlot).is(queue.get(queueIndex))) {
            itemInvSlot = findItemInInventory(player, queue.get(queueIndex), true);
        }
        if (itemInvSlot < 0) {
            abort("quick-enchant.run.itemGone");
            return;
        }
        Integer menuSlot = BoxOps.findInventorySlotInMenu(menu, player, itemInvSlot);
        if (menuSlot == null) {
            abort("quick-enchant.run.slotNotFound");
            return;
        }
        if (clickGate()) {
            BoxOps.quickMove(menu, menuSlot, player);
        }
    }

    /**
     * Enchant the dummy in the slot the plan chose. That enchant writes the seed this item will be
     * enchanted with, so the click order is not optional: throws, dummy enchant, drop, window
     * throws, real enchant.
     */
    private void dummyEnchant(LocalPlayer player) {
        if (!(player.containerMenu instanceof EnchantmentMenu menu) || plan == null) {
            return;
        }
        int slot = plan.dummySlot();
        if (!canAfford(player, menu, slot)) {
            QEDebug.log("cannot afford the dummy enchant: slot=%d costs=%s lapis=%s xpLevel=%d",
                    slot, java.util.Arrays.toString(menu.costs), QEDebug.stack(menu.getSlot(1).getItem()),
                    player.experienceLevel);
            abort("quick-enchant.run.cantAfford");
            return;
        }
        QEDebug.log("enchanting dummy: slot=%d %s, playerSeed=0x%s -> table seed becomes out(now+1)=%s",
                slot, QEDebug.menuState(menu), QEDebug.hex(PlayerRandCracker.getSeed()),
                QEDebug.xpSeed(plan.xpSeed()));
        Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, slot);
        PlayerRandCracker.onEnchantedItem();
        resetPhase(Phase.DUMMY_VERIFY);
    }

    /**
     * After the dummy enchant the slot-0 item carries enchantments, so the server reports
     * isEnchantable == false and zeroes all three offers (independent of the seed). Expecting
     * exactly that state proves the dummy enchant executed; whether it consumed the RIGHT
     * nextInt is verified by the swap verification once the real item is in the table.
     */
    private void verifyDummyCleared(LocalPlayer player) {
        if (phaseTicks <= VERIFY_DELAY_TICKS || !(player.containerMenu instanceof EnchantmentMenu menu)) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            if (menu.costs[i] != 0 || menu.enchantClue[i] != -1 || menu.levelClue[i] != -1) {
                QEDebug.log("DUMMY VERIFY FAILED after %d ticks: the slot-0 item still has offers, "
                                + "so the dummy enchant did not clear them\n  actual: %s",
                        phaseTicks, QEDebug.menuState(menu));
                QEDebug.dump("dummy verify failed", "dummy-verify-failed-" + System.currentTimeMillis() + ".txt");
                abortDesync("quick-enchant.run.verifyFailed");
                return;
            }
        }
        QEDebug.log("dummy enchant confirmed after %d ticks (offers cleared)", phaseTicks);
        resetPhase(Phase.TAKE_DUMMY_OUT);
    }

    /** Pick the enchanted dummy up with the cursor... */
    private void takeDummyOut(LocalPlayer player) {
        if (!(player.containerMenu instanceof EnchantmentMenu menu)) {
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            resetPhase(Phase.DROP_DUMMY);
            return;
        }
        if (!menu.getSlot(0).hasItem()) {
            abort("quick-enchant.run.itemGone");
            return;
        }
        if (clickGate()) {
            Minecraft.getInstance().gameMode.handleContainerInput(menu.containerId, 0, 0, ContainerInput.PICKUP, player);
        }
    }

    /** ...and drop it by clicking outside the container (slot -999, whole stack). */
    private void dropDummy(LocalPlayer player) {
        if (!(player.containerMenu instanceof EnchantmentMenu menu)) {
            return;
        }
        if (menu.getCarried().isEmpty()) {
            resetPhase(Phase.PLACE_REAL);
            return;
        }
        // the drop consumes 4 player RNG steps on purpose: mark it maintained and suppress the
        // "unexpected call" abort while the click is processed
        if (clickGate()) {
            QEDebug.log("dropping the enchanted dummy (consumes 4 RNG steps): %s", QEDebug.seedState());
            droppingDummy = true;
            EnchantmentCracker.setCurrentlyThrowingItems(true);
            Minecraft.getInstance().gameMode.handleContainerInput(menu.containerId, -999, 0, ContainerInput.PICKUP, player);
            EnchantmentCracker.setCurrentlyThrowingItems(false);
            droppingDummy = false;
        }
    }

    private boolean canAfford(LocalPlayer player, EnchantmentMenu menu, int slot) {
        return menu.costs[slot] > 0
                && InventoryScanner.isLapis(menu.getSlot(1).getItem())
                && menu.getSlot(1).getItem().getCount() >= slot + 1
                && player.experienceLevel >= Math.max(slot + 1, menu.costs[slot]);
    }

    private void verifyAndContinue(EnchantmentCracker.TablePrediction prediction, Phase next) {
        if (phaseTicks <= VERIFY_DELAY_TICKS || prediction == null) {
            return;
        }
        if (!(Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.containerMenu instanceof EnchantmentMenu menu)) {
            return;
        }
        if (!EnchantmentCracker.tableMatchesPrediction(menu, prediction)) {
            QEDebug.log("VERIFY FAILED in %s after %d ticks\n  expected: %s\n  actual:   %s\n  diagnosis: %s",
                    phase, phaseTicks, QEDebug.prediction(prediction), QEDebug.menuState(menu),
                    QEDebug.diagnose(menu, prediction));
            QEDebug.dump("verification failed in " + phase, "verify-failed-" + phase + "-" + System.currentTimeMillis() + ".txt");
            abortDesync("quick-enchant.run.verifyFailed");
            return;
        }
        QEDebug.log("%s verified after %d ticks: %s", phase, phaseTicks, QEDebug.menuState(menu));
        resetPhase(next);
    }

    private void enchant(LocalPlayer player) {
        if (!(player.containerMenu instanceof EnchantmentMenu menu) || plan == null) {
            return;
        }
        int slot = plan.realSlot();
        if (!canAfford(player, menu, slot)) {
            QEDebug.log("cannot afford the real enchant: slot=%d costs=%s lapis=%s xpLevel=%d",
                    slot, java.util.Arrays.toString(menu.costs), QEDebug.stack(menu.getSlot(1).getItem()),
                    player.experienceLevel);
            abort("quick-enchant.run.cantAfford");
            return;
        }
        QEDebug.log("enchanting real item: slot=%d %s, expected list %s, playerSeed=0x%s -> consumes 1 nextInt",
                slot, QEDebug.menuState(menu), QEDebug.enchantments(plan.realList()),
                QEDebug.hex(PlayerRandCracker.getSeed()));
        Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, slot);
        PlayerRandCracker.onEnchantedItem();
        resetPhase(Phase.ENCHANT_WAIT);
    }

    private void enchantWait(LocalPlayer player) {
        if (!(player.containerMenu instanceof EnchantmentMenu menu)) {
            return;
        }
        ItemStack enchanted = menu.getSlot(0).getItem();
        if (!enchanted.isEmpty() && enchanted.isEnchanted()) {
            QEDebug.log("real enchant applied after %d ticks: %s, new xpSeed=%s",
                    phaseTicks, QEDebug.stack(enchanted), QEDebug.seedState());
            resetPhase(Phase.ENCHANT_VERIFY);
        }
    }

    private void enchantVerify(LocalPlayer player) {
        if (!(player.containerMenu instanceof EnchantmentMenu menu) || plan == null) {
            return;
        }
        ItemStack enchanted = menu.getSlot(0).getItem();
        List<EnchantmentInstance> actual = new ArrayList<>();
        enchanted.getEnchantments().keySet().forEach(holder ->
                actual.add(new EnchantmentInstance(holder, enchanted.getEnchantments().getLevel(holder))));
        List<EnchantmentInstance> expected = plan.realList();
        if (!sameEnchantments(actual, expected)) {
            QEDebug.log("ENCHANT MISMATCH: item=%s\n  expected: %s\n  actual:   %s",
                    QEDebug.stack(enchanted), QEDebug.enchantments(expected), QEDebug.enchantments(actual));
            QEDebug.dump("enchant mismatch", "enchant-mismatch-" + System.currentTimeMillis() + ".txt");
            abortDesync("quick-enchant.run.enchantMismatch");
            return;
        }
        QEDebug.log("enchant verified: %s", QEDebug.enchantments(actual));
        // the run has now performed its first successful real enchant: no later entry may use a
        // dummy, the chain has to carry itself from here on
        firstEntry = false;
        resetPhase(Phase.TAKE_ITEM_OUT);
    }

    private static boolean sameEnchantments(List<EnchantmentInstance> a, List<EnchantmentInstance> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (EnchantmentInstance instance : a) {
            boolean found = false;
            for (EnchantmentInstance other : b) {
                if (other.enchantment() == instance.enchantment() && other.level() == instance.level()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private void takeItemOut(LocalPlayer player) {
        if (!(player.containerMenu instanceof EnchantmentMenu menu)) {
            return;
        }
        if (!menu.getSlot(0).hasItem()) {
            resetPhase(Phase.TABLE_CLOSE);
            return;
        }
        if (clickGate()) {
            BoxOps.quickMove(menu, 0, player);
        }
    }

    // ===== return the item to its original box =====

    private void returnOpen(LocalPlayer player) {
        int invSlot = findItemInInventory(player, queue.get(queueIndex), false);
        if (invSlot < 0) {
            abort("quick-enchant.run.itemGone");
            return;
        }
        itemInvSlot = invSlot;
        if (!BoxOps.openBox(player, boxSlot)) {
            abort("quick-enchant.run.boxOpenFailed");
            return;
        }
        resetPhase(Phase.RETURN_PUT);
    }

    private void returnPut(LocalPlayer player) {
        if (!BoxOps.isShulkerMenuOpen(player)) {
            return;
        }
        Integer menuSlot = BoxOps.findInventorySlotInMenu(player.containerMenu, player, itemInvSlot);
        if (menuSlot == null) {
            if (phaseTicks > MENU_CONTENT_GRACE_TICKS) {
                abort("quick-enchant.run.slotNotFound");
            }
            return;
        }
        BoxOps.quickMove(player.containerMenu, menuSlot, player);
        resetPhase(Phase.RETURN_CLOSE);
    }

    private void nextItem() {
        queueIndex++;
        plan = null;
        itemInvSlot = -1;
        dummyInvSlot = -1;
        dummyBoxSlot = -1;
        resetPhase(Phase.RESOLVE);
    }

    // ===== helpers =====

    private @Nullable EnchantPredicate predicateFor(Item item) {
        for (TypeConfig config : configs) {
            if (config.item() == item) {
                return config.predicate();
            }
        }
        return null;
    }

    private void finish(boolean success) {
        if (success) {
            completedNormally = true;
            ClientCommandHelper.sendFeedback(Component.translatable("quick-enchant.run.done").withStyle(ChatFormatting.GREEN));
        }
        cleanup();
    }

    private int countLapis(LocalPlayer player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (InventoryScanner.isLapis(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int findInInventory(LocalPlayer player, Item item) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    private int findDummyInInventory(LocalPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(dummyItem) && !stack.isEnchanted()) {
                return i;
            }
        }
        return -1;
    }

    private int findDummyBox(LocalPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!InventoryScanner.isShulkerBox(stack)) {
                continue;
            }
            for (ItemStack content : InventoryScanner.readBoxContents(stack)) {
                if (content.is(dummyItem) && !content.isEnchanted()) {
                    return slot;
                }
            }
        }
        return -1;
    }

    private int findItemInInventory(LocalPlayer player, Item item, boolean unenchanted) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item) && (!unenchanted || !stack.isEnchanted())) {
                return i;
            }
        }
        return -1;
    }

    public int statusCurrentIndex() {
        return queueIndex;
    }

    public int statusQueueSize() {
        return queue.size();
    }

    public String statusPhase() {
        return phase.name();
    }

    @Override
    public String toString() {
        return "EnchantAutomation[phase=" + phase + ", item " + queueIndex + "/" + queue.size() + "]";
    }
}
