package me.tianhangj.quickenchant;

import com.seedfinding.mcseed.lcg.LCG;
import com.seedfinding.mcseed.rand.Rand;
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

        int baseSteps = 4 * itemThrows + 5; // dummy enchant (1) + drop (4)
        Rand playerRand = new Rand(LCG.JAVA, seed);
        playerRand.advance(baseSteps + 4L * windowThrows);
        int solverWrittenSeed = (int) playerRand.nextBits(32);

        assertEquals(mirrorWrittenSeed, solverWrittenSeed,
                "the lookahead base steps must cover the pre-throws, the dummy enchant and the dummy drop");
    }
}
