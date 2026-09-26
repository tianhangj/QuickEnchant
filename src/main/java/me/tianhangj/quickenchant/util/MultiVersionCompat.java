package me.tianhangj.quickenchant.util;

import net.minecraft.SharedConstants;
import net.minecraft.world.item.Item;

/**
 * Slimmed-down port of clientcommands' MultiVersionCompat: QuickEnchant targets a single
 * Minecraft version (26.1.2), so only the vanilla protocol implementation is kept.
 */
public abstract class MultiVersionCompat {
    public static final int V1_7_2 = 4;
    public static final int V1_8 = 47;
    public static final int V1_9 = 107;
    public static final int V1_11 = 315;
    public static final int V1_11_1 = 316;
    public static final int V1_13 = 393;
    public static final int V1_13_2 = 404;
    public static final int V1_14 = 477;
    public static final int V1_14_2 = 485;
    public static final int V1_15 = 573;
    public static final int V1_15_2 = 578;
    public static final int V1_16 = 735;
    public static final int V1_17 = 755;
    public static final int V1_18 = 757;
    public static final int V1_18_2 = 758;
    public static final int V1_20 = 763;
    public static final int V1_20_6 = 766;
    public static final int V1_21 = 767;
    public static final int V1_21_2 = 768;
    public static final int V1_21_9 = 773;

    public abstract int getProtocolVersion();

    public abstract String getProtocolName();

    public boolean doesItemExist(Item item) {
        return true;
    }

    public static final MultiVersionCompat INSTANCE = new MultiVersionCompat() {
        @Override
        public int getProtocolVersion() {
            return SharedConstants.getProtocolVersion();
        }

        @Override
        public String getProtocolName() {
            return SharedConstants.getCurrentVersion().name();
        }
    };
}
