package me.tianhangj.quickenchant;

import com.seedfinding.mcseed.lcg.LCG;
import com.seedfinding.mcseed.rand.Rand;
import me.tianhangj.quickenchant.automation.SeedChain;
import me.tianhangj.quickenchant.features.PlayerRandCracker;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pure-logic tests for the player RNG mirror and the seed accounting the solver depends on.
 * The whole automation relies on these two things matching the server exactly, so they are
 * pinned here rather than only exercised in game.
 */
class RngMirrorTest {

    @Test
    void lcgMirrorMatchesVanillaRandom() {
        long[] seeds = {0x123456789ABL, 0x5DEECE66DL, 42L, -1L << 48, 0xFFFFFFFFFFFFL};
        for (long seed : seeds) {
            PlayerRandCracker.setSeed(seed);
            Random vanilla = new Random(seed ^ PlayerRandCracker.MULTIPLIER);

            assertEquals(vanilla.nextInt(), PlayerRandCracker.nextInt(), "nextInt mismatch for seed " + seed);
            assertEquals(vanilla.nextInt(100), PlayerRandCracker.nextInt(100), "nextInt(bound) mismatch");
            assertEquals(vanilla.nextInt(7), PlayerRandCracker.nextInt(7), "nextInt(7) mismatch");
            assertEquals(vanilla.nextFloat(), PlayerRandCracker.nextFloat(), 0f, "nextFloat mismatch");
        }
    }

    @Test
    void unthrowItemRevertsOneThrowOfFourSteps() {
        long seed = 0xABCDEF12345L;
        PlayerRandCracker.setSeed(seed);
        for (int i = 0; i < 4; i++) {
            PlayerRandCracker.nextInt(); // one item throw consumes 4 nextInt calls
        }
        PlayerRandCracker.unthrowItem();
        assertEquals(seed, PlayerRandCracker.getSeed(), "unthrowItem must revert exactly one throw");
    }

    @Test
    void solverSeedConventionMatchesMirror() {
        // the solver computes the XP seed after n throws via Rand(LCG.JAVA, seed).advance(n * 4).nextBits(32);
        // it must agree with the mirror: 4 nextInt() steps per throw, then the next nextInt()
        long seed = 0x0F1E2D3C4B5AL;
        for (int throwsCount = 0; throwsCount <= 3; throwsCount++) {
            PlayerRandCracker.setSeed(seed);
            for (int i = 0; i < throwsCount * 4; i++) {
                PlayerRandCracker.nextInt();
            }
            int mirrorXpSeed = PlayerRandCracker.nextInt();

            Rand playerRand = new Rand(LCG.JAVA, seed);
            playerRand.advance(throwsCount * 4L);
            int solverXpSeed = (int) playerRand.nextBits(32);

            assertEquals(mirrorXpSeed, solverXpSeed, "solver convention mismatch for " + throwsCount + " throws");
        }
    }

    /**
     * Regression test for the "dummy plan with zero pre-throws always fails VERIFY_SWAPPED" bug.
     *
     * <p>{@code EnchantmentMenu.clickMenuButton} calls {@code Player.onEnchantmentPerformed} for
     * every successful enchant, and that method does {@code enchantmentSeed = random.nextInt()}.
     * So performing a dummy enchant consumes one player-RNG nextInt() even when no items were
     * thrown first, and the table seed the real item will see is {@code out(s + 1)} - never the
     * pre-dummy seed. The solver used to reuse the current seed when {@code times == 0}, which made
     * it predict the swap table with the stale seed: VERIFY_FIRST passed (the dummy prediction did
     * use the current seed) and VERIFY_SWAPPED then always failed.
     */
    @Test
    void dummyEnchantConsumesOneNextIntEvenWithoutThrows() {
        long seed = 0x0F1E2D3C4B5AL;
        int currentXpSeed = 0x11223344;

        // onEnchantedItem() only advances the mirror when the crack state knows the seed;
        // this is the normal state during a run
        Configs.playerCrackState = PlayerRandCracker.CrackState.CRACKED;
        PlayerRandCracker.setSeed(seed);
        PlayerRandCracker.onEnchantedItem(); // the dummy enchant

        Rand playerRand = new Rand(LCG.JAVA, seed);
        int solverDummyXpSeed = (int) playerRand.nextBits(32);

        assertEquals(PlayerRandCracker.getSeed(), playerRand.getSeed(),
                "one dummy enchant must advance the player RNG by exactly one nextInt");
        assertNotEquals(currentXpSeed, solverDummyXpSeed,
                "the seed after a dummy enchant must not be assumed equal to the pre-dummy seed");
    }

