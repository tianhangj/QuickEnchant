package me.tianhangj.quickenchant.config;

import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import me.tianhangj.quickenchant.QuickEnchant;

public class QuickEnchantInputHandler implements IKeybindProvider {
    private static final QuickEnchantInputHandler INSTANCE = new QuickEnchantInputHandler();

    private QuickEnchantInputHandler() {
    }

    public static QuickEnchantInputHandler getInstance() {
        return INSTANCE;
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (IHotkey hotkey : ModConfig.Generic.HOTKEY_LIST) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory(QuickEnchant.MOD_NAME, QuickEnchant.MOD_ID + ".hotkeys.category.generic", ModConfig.Generic.HOTKEY_LIST);
    }

    public static void register() {
        InputEventHandler.getKeybindManager().registerKeybindProvider(getInstance());
    }
}
