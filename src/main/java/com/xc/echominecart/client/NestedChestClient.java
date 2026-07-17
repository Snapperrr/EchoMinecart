package com.xc.echominecart.client;

import com.xc.echominecart.EchoMinecartRegistry;
import com.xc.echominecart.NestedChestMod;
import com.xc.echominecart.client.screen.ConnectedChestScreen;
import com.xc.echominecart.client.screen.EchoMinecartSettingsScreen;
import com.xc.echominecart.client.screen.SpeedRailSettingsScreen;
import com.xc.echominecart.network.CarriageSyncPayload;
import com.xc.echominecart.network.NestedChestSyncPayload;
import com.xc.echominecart.network.SpeedRailOpenPayload;
import com.xc.echominecart.network.TripSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class NestedChestClient implements ClientModInitializer {
	private static KeyBinding settingsKeyBinding;

	@Override
	public void onInitializeClient() {
		NestedChestClientConfig.initialize();
		LateralRailModelLoader.initialize();
		registerSettingsControls();
		BlockRenderLayerMap.INSTANCE.putBlocks(
				RenderLayer.getCutout(),
				EchoMinecartRegistry.ECHO_RAIL,
				EchoMinecartRegistry.ECHO_POWERED_RAIL,
				EchoMinecartRegistry.SPEED_RAIL);
		HandledScreens.register(NestedChestMod.CONNECTED_CHEST_SCREEN_HANDLER, ConnectedChestScreen::new);
		ClientPlayNetworking.registerGlobalReceiver(NestedChestSyncPayload.ID, (payload, context) ->
				context.client().execute(() -> NestedChestOverlay.sync(payload.path(), payload.stacks())));
		ClientPlayNetworking.registerGlobalReceiver(CarriageSyncPayload.ID, (payload, context) ->
				context.client().execute(() -> {
					if (context.client().world != null) {
						CarriageClientVisuals.updateShape(payload.anchorId(), payload.anchorCell(), payload.cells(), payload.chestCount(), context.client().world.getTime());
					}
				}));
		ClientPlayNetworking.registerGlobalReceiver(TripSyncPayload.ID, (payload, context) ->
				context.client().execute(() -> TripClientVisuals.update(payload.entityId(), payload.tripped(), payload.yaw())));
		ClientPlayNetworking.registerGlobalReceiver(SpeedRailOpenPayload.ID, (payload, context) ->
				context.client().execute(() -> context.client().setScreen(new SpeedRailSettingsScreen(
						context.client().currentScreen, BlockPos.fromLong(payload.pos()), payload.speed()))));
	}

	private static void registerSettingsControls() {
		settingsKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.echominecart.open_settings",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_O,
				"category.echominecart"));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (settingsKeyBinding.wasPressed()) {
				client.setScreen(new EchoMinecartSettingsScreen(client.currentScreen));
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof GameMenuScreen) {
				int buttonWidth = Math.min(160, Math.max(100, scaledWidth - 20));
				Screens.getButtons(screen).add(ButtonWidget.builder(Text.translatable("button.echominecart.settings"),
								button -> client.setScreen(new EchoMinecartSettingsScreen(screen)))
						.dimensions(scaledWidth - buttonWidth - 10, scaledHeight - 30, buttonWidth, 20)
						.build());
			}
		});
	}
}
