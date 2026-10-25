package me.tianhangj.quickenchant.automation;

import com.seedfinding.mcseed.lcg.LCG;
import com.seedfinding.mcseed.rand.Rand;
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
 * Searches for a sequence of table operations that produces the requested enchantments on one item.
 *
 * <p>The vanilla table only enchants items with no existing enchantments (server-enforced via
 * {@code ItemStack.isEnchantable()} and the zeroed slot costs in {@code EnchantmentMenu}), so when
 * the current seed does not already offer the target, a DUMMY enchant is performed on a separate
 * item (configurable, e.g. a book): the dummy enchant advances the table seed to the target value,
 * then the dummy is swapped out for the real item. The dummy copy keeps whatever the dummy enchant
 * applied and is dropped afterwards; it only needs a non-empty offer so the server actually
 * performs the enchant and advances the seed.
 *
 * <h2>Single-step lookahead</h2>
 * The real enchant writes the player RNG's next {@code nextInt()} into the table seed, which
 * becomes the NEXT item's seed. Throws executed between the dummy and the real enchant (the
 * "window") do not affect the real enchant itself (it reads the DataSlot written by the dummy),
 * so the solver additionally searches a window throw count m that positions that written value to
 * satisfy the next queue entry's predicate. The next item then enchants directly (its own solver's
 * no-dummy fast path hits automatically - no cross-item state is needed). If no m is found within
 * the cap, {@code windowThrows} is 0 and the next item solves independently.
 */
public class EnchantSolver {
    public static final int NO_DUMMY = -1;

    /** upper bound for the lookahead window throws (each hit saves the next item its dummy) */
    private static final int MAX_WINDOW_THROWS = 256;

    public record Plan(int itemThrows, int dummySlot, int realSlot, int bookshelves,
                       int currentXpSeed, int realXpSeed,
                       List<EnchantmentInstance> realList,
                       /** prediction for whatever sits in the table right after placement (dummy or real item) */
                       EnchantmentCracker.TablePrediction placePrediction,
                       /** prediction for the real item after it replaces the dummy */
                       EnchantmentCracker.TablePrediction swapPrediction,
                       /** throws executed in the dummy->real window to prime the next queue entry's seed */
                       int windowThrows) {
    }

