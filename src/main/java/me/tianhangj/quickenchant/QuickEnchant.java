package me.tianhangj.quickenchant;

import me.tianhangj.quickenchant.automation.EnchantAutomation;
import me.tianhangj.quickenchant.command.CrackRNGCommand;
import me.tianhangj.quickenchant.config.ModConfig;
import me.tianhangj.quickenchant.config.QuickEnchantConfigGui;
import me.tianhangj.quickenchant.config.QuickEnchantInputHandler;
import me.tianhangj.quickenchant.features.EnchantmentCracker;
import me.tianhangj.quickenchant.features.PlayerRandCracker;
import me.tianhangj.quickenchant.gui.EnchantSelectScreen;
import me.tianhangj.quickenchant.inventory.InventoryScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuickEnchant implements ClientModInitializer {
	public static final String MOD_ID = "quick-enchant";
	public static final String MOD_NAME = "Quick Enchant";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Set once the auto-opened selection screen has been shown for the current crack session. */
	private boolean selectionScreenShown = false;

	@Override
	public void onInitializeClient() {
		PlayerRandCracker.registerEvents();
		EnchantmentCracker.registerEvents();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> CrackRNGCommand.register(dispatcher));

		fi.dy.masa.malilib.event.InitializationHandler.getInstance().registerInitializationHandler(new fi.dy.masa.malilib.interfaces.IInitializationHandler() {
			@Override
			public void registerModHandlers() {
				fi.dy.masa.malilib.config.ConfigManager.getInstance().registerConfigHandler(MOD_ID, ModConfig.getInstance());
				QuickEnchantInputHandler.register();
				fi.dy.masa.malilib.registry.Registry.CONFIG_SCREEN.registerConfigScreenFactory(
						new fi.dy.masa.malilib.util.data.ModInfo(MOD_ID, MOD_NAME, QuickEnchantConfigGui::new));

				registerHotkeyCallbacks();
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
		LOGGER.info("QuickEnchant initialized");
	}

	private void registerHotkeyCallbacks() {
		ModConfig.Generic.OPEN_SELECT_GUI.getKeybind().setCallback((action, key) -> {
			if (net.minecraft.client.Minecraft.getInstance().player != null && EnchantAutomation.getRunning() == null) {
				openSelectionScreen();
				return true;
			}
			return false;
		});
		ModConfig.Generic.CONFIRM_EXECUTE.getKeybind().setCallback((action, key) -> {
			if (EnchantAutomation.getRunning() == null && EnchantAutomation.hasPending()) {
				return EnchantAutomation.startPending();
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

	private void openSelectionScreen() {
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		InventoryScanner.Snapshot snapshot = InventoryScanner.scan(mc.player);
		if (snapshot.itemBoxes().isEmpty()) {
			me.tianhangj.quickenchant.command.ClientCommandHelper.sendError(
					net.minecraft.network.chat.Component.translatable("quick-enchant.run.noItems"));
			return;
		}
		mc.setScreen(new EnchantSelectScreen(snapshot, mc.screen));
	}

	private void onClientTick(net.minecraft.client.Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			selectionScreenShown = false;
			return;
		}

		// requirement: after all enchantable items are detected (both seeds cracked), pop up the
		// selection screen once per crack session
		boolean cracked = Configs.enchCrackState == EnchantmentCracker.CrackState.CRACKED
				&& Configs.playerCrackState.knowsSeed();
		if (!cracked) {
			// crack lost (relog/world change): allow the screen to pop again on the next crack
			selectionScreenShown = false;
			return;
		}

		// a running automation must neither trigger nor reset the auto-open flag - otherwise an
		// aborted run would re-open the selection screen
		if (EnchantAutomation.getRunning() != null) {
			return;
		}

		if (!selectionScreenShown && Configs.enchantingPrediction && mc.screen == null) {
			InventoryScanner.Snapshot snapshot = InventoryScanner.scan(mc.player);
			if (!snapshot.itemBoxes().isEmpty()) {
				selectionScreenShown = true;
				mc.setScreen(new EnchantSelectScreen(snapshot, null));
			}
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
