package me.tianhangj.quickenchant.gui;

import me.tianhangj.quickenchant.Configs;
import me.tianhangj.quickenchant.QuickEnchant;
import me.tianhangj.quickenchant.automation.EnchantAutomation;
import me.tianhangj.quickenchant.automation.PrepareFlow;
import me.tianhangj.quickenchant.features.EnchantmentCracker;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent top-right status overlay for the prepare flow: crack states plus the hint for the
 * next step. Visible everywhere (not only inside the enchanting table screen) from /qeprepare
 * until the flow ends.
 */
public class StatusHud implements HudElement {
    private static final Identifier ID = QuickEnchant.id("status_hud");

    public static void register() {
        HudElementRegistry.addLast(ID, new StatusHud());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        PrepareFlow.Stage stage = PrepareFlow.stage();
        if (stage == PrepareFlow.Stage.INACTIVE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        Font font = mc.font;

        Component cracked = Component.translatable("quick-enchant.flow.statusCracked").withStyle(ChatFormatting.GREEN);
        Component uncracked = Component.translatable("quick-enchant.flow.statusUncracked").withStyle(ChatFormatting.RED);

        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("quick-enchant.flow.statusPlayerRng",
                Configs.playerCrackState.knowsSeed() ? cracked : uncracked));
        lines.add(Component.translatable("quick-enchant.flow.statusXpSeed",
                Configs.enchCrackState == EnchantmentCracker.CrackState.CRACKED ? cracked : uncracked));
        lines.add(Component.empty());

        if (stage == PrepareFlow.Stage.RUNNING) {
            EnchantAutomation automation = EnchantAutomation.getRunning();
            if (automation != null) {
                lines.add(Component.translatable("quick-enchant.flow.statusRunning",
                        automation.statusCurrentIndex() + 1, automation.statusQueueSize())
                        .withStyle(ChatFormatting.YELLOW));
                lines.add(Component.literal(automation.statusPhase()).withStyle(ChatFormatting.GRAY));
            }
        } else {
            switch (stage) {
                case BOX_SELECT -> lines.add(Component.translatable("quick-enchant.flow.hintStageBoxes")
                        .withStyle(ChatFormatting.YELLOW));
                case ENCHANT_SELECT -> lines.add(Component.translatable("quick-enchant.flow.hintStageEnchants")
                        .withStyle(ChatFormatting.YELLOW));
                case AWAIT_CRACK -> {
                    if (!Configs.playerCrackState.knowsSeed()) {
                        lines.add(Component.translatable("quick-enchant.flow.hintCrackRng")
                                .withStyle(ChatFormatting.YELLOW));
                    }
                    if (Configs.enchCrackState != EnchantmentCracker.CrackState.CRACKED) {
                        lines.add(Component.translatable("quick-enchant.flow.hintXpSeed")
                                .withStyle(ChatFormatting.YELLOW));
                    }
                    if (Configs.playerCrackState.knowsSeed()
                            && Configs.enchCrackState == EnchantmentCracker.CrackState.CRACKED) {
                        lines.add(Component.translatable("quick-enchant.flow.hintReady")
                                .withStyle(ChatFormatting.GREEN));
                    }
                }
                default -> {
                }
            }
        }

        int y = 4;
        for (Component line : lines) {
            int x = graphics.guiWidth() - font.width(line) - 4;
            graphics.text(font, line, x, y, 0xFFFFFFFF, true);
            y += font.lineHeight + 2;
        }
    }
}
