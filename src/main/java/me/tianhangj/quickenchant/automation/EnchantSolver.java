package me.tianhangj.quickenchant.automation;

import me.tianhangj.quickenchant.Configs;
import me.tianhangj.quickenchant.features.EnchantmentCracker;
import me.tianhangj.quickenchant.features.PlayerRandCracker;
import me.tianhangj.quickenchant.predicate.EnchantPredicate;
import me.tianhangj.quickenchant.util.QEDebug;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Plans the table operations for one queue entry.
 *
 * <h2>The seed chain</h2>
 * Every successful enchant runs {@code Player.onEnchantmentPerformed}, which does
 * {@code enchantmentSeed = random.nextInt()}. The value it draws becomes the table seed for the
 * NEXT enchant, while the seed used BY that enchant was written by the previous one. The
 * enchantments therefore form a chain:
 *
 * <pre>
 *   throws -&gt; dummy enchant    writes S1  -&gt; item 1 enchants with S1
 *   throws -&gt; item 1 enchant   writes S2  -&gt; item 2 enchants with S2
 *   throws -&gt; item 2 enchant   writes S3  -&gt; item 3 enchants with S3
 *   throws -&gt; item 3 enchant   writes S4  -&gt; ...
 * </pre>
 *
 * Throwing items only advances the <em>player</em> RNG; it never touches the table seed. The seed an
 * enchant writes is fully determined by the player RNG position at the moment of the enchant, which
 * the automation controls by throwing whitelist items beforehand. So every step is: throw until the
 * upcoming enchant will write the seed the NEXT item needs, then enchant.
 *
 * <h2>Why only the first item needs a dummy</h2>
 * The dummy's only job is to write the seed item 1 will use. From item 1 onwards the previous
 * enchant already does that job, so a dummy would be a wasted table operation. Hence
 * {@code dummySlot} is non-null only for the first entry of a run; every later entry is a pure
 * chain step with no dummy.
 *
 * <p>There is no repair path. If a later entry's seed offers nothing for it, the chain is broken
 * and no throw count can fix that - throwing does not change the seed already in the table.
 * {@link #solve} then returns null and the caller terminates the run.
 */
public class EnchantSolver {
    public static final int NO_DUMMY = -1;

    /**
     * @param itemThrows   item throws executed BEFORE opening the table
     * @param dummySlot    {@link #NO_DUMMY} for every entry after the first; otherwise the table slot
     *                     to enchant the dummy in, whose enchant writes this item's seed
     * @param realSlot     table slot to click for this item
     * @param xpSeed       the seed this item is enchanted with (written by the dummy, or already in the table)
     * @param windowThrows item throws executed inside the open table, after the dummy drop (dummy only)
     */
    public record Plan(int itemThrows, int dummySlot, int realSlot, int bookshelves,
                       int xpSeed, List<EnchantmentInstance> realList,
                       /** what the open table must show with the dummy in slot 0 (dummy only) */
                       EnchantmentCracker.@Nullable TablePrediction placePrediction,
                       /** what the open table must show with the real item in slot 0 */
                       EnchantmentCracker.TablePrediction swapPrediction,
                       int windowThrows) {
        public boolean usesDummy() {
            return dummySlot != NO_DUMMY;
        }
    }

    /** A concrete way to enchant a stack: which bookshelf count, which slot, and what it grants. */
    private record Offer(int bookshelves, int slot, List<EnchantmentInstance> list) {
    }

    /**
     * How many pre-throws either path may scan: always the user's full configured budget.
     *
     * <p>It once had a 512-step "latency" cap, which silently turned "this seed needs a rare
     * combination" into "no plan found" for an item that was enchantable well inside the configured
     * limit. A throw count is cheap; giving up on a solvable item is not. The search stops at the
     * first hit, so the full range is only walked when no solution exists at all.
     */
    public static int scanLimit(int maxThrows) {
        return maxThrows;
    }

    /**
     * Upper bound for the WINDOW throws - the ones made inside the already-open table, in the gap
     * between the dummy drop and this item's own enchant.
     *
     * <p>They are deliberately not given the user's throw budget: they happen while the enchanter
     * sits in an open GUI, and every throw is a container click plus a server confirmation, so a
     * long search there turns a one-second step into a minute of standing still. Pre-table throws
     * (before the table opens) have no such cost and use the full budget - the two are different
     * things and must not be unified.
     */
    public static final int PRIME_WINDOW_THROWS = 256;

    /**
     * Plan this entry, or null when the chain cannot be continued (the caller terminates the run).
     *
     * @param firstOfRun true only for the first enchanted entry of a run - the only entry allowed to
     *                   use a dummy, because no previous enchant exists yet to write its seed
     * @param nextItem   the next queue entry, or null when this is the last one
     */
    @Nullable
    public static Plan solve(LocalPlayer player, Item item, EnchantPredicate predicate,
                             int minBookshelves, int maxBookshelves, Item dummyItem, boolean firstOfRun,
                             @Nullable Item nextItem, @Nullable EnchantPredicate nextPredicate) {
        if (Configs.enchCrackState != EnchantmentCracker.CrackState.CRACKED
                || !Configs.playerCrackState.knowsSeed()
                || !EnchantmentCracker.knowsXpSeed()) {
            return null;
        }

        int currentXpSeed = EnchantmentCracker.getCurrentXpSeed();
        long playerSeed = PlayerRandCracker.getSeed();
        Registry<Enchantment> registry = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        ItemStack realStack = new ItemStack(item);
        ItemStack dummyStack = new ItemStack(dummyItem);
        int maxThrows = Configs.getMaxEnchantItemThrows();
        int maxSlot = Configs.getMaxEnchantSlot();
        ItemStack nextStack = nextItem == null || nextPredicate == null ? null : new ItemStack(nextItem);

        QEDebug.log("solver: item=%s next=%s firstOfRun=%s xpSeed=%s playerSeed=0x%s maxThrows=%d "
                        + "bookshelves=%d..%d maxSlot=%d levels=%d..%d",
                QEDebug.stack(realStack), nextItem == null ? "none" : nextItem.toString(), firstOfRun,
                QEDebug.xpSeed(currentXpSeed), QEDebug.hex(playerSeed), maxThrows,
                minBookshelves, maxBookshelves, maxSlot,
                Configs.getMinEnchantLevels(), Configs.getMaxEnchantLevels());

        // ===== chain step: the seed already in the table offers this item =====
        // This is the steady state. It also covers the first entry when the table happens to hold a
        // usable seed already, in which case the dummy would be a pure waste.
        Offer offer = findOffer(registry, currentXpSeed, realStack, predicate, minBookshelves, maxBookshelves, maxSlot);
        if (offer != null) {
            // Throws happen before the table opens, so this enchant writes out(playerSeed + 4 * itemThrows).
            int itemThrows = 0;
            if (nextStack != null) {
                itemThrows = findThrows(registry, playerSeed, SeedChain.chainSteps(0), nextStack, nextPredicate,
                        minBookshelves, maxBookshelves, maxSlot, scanLimit(maxThrows));
                if (itemThrows < 0) {
                    QEDebug.log("solver: seed %s enchants this item, but none of the next %d throw counts makes "
                                    + "its enchant write a seed the next entry can use",
                            QEDebug.xpSeed(currentXpSeed), scanLimit(maxThrows));
                    return null;
                }
            }
            QEDebug.log("solver: chain plan - seed %s offers slot %d at %d bookshelves; %d pre-throws make this "
                            + "enchant write the next entry's seed",
                    QEDebug.xpSeed(currentXpSeed), offer.slot(), offer.bookshelves(), itemThrows);
            return new Plan(itemThrows, NO_DUMMY, offer.slot(), offer.bookshelves(), currentXpSeed, offer.list(),
                    null,
                    EnchantmentCracker.predictTable(registry, currentXpSeed, realStack, offer.bookshelves()),
                    0);
        }

        if (SeedChain.decide(false, firstOfRun) == SeedChain.Kind.BROKEN) {
            // Later entries have no dummy to fall back on: the previous enchant was supposed to write
            // a usable seed and did not, so the chain is broken and throwing cannot repair it.
            QEDebug.log("solver: seed %s offers nothing for %s at bookshelves %d..%d, and this is not the "
                            + "first entry - the chain is broken",
                    QEDebug.xpSeed(currentXpSeed), QEDebug.stack(realStack), minBookshelves, maxBookshelves);
            return null;
        }

        // ===== dummy step: only ever for the first entry =====
        // The dummy enchant happens after n0 throws, so it writes out(playerSeed + 4 * n0) - and that
        // becomes the seed THIS item is enchanted with. The enchant itself consumes 1 step, the drop
        // 4 more, so this item's own enchant lands at playerSeed + 4 * n0 + 5 + 4 * w and writes
        // out(...) for the next entry. See SeedChain for the same accounting in testable form.
        int limit = scanLimit(maxThrows);
        int withRealOffer = 0;
        int primed = 0;

        for (int n0 = 0; n0 <= limit; n0++) {
            int dummyXpSeed = SeedChain.dummySeed(playerSeed, n0);
            Offer realOffer = findOffer(registry, dummyXpSeed, realStack, predicate,
                    minBookshelves, maxBookshelves, maxSlot);
            if (realOffer == null) {
                continue; // the dummy would not write a usable seed for this item
            }
            withRealOffer++;

            // The window that primes the NEXT entry depends only on n0 (dummy enchant + drop), not
            // on which slot the dummy is enchanted in - so it is searched once per n0. Without a
            // usable window this n0 is pointless in a multi-entry run: the next entry could never
            // be enchanted, and it has no dummy to fall back on.
            int windowThrows = 0;
            if (nextStack != null) {
                windowThrows = findThrows(registry, playerSeed,
                        SeedChain.chainSteps(n0) + SeedChain.DUMMY_TO_REAL_STEPS,
                        nextStack, nextPredicate, minBookshelves, maxBookshelves, maxSlot,
                        PRIME_WINDOW_THROWS);
                if (windowThrows < 0) {
                    continue;
                }
            }
            primed++;

            for (int dummySlot = 0; dummySlot < 3; dummySlot++) {
                // The dummy shares the table session, so it must also produce an offer at the SAME
                // bookshelf count - otherwise the server performs no enchant and the seed this plan
                // depends on is never written.
                List<EnchantmentInstance> dummyList = EnchantmentCracker.simulateSlotList(
                        registry, currentXpSeed, dummySlot, realOffer.bookshelves(), dummyStack);
                if (dummyList.isEmpty()) {
                    continue;
                }

                QEDebug.log("solver: dummy plan - %d pre-throws, dummy slot %d at %d bookshelves writes seed %s; "
                                + "%d window throws make this item's enchant write the next entry's seed",
                        n0, dummySlot, realOffer.bookshelves(), QEDebug.xpSeed(dummyXpSeed), windowThrows);
                return new Plan(n0, dummySlot, realOffer.slot(), realOffer.bookshelves(), dummyXpSeed,
                        realOffer.list(),
                        EnchantmentCracker.predictTable(registry, currentXpSeed, dummyStack, realOffer.bookshelves()),
                        EnchantmentCracker.predictTable(registry, dummyXpSeed, realStack, realOffer.bookshelves()),
                        windowThrows);
            }
        }

        QEDebug.log("solver: no dummy plan within %d throws (chain seed %s, bookshelves %d..%d): "
                        + "%d of those seeds offered the item, %d of those could also prime the next entry "
                        + "within %d window throws, and none of them had a usable dummy slot",
                limit, QEDebug.xpSeed(currentXpSeed), minBookshelves, maxBookshelves,
                withRealOffer, primed, PRIME_WINDOW_THROWS);
        return null;
    }

    /**
     * Smallest item-throw count that makes an enchant at {@code playerSeed + offsetSteps + 4n}
     * write a seed the next queue entry can use, or -1 when none works up to {@code maxThrows}.
     *
     * <p>Each throw is 4 player-RNG steps, and the enchant writes the next 32-bit draw as the next
     * table seed, so candidate seeds are simply those draws.
     */
    private static int findThrows(Registry<Enchantment> registry, long playerSeed, long offsetSteps,
                                  ItemStack nextStack, EnchantPredicate nextPredicate,
                                  int minBookshelves, int maxBookshelves, int maxSlot, int maxThrows) {
        return SeedChain.findThrows(playerSeed, offsetSteps, maxThrows, writtenSeed ->
                findOffer(registry, writtenSeed, nextStack, nextPredicate,
                        minBookshelves, maxBookshelves, maxSlot) != null);
    }

    /**
     * Find a bookshelf count and slot whose offers satisfy the predicate for this seed, or null.
     * Bookshelf counts and slots are scanned in ascending order so the plan is deterministic.
     */
    @Nullable
    private static Offer findOffer(Registry<Enchantment> registry, int seed, ItemStack stack, EnchantPredicate predicate,
                                   int minBookshelves, int maxBookshelves, int maxSlot) {
        int minLevel = Configs.getMinEnchantLevels();
        int maxLevel = Configs.getMaxEnchantLevels();
        for (int bookshelves = minBookshelves; bookshelves <= maxBookshelves; bookshelves++) {
            for (int slot = 0; slot < maxSlot; slot++) {
                // the cost gate is much cheaper than drawing the enchantment list, and cost == 0
                // means the server would offer nothing at all
                int cost = EnchantmentCracker.simulateSlotCost(seed, slot, bookshelves, stack);
                if (cost <= 0 || cost < minLevel || cost > maxLevel) {
                    continue;
                }
                List<EnchantmentInstance> list = EnchantmentCracker.simulateSlotList(registry, seed, slot, bookshelves, stack);
                if (list.isEmpty()) {
                    continue;
                }
                if (predicate.test(list)) {
                    return new Offer(bookshelves, slot, list);
                }
            }
        }
        return null;
    }
}