    /**
     * The lookahead formula must account for: itemThrows pre-throws, the dummy enchant (+1 step),
     * the dummy drop (+4 steps) and the window throws (+4 each). The written value is the next
     * nextInt(), which becomes the next item's table seed.
     */
    @Test
    void lookaheadBaseStepsAccountsForDummyAndDrop() {
        long seed = 0x0BADF00D1234L;
        int itemThrows = 3;
        int windowThrows = 5;

        // replay the exact sequence the automation performs
        Configs.playerCrackState = PlayerRandCracker.CrackState.CRACKED;
        PlayerRandCracker.setSeed(seed);
        for (int i = 0; i < itemThrows * 4; i++) {
            PlayerRandCracker.nextInt();          // pre-throws
        }
        PlayerRandCracker.onEnchantedItem();      // dummy enchant
        PlayerRandCracker.onDropItem();           // drop the enchanted dummy
        for (int i = 0; i < windowThrows * 4; i++) {
            PlayerRandCracker.nextInt();          // window throws
        }
        int mirrorWrittenSeed = PlayerRandCracker.nextInt(); // real enchant writes the next seed

        int solverWrittenSeed = SeedChain.dummyWrittenSeed(seed, itemThrows, windowThrows);

        assertEquals(mirrorWrittenSeed, solverWrittenSeed,
                "the lookahead base steps must cover the pre-throws, the dummy enchant and the dummy drop");
    }

    /**
     * The chained path: from the second queue entry onward no dummy is used, so an entry's enchant
     * writes {@code out(s + 4 * itemThrows)} directly. Throwing before the table is therefore
     * exactly equivalent to throwing after it - which is what lets the automation do all its throws
     * with the table closed and still prime the next entry.
     */
    @Test
    void chainPathWritesNextSeedAfterOnlyThrows() {
        long seed = 0x13572468ACE0L;
        for (int itemThrows = 0; itemThrows <= 3; itemThrows++) {
            Configs.playerCrackState = PlayerRandCracker.CrackState.CRACKED;
            PlayerRandCracker.setSeed(seed);
            for (int i = 0; i < itemThrows * 4; i++) {
                PlayerRandCracker.nextInt();       // pre-table throws
            }
            // no dummy enchant and no drop: the real enchant writes the very next draw
            int mirrorWrittenSeed = PlayerRandCracker.nextInt();

            int solverWrittenSeed = SeedChain.chainWrittenSeed(seed, itemThrows);

            assertEquals(mirrorWrittenSeed, solverWrittenSeed,
                    "the chain path must predict the written seed as out(s + 4 * throws)");
        }
    }

    /**
     * Throwing does not change the table seed - it only decides which value the next enchant
     * writes. This is the invariant that lets every throw happen before the table is opened.
     */
    @Test
    void throwingNeverChangesTheTableSeed() {
        long seed = 0x0FEDCBA98765L;
        Configs.playerCrackState = PlayerRandCracker.CrackState.CRACKED;
        PlayerRandCracker.setSeed(seed);
        // the table seed an enchant writes is a function of the PLAYER rng, not of the table, so
        // any number of throws leaves the already-known table seed untouched
        PlayerRandCracker.nextInt();
        PlayerRandCracker.onEnchantedItem();
        long afterOneItem = PlayerRandCracker.getSeed();
        for (int i = 0; i < 12; i++) {
            PlayerRandCracker.nextInt();
        }
        assertNotEquals(afterOneItem, PlayerRandCracker.getSeed(), "throws must advance the player rng");
    }

    /**
     * Both paths must scan the same throw budget: the user's configured one.
     *
     * <p>Regression history: the caps diverged during the seed-chain rewrite - the dummy path stopped
     * at 512 instead of the configured 16384, so a real run logged "no plan found within 16384 throws"
     * while having searched only 512, and reported an enchantable item as unsolvable. The two paths
     * now share a single limit so they cannot drift apart again.
     */
    @Test
    void bothPathsScanTheFullConfiguredBudget() {
        int configured = 64 * 256; // the default maxEnchantItemThrows
        assertEquals(configured, me.tianhangj.quickenchant.automation.EnchantSolver.scanLimit(configured),
                "the throw scan must use the whole configured budget");

        // values that used to be (wrongly) clamped by the old 512 chain cap
        assertEquals(2048, me.tianhangj.quickenchant.automation.EnchantSolver.scanLimit(2048),
                "a mid-sized budget must not be clamped");
        assertEquals(7, me.tianhangj.quickenchant.automation.EnchantSolver.scanLimit(7),
                "a small configured budget must be honoured exactly, never exceeded");
        assertEquals(0, me.tianhangj.quickenchant.automation.EnchantSolver.scanLimit(0),
                "zero throws means zero throws, not a hidden minimum");
    }

