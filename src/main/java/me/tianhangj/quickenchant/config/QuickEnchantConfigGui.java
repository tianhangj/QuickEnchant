package me.tianhangj.quickenchant.config;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import me.tianhangj.quickenchant.QuickEnchant;

import java.util.List;

public class QuickEnchantConfigGui extends GuiConfigsBase {
    public QuickEnchantConfigGui() {
        super(10, 50, QuickEnchant.MOD_ID, null, "quick-enchant.gui.title.configs");
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        return ConfigOptionWrapper.createFor(ModConfig.Generic.OPTIONS);
    }
}
