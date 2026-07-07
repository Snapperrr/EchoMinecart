package com.xc.echominecart.client;

import com.xc.echominecart.EchoMinecartRegistry;
import com.xc.echominecart.NestedChestMod;
import com.xc.echominecart.client.screen.ConnectedChestScreen;
import com.xc.echominecart.network.CarriageSyncPayload;
import com.xc.echominecart.network.NestedChestSyncPayload;
import com.xc.echominecart.network.TripSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.render.RenderLayer;

public class NestedChestClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		NestedChestClientConfig.initialize();
		BlockRenderLayerMap.INSTANCE.putBlocks(
				RenderLayer.getCutout(),
				EchoMinecartRegistry.ECHO_RAIL,
				EchoMinecartRegistry.ECHO_POWERED_RAIL,
				EchoMinecartRegistry.ECHO_DETECTOR_RAIL,
				EchoMinecartRegistry.ECHO_ACTIVATOR_RAIL);
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
	}
}