    @Nullable
    public static Plan solve(LocalPlayer player, Item item, EnchantPredicate predicate, int minBookshelves, int maxBookshelves,
                             Item dummyItem, @Nullable Item nextItem, @Nullable EnchantPredicate nextPredicate) {
        if (Configs.enchCrackState != EnchantmentCracker.CrackState.CRACKED
                || !Configs.playerCrackState.knowsSeed()) {
            return null;
        }

        int currentXpSeed = EnchantmentCracker.getCurrentXpSeed();
        long playerSeed = PlayerRandCracker.getSeed();
        Registry<Enchantment> registry = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        ItemStack realStack = new ItemStack(item);
        ItemStack dummyStack = new ItemStack(dummyItem);
        int maxThrows = Configs.getMaxEnchantItemThrows();
        int maxSlot = Configs.getMaxEnchantSlot();

        QEDebug.log("solver: item=%s dummy=%s next=%s currentXpSeed=%s playerSeed=0x%s maxThrows=%d bookshelves=%d..%d maxSlot=%d levels=%d..%d",
                QEDebug.stack(realStack), QEDebug.stack(dummyStack), nextItem == null ? "none" : nextItem.toString(),
                QEDebug.xpSeed(currentXpSeed), QEDebug.hex(playerSeed), maxThrows,
                minBookshelves, maxBookshelves, maxSlot,
                Configs.getMinEnchantLevels(), Configs.getMaxEnchantLevels());

        // rejection tallies, so a failed search can be explained in one line instead of per-step spam
        int tried = 0;
        int dummyEmpty = 0;
        int realEmpty = 0;
        int costOutOfRange = 0;
        int predicateFailed = 0;

        for (int times = 0; times <= maxThrows; times++) {
            // The table seed AFTER the dummy enchant. Every successful enchant - dummy or real -
            // runs Player.onEnchantmentPerformed, which does enchantmentSeed = random.nextInt().
            // The dummy enchant therefore consumes exactly one nextInt() from the player RNG,
            // right after the `times` pre-throws (4 nextInt-equivalents each). This holds for
            // times == 0 as well: the seed becomes out(s + 1), NOT the current seed. Only the
            // no-dummy path keeps the current seed, because then no enchant has happened yet.
            Rand playerRand = new Rand(LCG.JAVA, playerSeed);
            playerRand.advance(times * 4L);
            int dummyXpSeed = (int) playerRand.nextBits(32);

            for (int bookshelves = minBookshelves; bookshelves <= maxBookshelves; bookshelves++) {
                // no-dummy case: the table seed does not depend on the pre-throws, so if this can
                // work at all it already works at times == 0 - no need to search it repeatedly
                if (times == 0) {
                    Rejection rejection = new Rejection();
                    Plan plan = tryCombination(registry, currentXpSeed, currentXpSeed, NO_DUMMY, bookshelves,
                            times, realStack, dummyStack, predicate, maxSlot, rejection);
                    tried += rejection.tried;
                    dummyEmpty += rejection.dummyEmpty;
                    realEmpty += rejection.realEmpty;
                    costOutOfRange += rejection.costOutOfRange;
                    predicateFailed += rejection.predicateFailed;
                    if (plan != null) {
                        return withLookahead(plan, registry, playerSeed, nextItem, nextPredicate, minBookshelves, maxBookshelves, maxSlot);
                    }
                }

                for (int dummySlot = 0; dummySlot < 3; dummySlot++) {
                    Rejection rejection = new Rejection();
                    Plan plan = tryCombination(registry, currentXpSeed, dummyXpSeed, dummySlot, bookshelves,
                            times, realStack, dummyStack, predicate, maxSlot, rejection);
                    tried += rejection.tried;
                    dummyEmpty += rejection.dummyEmpty;
                    realEmpty += rejection.realEmpty;
                    costOutOfRange += rejection.costOutOfRange;
                    predicateFailed += rejection.predicateFailed;
                    if (plan != null) {
                        return withLookahead(plan, registry, playerSeed, nextItem, nextPredicate, minBookshelves, maxBookshelves, maxSlot);
                    }
                }
            }
        }

        QEDebug.log("solver: no plan found. combinations=%d dummyListEmpty=%d realListEmpty=%d costOutOfRange=%d predicateFailed=%d",
                tried, dummyEmpty, realEmpty, costOutOfRange, predicateFailed);
        return null;
    }

    /** Tallies why combinations were rejected, so a failed search reports one summary line. */
    private static final class Rejection {
        int tried;
        int dummyEmpty;
        int realEmpty;
        int costOutOfRange;
        int predicateFailed;
    }

    @Nullable
    private static Plan tryCombination(Registry<Enchantment> registry, int currentXpSeed, int realXpSeed, int dummySlot,
                                       int bookshelves, int times, ItemStack realStack, ItemStack dummyStack,
                                       EnchantPredicate predicate, int maxSlot, Rejection rejection) {
        List<EnchantmentInstance> dummyList = List.of();
        if (dummySlot != NO_DUMMY) {
            // the dummy offer is simulated with the dummy item's own stack (its enchantability
            // affects the cost and the candidate pool); the server only performs the enchant (and
            // advances the seed) if the list is non-empty
            dummyList = EnchantmentCracker.simulateSlotList(registry, currentXpSeed, dummySlot, bookshelves, dummyStack);
            if (dummyList.isEmpty()) {
                rejection.dummyEmpty++;
                return null;
            }
        }

        for (int realSlot = 0; realSlot < maxSlot; realSlot++) {
            rejection.tried++;
            List<EnchantmentInstance> realList = EnchantmentCracker.simulateSlotList(registry, realXpSeed, realSlot, bookshelves, realStack);
            if (realList.isEmpty()) {
                rejection.realEmpty++;
                continue;
            }
            int realCost = EnchantmentCracker.simulateSlotCost(realXpSeed, realSlot, bookshelves, realStack);
            if (realCost < Configs.getMinEnchantLevels() || realCost > Configs.getMaxEnchantLevels()) {
                rejection.costOutOfRange++;
                continue;
            }

            // the real item receives only the real list (the dummy enchants a separate item)
            if (!predicate.test(realList)) {
                rejection.predicateFailed++;
                continue;
            }

            EnchantmentCracker.TablePrediction placePrediction;
            EnchantmentCracker.TablePrediction swapPrediction;
            if (dummySlot == NO_DUMMY) {
                placePrediction = EnchantmentCracker.predictTable(registry, currentXpSeed, realStack, bookshelves);
                swapPrediction = placePrediction;
            } else {
                placePrediction = EnchantmentCracker.predictTable(registry, currentXpSeed, dummyStack, bookshelves);
                swapPrediction = EnchantmentCracker.predictTable(registry, realXpSeed, realStack, bookshelves);
            }

            return new Plan(times, dummySlot, realSlot, bookshelves, currentXpSeed, realXpSeed,
                    realList, placePrediction, swapPrediction, 0);
        }
        return null;
    }

