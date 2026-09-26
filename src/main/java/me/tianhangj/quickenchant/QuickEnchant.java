package me.tianhangj.quickenchant;

import me.tianhangj.quickenchant.automation.EnchantAutomation;
import me.tianhangj.quickenchant.command.CrackRNGCommand;
import me.tianhangj.quickenchant.command.QECommands;
import me.tianhangj.quickenchant.config.FlowState;
import me.tianhangj.quickenchant.config.ModConfig;
import me.tianhangj.quickenchant.config.QuickEnchantConfigGui;
import me.tianhangj.quickenchant.config.QuickEnchantInputHandler;
import me.tianhangj.quickenchant.features.EnchantmentCracker;
import me.tianhangj.quickenchant.features.PlayerRandCracker;
import me.tianhangj.quickenchant.gui.StatusHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuickEnchant implements ClientModInitializer {
	public static final String MOD_ID = "quick-enchant";
	public static final String MOD_NAME = "Quick Enchant";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		PlayerRandCracker.registerEvents();
		EnchantmentCracker.registerEvents();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			CrackRNGCommand.register(dispatcher);
			QECommands.register(dispatcher);
		});

		fi.dy.masa.malilib.event.InitializationHandler.getInstance().registerInitializationHandler(new fi.dy.masa.malilib.interfaces.IInitializationHandler() {
			@Override
			public void registerModHandlers() {
				fi.dy.masa.malilib.config.ConfigManager.getInstance().registerConfigHandler(MOD_ID, ModConfig.getInstance());
				QuickEnchantInputHandler.register();
				fi.dy.masa.malilib.registry.Registry.CONFIG_SCREEN.registerConfigScreenFactory(
						new fi.dy.masa.malilib.util.data.ModInfo(MOD_ID, MOD_NAME, QuickEnchantConfigGui::new));
				StatusHud.register();
				FlowState.load();

				registerHotkeyCallbacks();
			}
		});

		LOGGER.info("QuickEnchant initialized");
	}

	private void registerHotkeyCallbacks() {
		ModConfig.Generic.OPEN_SELECT_GUI.getKeybind().setCallback((action, key) -> {
			if (net.minecraft.client.Minecraft.getInstance().player != null && EnchantAutomation.getRunning() == null) {
				QECommands.prepare();
				return true;
			}
			return false;
		});
		ModConfig.Generic.CONFIRM_EXECUTE.getKeybind().setCallback((action, key) -> {
			if (EnchantAutomation.getRunning() == null) {
				QECommands.start();
				return true;
			}
			return false;
		});
		ModConfig.Generic.ABORT_AUTOMATION.getKeybind().setCallback((action, key) -> {
			if (EnchantAutomation.getRunning() != null) {
				EnchantAutomation.abort("quick-enchant.run.aborted");
				return true;
			}
			return false;
		});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
