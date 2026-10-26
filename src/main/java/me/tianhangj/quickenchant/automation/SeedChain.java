package me.tianhangj.quickenchant.automation;

import com.seedfinding.mcseed.lcg.LCG;
import com.seedfinding.mcseed.rand.Rand;

/**
 * The player-RNG step accounting the seed chain is built on, kept in one place so the solver and
 * the tests cannot drift apart.
 *
 * <h2>The three things that move the player RNG</h2>
 * <ul>
 *   <li><b>item throw</b> - the thrown item's velocity draws 4 values, so one throw is
 *       {@link #THROW_STEPS} steps;</li>
 *   <li><b>enchant</b> - {@code Player.onEnchantmentPerformed} does
 *       {@code enchantmentSeed = random.nextInt()}, i.e. {@link #ENCHANT_STEPS} step, and the value
 *       it draws becomes the table seed the NEXT enchant reads;</li>
 *   <li><b>dropping the enchanted dummy</b> - another 4 steps ({@link #DROP_STEPS}).</li>
 * </ul>
 *
 * <p>Throwing never changes the table seed. It only decides which value the upcoming enchant
 * writes - which is why the automation can throw everything before opening the table, and why a
 * broken chain (a seed that offers nothing) can never be repaired by throwing.
 *
 * <h2>The two shapes of a step</h2>
 * <pre>
 *   chain path (every entry after the first):
 *     [n pre-table throws] enchant -&gt; writes out(playerSeed + 4n), this item uses the seed already in the table
 *
 *   dummy path (the first entry of a run only):
 *     [n pre-throws] dummy enchant -&gt; writes out(playerSeed + 4n) = this item's seed
 *     [drop dummy +4] [w window throws] this item's enchant -&gt; writes out(playerSeed + 4n + 5 + 4w)
 * </pre>
 *
 * <p>The dummy path exists only because a run starts with nothing having written a seed yet.
 */
public final class SeedChain {
    /** One thrown item: 4 draws for the spawned item's velocity. */
    public static final int THROW_STEPS = 4;
    /** One performed enchant: {@code enchantmentSeed = random.nextInt()}. */
    public static final int ENCHANT_STEPS = 1;
    /** Dropping the enchanted dummy copy. */
    public static final int DROP_STEPS = 4;
    /** Steps from the dummy enchant to this item's own enchant, before any window throw. */
    public static final int DUMMY_TO_REAL_STEPS = ENCHANT_STEPS + DROP_STEPS;

    /** How an entry has to be enchanted, given whether the current table seed already offers it. */
    public enum Kind {
        /** The seed in the table offers the item: throw to prime the next entry, then enchant. */
        CHAIN,
        /** The seed offers nothing, but this is the first entry: a dummy enchant writes one. */
        DUMMY,
        /** The seed offers nothing and a previous enchant was supposed to provide one: unrecoverable. */
        BROKEN
    }

    private SeedChain() {
    }

    /**
     * The rule that drives the whole flow: a dummy may only ever be used for the FIRST entry of a
     * run. Every later entry's seed has to come from the previous entry's enchant - the table seed
     * cannot be changed by throwing, so falling back to a dummy there would silently break the
     * chain instead of proving it broken.
     *
     * <p>A usable seed in the table is always taken as the chain step, even on the first entry: an
     * enchant that is not needed is a wasted table operation.
     */
    public static Kind decide(boolean seedOffersItem, boolean firstOfRun) {
        if (seedOffersItem) {
            return Kind.CHAIN;
        }
        return firstOfRun ? Kind.DUMMY : Kind.BROKEN;
    }

    /** Steps from the current player RNG position to this item's own enchant (chain path). */
    public static long chainSteps(int itemThrows) {
        return (long) THROW_STEPS * itemThrows;
    }

    /** Steps from the current player RNG position to this item's own enchant (dummy path). */
    public static long dummySteps(int itemThrows, int windowThrows) {
        return (long) THROW_STEPS * itemThrows + DUMMY_TO_REAL_STEPS + (long) THROW_STEPS * windowThrows;
    }

    /**
     * The seed the dummy enchant writes - and therefore the seed THIS item is enchanted with on the
     * dummy path.
     */
    public static int dummySeed(long playerSeed, int itemThrows) {
        return writtenSeed(playerSeed, chainSteps(itemThrows));
    }

    /** The seed this item's own enchant writes, i.e. the next entry's table seed. */
    public static int chainWrittenSeed(long playerSeed, int itemThrows) {
        return writtenSeed(playerSeed, chainSteps(itemThrows));
    }

    /** The seed this item's own enchant writes on the dummy path, i.e. the next entry's table seed. */
    public static int dummyWrittenSeed(long playerSeed, int itemThrows, int windowThrows) {
        return writtenSeed(playerSeed, dummySteps(itemThrows, windowThrows));
    }

    /**
     * The 32-bit value an enchant writes when the player RNG sits {@code steps} steps past
     * {@code playerSeed}: exactly the draw {@code Player.onEnchantmentPerformed} takes.
     *
     * <p>The draw is taken from a FRESH Rand every call on purpose. A Rand is stateful: asking it
     * for a value moves it, so any code that probes candidate seeds through one shared Rand gets
     * each candidate shifted by every probe before it. That is not a cosmetic detail - it silently
     * made the solver validate throws against seeds the game would never write (see
     * {@link #findThrows}).
     */
    public static int writtenSeed(long playerSeed, long steps) {
        Rand playerRand = new Rand(LCG.JAVA, playerSeed);
        playerRand.advance(steps);
        return (int) playerRand.nextBits(32);
    }

    /**
     * The seed written by an enchant that happens {@code throwsCount} item-throws after a point
     * which is itself {@code baseSteps} steps past {@code playerSeed}.
     */
    public static int writtenSeedAfterThrows(long playerSeed, long baseSteps, int throwsCount) {
        return writtenSeed(playerSeed, baseSteps + (long) THROW_STEPS * throwsCount);
    }

    /**
     * Smallest throw count in {@code [0, maxThrows]} at which an enchant would write a seed
     * {@code accepts} takes, or -1 when no count works.
     *
     * <p>Every candidate is derived from {@code playerSeed} and {@code baseSteps} alone, so the
     * n-th candidate is the seed the game really writes after n throws - independent of how many
     * candidates were already tried. This is the one place that search may live: it used to be
     * open-coded on top of a single advanced {@link Rand}, and each {@code nextBits} call moved
     * that Rand one draw further than the throw it was meant to model, so candidate n was really
     * {@code out(base + 5n)}. Plans were then accepted against seeds the game never writes, and
     * the next queue entry always found the chain broken.
     *
     * @param accepts predicate on the seed the enchant would write
     */
    public static int findThrows(long playerSeed, long baseSteps, int maxThrows,
                                 java.util.function.IntPredicate accepts) {
        for (int n = 0; n <= maxThrows; n++) {
            if (accepts.test(writtenSeedAfterThrows(playerSeed, baseSteps, n))) {
                return n;
            }
        }
        return -1;
    }
}