    /**
     * Search the window throw count m so that the seed written by this item's real enchant
     * satisfies the next queue entry's predicate.
     *
     * <p>RNG accounting, relative to the state s before this item's pre-throws: the dummy
     * consumes out(s + 4n), the dummy drop advances 4 steps, m window throws advance 4m, and the
     * real enchant consumes out(s + 4n + 5 + 4m) which becomes the next table seed. Without a
     * dummy there is no pre-throw and no drop, so the written value is out(s + 4m).
     */
    private static Plan withLookahead(Plan plan, Registry<Enchantment> registry, long playerSeed,
                                      @Nullable Item nextItem, @Nullable EnchantPredicate nextPredicate,
                                      int minBookshelves, int maxBookshelves, int maxSlot) {
        if (nextItem == null || nextPredicate == null) {
            return plan;
        }

        ItemStack nextStack = new ItemStack(nextItem);
        int baseSteps = plan.dummySlot() == NO_DUMMY ? 0 : 4 * plan.itemThrows() + 5;

        for (int m = 0; m <= MAX_WINDOW_THROWS; m++) {
            Rand playerRand = new Rand(LCG.JAVA, playerSeed);
            playerRand.advance(baseSteps + 4L * m);
            int writtenSeed = (int) playerRand.nextBits(32);
            if (seedOffers(registry, writtenSeed, nextStack, nextPredicate, minBookshelves, maxBookshelves, maxSlot)) {
                QEDebug.log("solver: lookahead windowThrows=%d writes next seed %s (baseSteps=%d)",
                        m, QEDebug.xpSeed(writtenSeed), baseSteps);
                return new Plan(plan.itemThrows(), plan.dummySlot(), plan.realSlot(), plan.bookshelves(),
                        plan.currentXpSeed(), plan.realXpSeed(), plan.realList(),
                        plan.placePrediction(), plan.swapPrediction(), m);
            }
        }
        QEDebug.log("solver: lookahead found no usable window within %d throws (baseSteps=%d); next item solves independently",
                MAX_WINDOW_THROWS, baseSteps);
        return plan;
    }

    /** Whether the given table seed offers an enchantment set satisfying the predicate at some slot and bookshelf count. */
    private static boolean seedOffers(Registry<Enchantment> registry, int seed, ItemStack stack, EnchantPredicate predicate,
                                      int minBookshelves, int maxBookshelves, int maxSlot) {
        for (int bookshelves = minBookshelves; bookshelves <= maxBookshelves; bookshelves++) {
            for (int slot = 0; slot < maxSlot; slot++) {
                List<EnchantmentInstance> list = EnchantmentCracker.simulateSlotList(registry, seed, slot, bookshelves, stack);
                if (list.isEmpty()) {
                    continue;
                }
                int cost = EnchantmentCracker.simulateSlotCost(seed, slot, bookshelves, stack);
                if (cost < Configs.getMinEnchantLevels() || cost > Configs.getMaxEnchantLevels()) {
                    continue;
                }
                if (predicate.test(list)) {
                    return true;
                }
            }
        }
        return false;
    }
}
