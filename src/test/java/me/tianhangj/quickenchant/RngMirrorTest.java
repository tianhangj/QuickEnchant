package me.tianhangj.quickenchant;

import com.seedfinding.mcseed.lcg.LCG;
import com.seedfinding.mcseed.rand.Rand;
import me.tianhangj.quickenchant.features.PlayerRandCracker;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for the player RNG mirror: the whole automation relies on the mirror
 * matching the server's player RNG exactly.
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
}