    /**
     * After a prediction mismatch the automation discards BOTH seeds. Dropping only the table seed
     * would leave a wrong player seed behind, and the next run would throw items to a position that
     * is wrong in exactly the same way. {@link PlayerRandCracker#forgetSeed()} must be silent about
     * it (no RNG-call event, which would re-enter the abort handling) and must leave the mirror
     * unable to pretend it still knows the seed.
     */
    @Test
    void forgetSeedDropsTheCrackStateAndIsSilent() {
        Configs.playerCrackState = PlayerRandCracker.CrackState.CRACKED;
        PlayerRandCracker.setSeed(0x0FEDCBA98765L);

        java.util.concurrent.atomic.AtomicInteger events = new java.util.concurrent.atomic.AtomicInteger();
        PlayerRandCracker.RNG_CALLED_EVENT.register(event -> events.incrementAndGet());

        PlayerRandCracker.forgetSeed();

        assertEquals(PlayerRandCracker.CrackState.UNCRACKED, Configs.playerCrackState,
                "forgetSeed must drop the player crack state");
        assertEquals(0L, PlayerRandCracker.getSeed(), "forgetSeed must erase the mirrored seed");
        assertEquals(0, events.get(), "forgetSeed must not fire the RNG-call event");

        // with the seed forgotten, further RNG calls must not be "maintained" - otherwise the
        // mirror would silently keep advancing from a seed that is no longer trusted
        PlayerRandCracker.onDropItem();
        assertEquals(PlayerRandCracker.CrackState.UNCRACKED, Configs.playerCrackState,
                "RNG calls must not re-crack a forgotten seed");
    }

    // ===== the flow specification =====
    //
    //   throws -> dummy enchant   writes S1 -> item 1 enchants with S1
    //   throws -> item 1 enchant  writes S2 -> item 2 enchants with S2
    //   throws -> item 2 enchant  writes S3 -> item 3 enchants with S3
    //
    // The first entry is the only one that may use a dummy: nothing has written a seed before it.
    // Every later entry relies on the previous enchant, and a seed that offers nothing there means
    // the chain is broken for good (throwing cannot change a seed that is already in the table).

    /**
     * The first entry is allowed to fall back to a dummy - and only the first entry.
     *
     * <p>Regression: the dummy was once removed entirely, which broke the very first item (no
     * enchant had written its seed yet), and before that a dummy fallback existed for EVERY entry,
     * which hid a broken chain instead of reporting it.
     */
    @Test
    void dummyIsUsedOnlyForTheFirstEntry() {
        // first entry: a seed that offers nothing may still be enchanted via a dummy
        assertEquals(SeedChain.Kind.DUMMY, SeedChain.decide(false, true),
                "the first entry must be allowed to use a dummy to write its seed");

        // every later entry: the previous enchant owed us a usable seed; a dummy must NOT be used
        assertEquals(SeedChain.Kind.BROKEN, SeedChain.decide(false, false),
                "a later entry must never fall back to a dummy - that would hide a broken chain");

        // a usable seed is always the cheaper chain step, even on the first entry
        assertEquals(SeedChain.Kind.CHAIN, SeedChain.decide(true, true),
                "an unnecessary dummy enchant wastes a table operation");
        assertEquals(SeedChain.Kind.CHAIN, SeedChain.decide(true, false),
                "the steady state: the seed in the table offers the item");
    }

    /** Throwing is 4 player-RNG steps, an enchant is 1, and the dummy drop is another 4. */
    @Test
    void chainStepWalksFourStepsPerThrow() {
        assertEquals(4, SeedChain.THROW_STEPS, "one thrown item draws four velocity values");
        assertEquals(1, SeedChain.ENCHANT_STEPS, "one enchant draws exactly one nextInt");
        assertEquals(4, SeedChain.DROP_STEPS, "dropping an item draws four velocity values");
        assertEquals(5, SeedChain.DUMMY_TO_REAL_STEPS, "dummy enchant + dummy drop = 5 steps");

        for (int throwsCount = 0; throwsCount <= 4; throwsCount++) {
            assertEquals(4L * throwsCount, SeedChain.chainSteps(throwsCount),
                    "the chain path spends 4 steps per throw and nothing else");
            assertEquals(4L * throwsCount + 5, SeedChain.dummySteps(throwsCount, 0),
                    "the dummy path adds the enchant and the drop before this item's own enchant");
            assertEquals(4L * throwsCount + 5, SeedChain.dummySteps(throwsCount + 1, 0) - 4,
                    "one more throw shifts the real enchant by exactly one throw");
        }
    }

