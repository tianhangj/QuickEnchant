package me.tianhangj.quickenchant.automation;

import java.util.ArrayList;
import java.util.List;

/**
 * Session stage machine for the /qeprepare flow:
 * BOX_SELECT (mark the enchantable-item boxes) -> ENCHANT_SELECT (per-item enchantments)
 * -> AWAIT_CRACK (player does /qecrackrng + one dummy enchant in the table) -> RUNNING (/qestart).
 */
public class PrepareFlow {
    public enum Stage {
        INACTIVE,
        BOX_SELECT,
        ENCHANT_SELECT,
        AWAIT_CRACK,
        RUNNING
    }

    private static Stage stage = Stage.INACTIVE;
    private static final List<Integer> selectedSlots = new ArrayList<>();
    private static List<EnchantAutomation.TypeConfig> confirmedConfigs;

    public static Stage stage() {
        return stage;
    }

    public static List<Integer> selectedSlots() {
        return selectedSlots;
    }

    public static List<EnchantAutomation.TypeConfig> confirmedConfigs() {
        return confirmedConfigs;
    }

    /** /qeprepare (or the hotkey): back to step 1, box selection. */
    public static void start() {
        selectedSlots.clear();
        confirmedConfigs = null;
        stage = Stage.BOX_SELECT;
    }

    public static void confirmBoxes(List<Integer> slots) {
        selectedSlots.clear();
        selectedSlots.addAll(slots);
        stage = Stage.ENCHANT_SELECT;
    }

    public static void confirmEnchants(List<EnchantAutomation.TypeConfig> configs) {
        confirmedConfigs = configs;
        stage = Stage.AWAIT_CRACK;
    }

    public static void beginRun() {
        stage = Stage.RUNNING;
    }

    public static void endRun() {
        stage = Stage.INACTIVE;
        confirmedConfigs = null;
    }
}
