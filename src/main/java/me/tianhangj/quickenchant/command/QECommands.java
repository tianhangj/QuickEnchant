package me.tianhangj.quickenchant.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import me.tianhangj.quickenchant.QuickEnchant;
import me.tianhangj.quickenchant.automation.EnchantAutomation;
import me.tianhangj.quickenchant.automation.PrepareFlow;
import me.tianhangj.quickenchant.command.ClientCommandHelper;
import me.tianhangj.quickenchant.gui.BoxSelectScreen;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

/**
 * /qeprepare - step 1 of the flow: opens the box-selection screen (inheriting the previous
 * selection when the last run did not end normally).
 * /qestart - final step: starts the automation with the confirmed boxes and enchantments.
 */
public class QECommands {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("qeprepare").executes(ctx -> {
            prepare();
            return Command.SINGLE_SUCCESS;
        }));
        dispatcher.register(literal("qestart").executes(ctx -> {
            start();
            return Command.SINGLE_SUCCESS;
        }));
    }

    /** Step 1: open the box-selection screen, inheriting the last selection after an abnormal end. */
    public static void prepare() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            ClientCommandHelper.sendError(Component.translatable("quick-enchant.run.needWorld"));
            return;
        }
        if (EnchantAutomation.getRunning() != null) {
            ClientCommandHelper.sendError(Component.translatable("quick-enchant.run.conflict"));
            return;
        }
        boolean inherited = !me.tianhangj.quickenchant.config.FlowState.lastRunNormalEnd
                && !me.tianhangj.quickenchant.config.FlowState.selectedBoxes.isEmpty();
        PrepareFlow.start();
        // defer: /qeprepare runs inside the chat screen's key handling - setting the screen
        // synchronously would be replaced when the chat finishes processing the submit
        mc.execute(() -> {
            me.tianhangj.quickenchant.util.Compat.setScreen(mc, new BoxSelectScreen(inherited));
            QuickEnchant.LOGGER.info("prepare: BoxSelectScreen opened (inherited={}, marked={})",
                    inherited, me.tianhangj.quickenchant.config.FlowState.selectedBoxes.size());
        });
        if (inherited) {
            ClientCommandHelper.sendFeedback(Component.translatable("quick-enchant.flow.inherited"));
        }
        ClientCommandHelper.sendFeedback(Component.translatable("quick-enchant.flow.prepared"));
    }

    /** Final step: start the automation with the confirmed boxes and enchantment selections. */
    public static void start() {
        if (PrepareFlow.stage() != PrepareFlow.Stage.AWAIT_CRACK
                || PrepareFlow.confirmedConfigs() == null) {
            ClientCommandHelper.sendError(Component.translatable("quick-enchant.flow.notPrepared"));
            return;
        }
        EnchantAutomation.beginRun(PrepareFlow.selectedSlots(), PrepareFlow.confirmedConfigs());
    }
}