    /**
     * The candidate throws of a search must be the seeds the game really writes.
     *
     * <p>Regression, and the one that actually broke a live run: the throw search walked ONE shared
     * {@link Rand} - {@code advance(4)} then {@code nextBits(32)} per candidate. The probe itself
     * consumes a draw, so candidate n was really {@code out(base + 5n)} instead of
     * {@code out(base + 4n)}. The drift is invisible for small n and then diverges completely: the
     * solver accepted a window of 10 throws whose 10th candidate it computed as A350381E, while the
     * game wrote 222E2480, and the next queue entry could never find its seed.
     *
     * <p>Pinned with the real numbers from that run so the accounting cannot silently drift again.
     */
    @Test
    void throwSearchCandidatesMatchTheSeedsTheGameWrites() {
        long playerSeed = 0x0402914F8CF4L;   // player RNG after the manual crack enchant
        long baseSteps = 13 * 4 + 5;         // 13 pre-throws, dummy enchant, dummy drop

        // the game: 10 window throws, enchanted, wrote 222E2480
        assertEquals(0x222E2480, SeedChain.writtenSeedAfterThrows(playerSeed, baseSteps, 10),
                "10 window throws must write the seed the live run actually wrote");

        // the same seed the old loop reported for n = 10 - proof of the drift it introduced
        assertEquals(0xA350381E, SeedChain.writtenSeed(playerSeed, baseSteps + 5L * 10),
                "the drifted value must no longer be what a 10-throw candidate returns");

        // every candidate is independent of how many were probed before it
        int[] expected = new int[12];
        for (int n = 0; n < expected.length; n++) {
            expected[n] = SeedChain.writtenSeedAfterThrows(playerSeed, baseSteps, n);
        }
        for (int n = expected.length - 1; n >= 0; n--) {
            assertEquals(expected[n], SeedChain.writtenSeedAfterThrows(playerSeed, baseSteps, n),
                    "candidate " + n + " must not depend on evaluation order");
        }
    }

    /**
     * The throw search only ever returns a count whose seed really satisfies the predicate, and it
     * returns -1 when nothing does.
     */
    @Test
    void findThrowsReturnsACountWhoseSeedMatches() {
        long playerSeed = 0x0402914F8CF4L;
        long baseSteps = 13 * 4 + 5;

        // ask for the count that writes exactly one known seed
        int target = 7;
        int wanted = SeedChain.writtenSeedAfterThrows(playerSeed, baseSteps, target);
        assertEquals(target, SeedChain.findThrows(playerSeed, baseSteps, 11, seed -> seed == wanted),
                "the search must find the count that writes the wanted seed");

        // first match wins: with a predicate that accepts everything, that is zero throws
        assertEquals(0, SeedChain.findThrows(playerSeed, baseSteps, 11, seed -> true),
                "a predicate the very first candidate satisfies means zero throws");
        assertEquals(-1, SeedChain.findThrows(playerSeed, baseSteps, 11, seed -> false),
                "an unsatisfiable predicate must report -1, not a bogus count");
    }

    /**
     * The dummy plan's two seeds: the dummy enchant writes the seed THIS item is enchanted with,
     * and this item's own enchant (after the drop and the window throws) writes the NEXT entry's.
     */
    @Test
    void dummyPlanAccountsForEnchantAndDrop() {
        long seed = 0x0C0FFEE12345L;
        assertEquals(SeedChain.dummySeed(seed, 3), SeedChain.writtenSeed(seed, 4L * 3),
                "the real item is enchanted with what the dummy enchant wrote");
        assertEquals(SeedChain.dummyWrittenSeed(seed, 3, 2), SeedChain.writtenSeed(seed, 4L * 3 + 5 + 4L * 2),
                "this item's enchant lands after the drop (+4) and the window throws (4 each)");

        for (int n0 = 0; n0 <= 2; n0++) {
            for (int windowThrows = 0; windowThrows <= 3; windowThrows++) {
                Configs.playerCrackState = PlayerRandCracker.CrackState.CRACKED;
                PlayerRandCracker.setSeed(seed);
                for (int i = 0; i < n0 * 4; i++) {
                    PlayerRandCracker.nextInt();      // pre-throws
                }
                PlayerRandCracker.onEnchantedItem();  // dummy enchant -> this item's seed
                PlayerRandCracker.onDropItem();       // drop the enchanted dummy
                for (int i = 0; i < windowThrows * 4; i++) {
                    PlayerRandCracker.nextInt();      // window throws inside the table
                }
                int mirrorNextSeed = PlayerRandCracker.nextInt(); // this item's enchant writes it

                assertEquals(SeedChain.dummyWrittenSeed(seed, n0, windowThrows), mirrorNextSeed,
                        "dummy path, n0=" + n0 + " windowThrows=" + windowThrows);
            }
        }
    }
}
