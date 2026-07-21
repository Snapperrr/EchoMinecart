package com.xc.echominecart.client;

import com.xc.echominecart.EchoMinecartRegistry;
import com.xc.echominecart.NestedChestMod;
import com.xc.echominecart.client.screen.ConnectedChestScreen;
import com.xc.echominecart.client.screen.EchoMinecartSettingsScreen;
import com.xc.echominecart.client.screen.SpeedRailSettingsScreen;
import com.xc.echominecart.client.screen.RingVehicleScreen;
import com.xc.echominecart.client.screen.RingVehicleSettingsScreen;
import com.xc.echominecart.network.CarriageSyncPayload;
import com.xc.echominecart.network.NestedChestSyncPayload;
import com.xc.echominecart.network.SpeedRailOpenPayload;
import com.xc.echominecart.network.TripSyncPayload;
import com.xc.echominecart.network.RingVehicleActionPayload;
import com.xc.echominecart.network.RingVehicleAbilityPayload;
import com.xc.echominecart.network.RingVehicleControlPayload;
import com.xc.echominecart.network.RingVehicleMiningPayload;
import com.xc.echominecart.ringvehicle.RingVehicleEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Registers client screens, renderers, key bindings, HUD state, and validated control payloads. */
public class NestedChestClient implements ClientModInitializer {
	private static KeyBinding settingsKeyBinding;
	private static KeyBinding ringGearKeyBinding;
	private static KeyBinding ringInventoryKeyBinding;
	private static KeyBinding ringDashKeyBinding;
	private static KeyBinding ringSmashKeyBinding;
	private static KeyBinding ringClutchKeyBinding;
	private static final int JUMP_HUD_HIDDEN = 0;
	private static final int JUMP_HUD_CHARGING = 1;
	private static final int JUMP_HUD_RELEASING = 2;
	private static final int DASH_MAX_CHARGE_TICKS = 30;
	private static final int FLIGHT_SMASH_MAX_CHARGE_TICKS = 40;
	private static final int ABILITY_SOCKET_SIZE = 18;
	private static final int ABILITY_SOCKET_OVERLAP = 5;
	private static final ItemStack JUMP_HUD_ICON = Items.RABBIT_FOOT.getDefaultStack();
	private static final ItemStack DASH_HUD_ICON = Items.SUGAR.getDefaultStack();
	private static final ItemStack SMASH_HUD_ICON = Items.MACE.getDefaultStack();
	private static int ringJumpChargeTicks;
	private static boolean ringJumpWasPressed;
	private static int ringJumpHudState;
	private static int ringJumpHudTicks;
	private static int ringJumpFullTicks;
	private static float ringJumpHudCharge;
	private static float ringJumpReleaseStartCharge;
	private static int ringDashChargeTicks;
	private static boolean ringDashWasPressed;
	private static int ringDashHudState;
	private static int ringDashHudTicks;
	private static int ringDashFullTicks;
	private static float ringDashHudCharge;
	private static float ringDashReleaseStartCharge;
	private static int ringFlightSmashChargeTicks;
	private static boolean ringFlightSmashWasPressed;
	private static int ringFlightSmashHudState;
	private static int ringFlightSmashHudTicks;
	private static int ringFlightSmashFullTicks;
	private static float ringFlightSmashHudCharge;
	private static float ringFlightSmashReleaseStartCharge;
	private static final Map<Integer, RingVehicleRotorSoundInstance> RING_VEHICLE_ROTOR_SOUNDS = new HashMap<>();

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
		HandledScreens.register(NestedChestMod.RING_VEHICLE_SCREEN_HANDLER, RingVehicleScreen::new);
		EntityRendererRegistry.register(EchoMinecartRegistry.RING_VEHICLE_ENTITY, RingVehicleRenderer::new);
		ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof RingVehicleEntity vehicle) {
				MinecraftClient.getInstance().getSoundManager().play(new RingVehicleMovingSoundInstance(vehicle));
				playRotorSound(MinecraftClient.getInstance(), vehicle);
			}
		});
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof RingVehicleEntity vehicle) {
				RingVehicleRotorSoundInstance sound = RING_VEHICLE_ROTOR_SOUNDS.remove(vehicle.getId());
				if (sound != null) {
					MinecraftClient.getInstance().getSoundManager().stop(sound);
				}
			}
		});
		RingVehicleItemRenderer ringVehicleItemRenderer = new RingVehicleItemRenderer(MinecraftClient.getInstance());
		BuiltinItemRendererRegistry.INSTANCE.register(EchoMinecartRegistry.RING_RAIL_VEHICLE, ringVehicleItemRenderer);
		BuiltinItemRendererRegistry.INSTANCE.register(EchoMinecartRegistry.RING_POWERED_RAIL_VEHICLE, ringVehicleItemRenderer);
		BuiltinItemRendererRegistry.INSTANCE.register(EchoMinecartRegistry.LAVA_PROOF_RING_RAIL_VEHICLE, ringVehicleItemRenderer);
		BuiltinItemRendererRegistry.INSTANCE.register(EchoMinecartRegistry.LAVA_PROOF_RING_POWERED_RAIL_VEHICLE, ringVehicleItemRenderer);
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
		ringGearKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.echominecart.ring_vehicle_gear",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				"category.echominecart"));
		ringInventoryKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.echominecart.ring_vehicle_inventory",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_V,
				"category.echominecart"));
		ringDashKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.echominecart.ring_vehicle_dash",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_R,
				"category.echominecart"));
		ringSmashKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.echominecart.ring_vehicle_smash",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_X,
				"category.echominecart"));
		ringClutchKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.echominecart.ring_vehicle_clutch",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_LEFT_ALT,
				"category.echominecart"));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			maintainRotorSounds(client);
			while (settingsKeyBinding.wasPressed()) {
				client.setScreen(new EchoMinecartSettingsScreen(client.currentScreen));
			}
			if (client.player != null && client.player.getVehicle() instanceof RingVehicleEntity vehicle) {
				float throttle = 0.0F;
				float steering = 0.0F;
				boolean clutchHeld = false;
				if (client.currentScreen == null) {
					clutchHeld = ringClutchKeyBinding.isPressed();
					if (client.options.forwardKey.isPressed()) {
						throttle += 1.0F;
					}
					if (client.options.backKey.isPressed()) {
						throttle -= 1.0F;
					}
					if (client.options.leftKey.isPressed()) {
						steering += 1.0F;
					}
					if (client.options.rightKey.isPressed()) {
						steering -= 1.0F;
					}
					boolean flightAbilityReady = !vehicle.isDiscMode() || vehicle.isDiscFlightActive();
					boolean canJump = vehicle.getJumpModuleStrength() > 0 && flightAbilityReady;
					boolean jumpPressed = canJump && client.options.jumpKey.isPressed();
					if (jumpPressed) {
						if (!ringJumpWasPressed) {
							ringJumpHudState = JUMP_HUD_CHARGING;
							ringJumpHudTicks = 0;
							ringJumpFullTicks = 0;
							ringJumpHudCharge = 0.0F;
						}
						ringJumpChargeTicks = Math.min(40, ringJumpChargeTicks + 1);
						ringJumpHudState = JUMP_HUD_CHARGING;
						ringJumpHudTicks++;
						ringJumpHudCharge = ringJumpChargeTicks / 40.0F;
						ringJumpFullTicks = ringJumpChargeTicks >= 40 ? ringJumpFullTicks + 1 : 0;
					} else if (ringJumpWasPressed && canJump && ringJumpChargeTicks > 0) {
						ClientPlayNetworking.send(new RingVehicleAbilityPayload(vehicle.getId(),
								RingVehicleAbilityPayload.JUMP, ringJumpChargeTicks / 40.0F));
						beginJumpHudRelease();
					}
					if (!canJump) {
						ringJumpChargeTicks = 0;
						hideJumpHud();
					}
					ringJumpWasPressed = jumpPressed;

					boolean canDash = vehicle.getDashModuleStrength() > 0 && flightAbilityReady;
					boolean dashPressed = canDash && ringDashKeyBinding.isPressed();
					if (dashPressed) {
						if (!ringDashWasPressed) {
							ringDashHudState = JUMP_HUD_CHARGING;
							ringDashHudTicks = 0;
							ringDashFullTicks = 0;
							ringDashHudCharge = 0.0F;
						}
						ringDashChargeTicks = Math.min(DASH_MAX_CHARGE_TICKS, ringDashChargeTicks + 1);
						ringDashHudState = JUMP_HUD_CHARGING;
						ringDashHudTicks++;
						ringDashHudCharge = ringDashChargeTicks / (float) DASH_MAX_CHARGE_TICKS;
						ringDashFullTicks = ringDashChargeTicks >= DASH_MAX_CHARGE_TICKS ? ringDashFullTicks + 1 : 0;
					} else if (ringDashWasPressed && canDash && ringDashChargeTicks > 0) {
						ClientPlayNetworking.send(new RingVehicleAbilityPayload(vehicle.getId(),
								RingVehicleAbilityPayload.DASH,
								ringDashChargeTicks / (float) DASH_MAX_CHARGE_TICKS));
						beginDashHudRelease();
					}
					if (!canDash) {
						ringDashChargeTicks = 0;
						hideDashHud();
					}
					ringDashWasPressed = dashPressed;

					while (ringSmashKeyBinding.wasPressed()) {
						// Drain press events; both vehicle modes use hold/release charging.
					}
					boolean canSmash = vehicle.getSmashModuleType() > 0
							&& (!vehicle.isDiscMode() || vehicle.isDiscFlightActive());
					boolean smashPressed = canSmash && ringSmashKeyBinding.isPressed();
					if (smashPressed) {
						if (!ringFlightSmashWasPressed) {
							ringFlightSmashHudState = JUMP_HUD_CHARGING;
							ringFlightSmashHudTicks = 0;
							ringFlightSmashFullTicks = 0;
							ringFlightSmashHudCharge = 0.0F;
						}
						ringFlightSmashChargeTicks = Math.min(FLIGHT_SMASH_MAX_CHARGE_TICKS,
								ringFlightSmashChargeTicks + 1);
						ringFlightSmashHudState = JUMP_HUD_CHARGING;
						ringFlightSmashHudTicks++;
						ringFlightSmashHudCharge = ringFlightSmashChargeTicks
								/ (float) FLIGHT_SMASH_MAX_CHARGE_TICKS;
						ringFlightSmashFullTicks = ringFlightSmashChargeTicks >= FLIGHT_SMASH_MAX_CHARGE_TICKS
								? ringFlightSmashFullTicks + 1 : 0;
					} else if (ringFlightSmashWasPressed && canSmash && ringFlightSmashChargeTicks > 0) {
						ClientPlayNetworking.send(new RingVehicleAbilityPayload(vehicle.getId(),
								RingVehicleAbilityPayload.SMASH,
								ringFlightSmashChargeTicks / (float) FLIGHT_SMASH_MAX_CHARGE_TICKS));
						beginFlightSmashHudRelease();
					}
					if (!canSmash) {
						ringFlightSmashChargeTicks = 0;
						hideFlightSmashHud();
					}
					ringFlightSmashWasPressed = smashPressed;
				} else {
					if (ringJumpWasPressed) {
						beginJumpHudRelease();
					}
					if (ringDashWasPressed) {
						beginDashHudRelease();
					}
					if (ringFlightSmashWasPressed) {
						beginFlightSmashHudRelease();
					}
					ringJumpChargeTicks = 0;
					ringJumpWasPressed = false;
					ringDashChargeTicks = 0;
					ringDashWasPressed = false;
					ringFlightSmashChargeTicks = 0;
					ringFlightSmashWasPressed = false;
				}
				tickJumpHudRelease();
				tickDashHudRelease();
				tickFlightSmashHudRelease();
				steering *= (float) NestedChestClientConfig.ringVehicleSteeringSensitivity();
				ClientPlayNetworking.send(new RingVehicleControlPayload(vehicle.getId(), throttle, steering, clutchHeld));
				boolean miningHeld = client.currentScreen == null
						&& vehicle.isMiningModeEnabled()
						&& client.options.attackKey.isPressed();
				Vec3d miningAim = cameraForward(client);
				ClientPlayNetworking.send(new RingVehicleMiningPayload(vehicle.getId(), miningHeld,
						(float) miningAim.x, (float) miningAim.y, (float) miningAim.z));
				while (ringGearKeyBinding.wasPressed()) {
					ClientPlayNetworking.send(new RingVehicleActionPayload(vehicle.getId(), RingVehicleActionPayload.TOGGLE_GEAR));
				}
				while (ringInventoryKeyBinding.wasPressed()) {
					ClientPlayNetworking.send(new RingVehicleActionPayload(vehicle.getId(), RingVehicleActionPayload.OPEN_INVENTORY));
				}
			} else {
				ringJumpChargeTicks = 0;
				ringJumpWasPressed = false;
				ringDashChargeTicks = 0;
				ringDashWasPressed = false;
				ringFlightSmashChargeTicks = 0;
				ringFlightSmashWasPressed = false;
				hideJumpHud();
				hideDashHud();
				hideFlightSmashHud();
			}
		});
		HudRenderCallback.EVENT.register((context, tickCounter) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player == null || !(client.player.getVehicle() instanceof RingVehicleEntity vehicle)) {
				return;
			}
			boolean showJump = ringJumpHudState != JUMP_HUD_HIDDEN && vehicle.getJumpModuleStrength() > 0;
			boolean showDash = ringDashHudState != JUMP_HUD_HIDDEN && vehicle.getDashModuleStrength() > 0;
			boolean showFlightSmash = ringFlightSmashHudState != JUMP_HUD_HIDDEN
					&& vehicle.getSmashModuleType() > 0;
			float tickDelta = tickCounter.getTickDelta(true);
			if (showJump) {
				renderJumpChargeHud(context, tickDelta);
			}
			if (showDash) {
				renderDashChargeHud(context, tickDelta, showJump ? 20 : 0);
			}
			if (showFlightSmash) {
				int verticalOffset = (showJump ? 20 : 0) + (showDash ? 20 : 0);
				renderFlightSmashChargeHud(context, tickDelta, verticalOffset);
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof GameMenuScreen) {
				int buttonWidth = Math.min(160, Math.max(100, scaledWidth - 20));
				Screens.getButtons(screen).add(ButtonWidget.builder(Text.translatable("button.echominecart.ring_vehicle_settings"),
								button -> client.setScreen(new RingVehicleSettingsScreen(screen)))
						.dimensions(scaledWidth - buttonWidth - 10, scaledHeight - 54, buttonWidth, 20)
						.build());
				Screens.getButtons(screen).add(ButtonWidget.builder(Text.translatable("button.echominecart.settings"),
								button -> client.setScreen(new EchoMinecartSettingsScreen(screen)))
						.dimensions(scaledWidth - buttonWidth - 10, scaledHeight - 30, buttonWidth, 20)
						.build());
			}
		});
	}

	private static void playRotorSound(MinecraftClient client, RingVehicleEntity vehicle) {
		RingVehicleRotorSoundInstance previous = RING_VEHICLE_ROTOR_SOUNDS.remove(vehicle.getId());
		if (previous != null) {
			client.getSoundManager().stop(previous);
		}
		RingVehicleRotorSoundInstance sound = new RingVehicleRotorSoundInstance(vehicle);
		RING_VEHICLE_ROTOR_SOUNDS.put(vehicle.getId(), sound);
		client.getSoundManager().play(sound);
	}

	private static void maintainRotorSounds(MinecraftClient client) {
		if (client.world == null) {
			RING_VEHICLE_ROTOR_SOUNDS.clear();
			return;
		}
		Iterator<Map.Entry<Integer, RingVehicleRotorSoundInstance>> iterator
				= RING_VEHICLE_ROTOR_SOUNDS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Integer, RingVehicleRotorSoundInstance> entry = iterator.next();
			if (!(client.world.getEntityById(entry.getKey()) instanceof RingVehicleEntity vehicle)
					|| vehicle.isRemoved()) {
				client.getSoundManager().stop(entry.getValue());
				iterator.remove();
				continue;
			}
			if (vehicle.isDiscMode() && Math.abs(vehicle.getFlightRotorSpeed()) > 0.45F
					&& (!entry.getValue().startedWithRotor()
							|| !client.getSoundManager().isPlaying(entry.getValue())
							|| entry.getValue().refreshDue())) {
				RingVehicleRotorSoundInstance previous = entry.getValue();
				RingVehicleRotorSoundInstance replacement = new RingVehicleRotorSoundInstance(vehicle);
				entry.setValue(replacement);
				client.getSoundManager().play(replacement);
				client.getSoundManager().stop(previous);
			}
		}
	}

	private static Vec3d cameraForward(MinecraftClient client) {
		Vector3f forward = client.gameRenderer.getCamera().getHorizontalPlane();
		Vec3d direction = new Vec3d(forward.x, forward.y, forward.z);
		if (direction.lengthSquared() < 0.25D && client.player != null) {
			direction = client.player.getRotationVec(1.0F);
		}
		return direction.lengthSquared() < 0.25D ? new Vec3d(0.0D, 0.0D, 1.0D) : direction.normalize();
	}

	private static void beginJumpHudRelease() {
		ringJumpReleaseStartCharge = ringJumpHudCharge;
		ringJumpChargeTicks = 0;
		ringJumpFullTicks = 0;
		ringJumpHudTicks = 0;
		ringJumpHudState = JUMP_HUD_RELEASING;
	}

	private static void tickJumpHudRelease() {
		if (ringJumpHudState != JUMP_HUD_RELEASING) {
			return;
		}
		ringJumpHudTicks++;
		if (ringJumpHudTicks <= 8) {
			float progress = smoothStep(0.0F, 8.0F, ringJumpHudTicks);
			ringJumpHudCharge = ringJumpReleaseStartCharge * (1.0F - progress);
		} else {
			ringJumpHudCharge = 0.0F;
		}
		if (ringJumpHudTicks > 20) {
			hideJumpHud();
		}
	}

	private static void hideJumpHud() {
		ringJumpHudState = JUMP_HUD_HIDDEN;
		ringJumpHudTicks = 0;
		ringJumpFullTicks = 0;
		ringJumpHudCharge = 0.0F;
		ringJumpReleaseStartCharge = 0.0F;
	}

	private static void beginDashHudRelease() {
		ringDashReleaseStartCharge = ringDashHudCharge;
		ringDashChargeTicks = 0;
		ringDashFullTicks = 0;
		ringDashHudTicks = 0;
		ringDashHudState = JUMP_HUD_RELEASING;
	}

	private static void tickDashHudRelease() {
		if (ringDashHudState != JUMP_HUD_RELEASING) {
			return;
		}
		ringDashHudTicks++;
		if (ringDashHudTicks <= 7) {
			float progress = smoothStep(0.0F, 7.0F, ringDashHudTicks);
			ringDashHudCharge = ringDashReleaseStartCharge * (1.0F - progress);
		} else {
			ringDashHudCharge = 0.0F;
		}
		if (ringDashHudTicks > 17) {
			hideDashHud();
		}
	}

	private static void hideDashHud() {
		ringDashHudState = JUMP_HUD_HIDDEN;
		ringDashHudTicks = 0;
		ringDashFullTicks = 0;
		ringDashHudCharge = 0.0F;
		ringDashReleaseStartCharge = 0.0F;
	}

	private static void beginFlightSmashHudRelease() {
		ringFlightSmashReleaseStartCharge = ringFlightSmashHudCharge;
		ringFlightSmashChargeTicks = 0;
		ringFlightSmashFullTicks = 0;
		ringFlightSmashHudTicks = 0;
		ringFlightSmashHudState = JUMP_HUD_RELEASING;
	}

	private static void tickFlightSmashHudRelease() {
		if (ringFlightSmashHudState != JUMP_HUD_RELEASING) {
			return;
		}
		ringFlightSmashHudTicks++;
		if (ringFlightSmashHudTicks <= 6) {
			float progress = smoothStep(0.0F, 6.0F, ringFlightSmashHudTicks);
			ringFlightSmashHudCharge = ringFlightSmashReleaseStartCharge * (1.0F - progress);
		} else {
			ringFlightSmashHudCharge = 0.0F;
		}
		if (ringFlightSmashHudTicks > 16) {
			hideFlightSmashHud();
		}
	}

	private static void hideFlightSmashHud() {
		ringFlightSmashHudState = JUMP_HUD_HIDDEN;
		ringFlightSmashHudTicks = 0;
		ringFlightSmashFullTicks = 0;
		ringFlightSmashHudCharge = 0.0F;
		ringFlightSmashReleaseStartCharge = 0.0F;
	}

	private static void renderJumpChargeHud(DrawContext context, float tickDelta) {
		int width = 130;
		int height = 12;
		int trackWidth = width - ABILITY_SOCKET_SIZE + ABILITY_SOCKET_OVERLAP;
		int x = (context.getScaledWindowWidth() - width) / 2;
		int y = context.getScaledWindowHeight() - 56;
		float stateTime = ringJumpHudTicks + tickDelta;
		float intro = ringJumpHudState == JUMP_HUD_CHARGING
				? smoothStep(0.0F, 7.0F, stateTime)
				: 1.0F;
		float alpha = intro;
		float slide = 0.0F;
		if (ringJumpHudState == JUMP_HUD_RELEASING && stateTime > 12.0F) {
			float exit = smoothStep(12.0F, 20.0F, stateTime);
			alpha *= 1.0F - exit;
			slide = 6.0F * exit;
		}

		double seconds = System.nanoTime() / 1_000_000_000.0D;
		float fullCue = ringJumpHudState == JUMP_HUD_CHARGING
				? smoothStep(0.72F, 1.0F, ringJumpHudCharge)
				: 0.0F;
		float entryShake = (1.0F - intro) * 0.85F;
		float fullShake = fullCue * (0.32F + 1.48F * smoothStep(0.0F, 7.0F, ringJumpFullTicks + tickDelta));
		float shake = entryShake + fullShake;
		float shakeX = (float) (Math.sin(seconds * 41.0D) * 0.62D + Math.sin(seconds * 67.0D) * 0.38D) * shake;
		float shakeY = (float) (Math.sin(seconds * 53.0D + 1.7D) * 0.58D
				+ Math.sin(seconds * 29.0D + 0.4D) * 0.42D) * shake * 0.58F;
		float scale = 0.92F + 0.08F * (1.0F - (float) Math.pow(1.0F - intro, 3.0D));

		context.getMatrices().push();
		context.getMatrices().translate(x + width * 0.5F + shakeX, y + height * 0.5F + slide + shakeY, 0.0F);
		context.getMatrices().scale(scale, scale, 1.0F);
		context.getMatrices().translate(-(x + width * 0.5F), -(y + height * 0.5F), 0.0F);

		fillRoundedRect(context, x + 1, y + 2, trackWidth, height, 5, withAlpha(0x70000000, alpha));
		fillRoundedRect(context, x, y, trackWidth, height, 5, withAlpha(0xE5C8D1CC, alpha));
		fillRoundedRect(context, x + 2, y + 2, trackWidth - 4, height - 4, 3, withAlpha(0xEE222A26, alpha));

		float charge = Math.max(0.0F, Math.min(1.0F, ringJumpHudCharge));
		int fillWidth = Math.round((trackWidth - 6) * charge);
		int red = Math.round(82 + 173 * charge);
		int green = Math.round(207 - 22 * charge);
		int blue = Math.round(133 - 63 * charge);
		int color = 0xFF000000 | red << 16 | green << 8 | blue;
		if (fillWidth > 0) {
			fillRoundedRect(context, x + 3, y + 3, fillWidth, height - 6, 3, withAlpha(color, alpha));
		}
		if (fillWidth > 5) {
			context.fill(x + 5, y + 4, x + 2 + fillWidth, y + 5, withAlpha(0x55FFFFFF, alpha));
		}

		if (ringJumpHudState == JUMP_HUD_CHARGING && ringJumpFullTicks > 0) {
			renderFullChargeEffects(context, x, y, trackWidth, height, alpha, tickDelta, seconds);
		}
		renderAbilitySocket(context, JUMP_HUD_ICON, x, y, width, height,
				0xFFE5FFF3, 0xED18362D, 0xFFB8FFE0, alpha, charge, fullCue, seconds);
		context.getMatrices().pop();
	}

	private static void renderDashChargeHud(DrawContext context, float tickDelta, int verticalOffset) {
		int width = 138;
		int height = 10;
		int trackWidth = width - ABILITY_SOCKET_SIZE + ABILITY_SOCKET_OVERLAP;
		int x = (context.getScaledWindowWidth() - width) / 2;
		int y = context.getScaledWindowHeight() - 56 - verticalOffset;
		float stateTime = ringDashHudTicks + tickDelta;
		float intro = ringDashHudState == JUMP_HUD_CHARGING
				? smoothStep(0.0F, 5.0F, stateTime)
				: 1.0F;
		float alpha = intro;
		float slideY = 0.0F;
		if (ringDashHudState == JUMP_HUD_RELEASING && stateTime > 10.0F) {
			float exit = smoothStep(10.0F, 17.0F, stateTime);
			alpha *= 1.0F - exit;
			slideY = 4.0F * exit;
		}

		double seconds = System.nanoTime() / 1_000_000_000.0D;
		float charge = Math.max(0.0F, Math.min(1.0F, ringDashHudCharge));
		float fullCue = ringDashHudState == JUMP_HUD_CHARGING ? smoothStep(0.68F, 1.0F, charge) : 0.0F;
		float shake = (1.0F - intro) * 0.72F
				+ fullCue * (0.26F + 1.18F * smoothStep(0.0F, 6.0F, ringDashFullTicks + tickDelta));
		float shakeX = (float) (Math.sin(seconds * 58.0D) * 0.72D
				+ Math.sin(seconds * 89.0D + 0.8D) * 0.28D) * shake;
		float shakeY = (float) Math.sin(seconds * 37.0D + 1.2D) * shake * 0.34F;
		float entrySlideX = -9.0F * (1.0F - intro);
		float scaleX = 0.90F + 0.10F * intro;
		float scaleY = 0.84F + 0.16F * intro;

		context.getMatrices().push();
		context.getMatrices().translate(
				x + width * 0.5F + entrySlideX + shakeX,
				y + height * 0.5F + slideY + shakeY, 0.0F);
		context.getMatrices().scale(scaleX, scaleY, 1.0F);
		context.getMatrices().translate(-(x + width * 0.5F), -(y + height * 0.5F), 0.0F);

		fillRoundedRect(context, x + 2, y + 2, trackWidth, height, 4, withAlpha(0x65000000, alpha));
		fillRoundedRect(context, x, y, trackWidth, height, 4, withAlpha(0xD4A9DDF0, alpha));
		fillRoundedRect(context, x + 2, y + 2, trackWidth - 4, height - 4, 2, withAlpha(0xF0142029, alpha));
		int fillWidth = Math.round((trackWidth - 6) * charge);
		int red = Math.round(55 + 72 * charge);
		int green = Math.round(158 + 82 * charge);
		int blue = Math.round(220 + 35 * charge);
		int fillColor = 0xFF000000 | red << 16 | green << 8 | blue;
		if (fillWidth > 0) {
			fillRoundedRect(context, x + 3, y + 3, fillWidth, height - 6, 2, withAlpha(fillColor, alpha));
		}
		if (fillWidth > 7) {
			context.fill(x + 5, y + 3, x + 2 + fillWidth, y + 4, withAlpha(0x72FFFFFF, alpha));
		}
		if (ringDashHudState == JUMP_HUD_CHARGING && ringDashFullTicks > 0) {
			renderFullDashEffects(context, x, y, trackWidth, height, alpha, seconds);
		}
		renderAbilitySocket(context, DASH_HUD_ICON, x, y, width, height,
				0xFFD9F4FF, 0xED132C38, 0xFF82E6FF, alpha, charge, fullCue, seconds);
		context.getMatrices().pop();
	}

	private static void renderFlightSmashChargeHud(DrawContext context, float tickDelta, int verticalOffset) {
		int width = 142;
		int height = 12;
		int trackWidth = width - ABILITY_SOCKET_SIZE + ABILITY_SOCKET_OVERLAP;
		int x = (context.getScaledWindowWidth() - width) / 2;
		int y = context.getScaledWindowHeight() - 56 - verticalOffset;
		float stateTime = ringFlightSmashHudTicks + tickDelta;
		float intro = ringFlightSmashHudState == JUMP_HUD_CHARGING
				? smoothStep(0.0F, 6.0F, stateTime)
				: 1.0F;
		float alpha = intro;
		float slideY = 0.0F;
		if (ringFlightSmashHudState == JUMP_HUD_RELEASING && stateTime > 9.0F) {
			float exit = smoothStep(9.0F, 16.0F, stateTime);
			alpha *= 1.0F - exit;
			slideY = 7.0F * exit;
		}

		double seconds = System.nanoTime() / 1_000_000_000.0D;
		float charge = Math.max(0.0F, Math.min(1.0F, ringFlightSmashHudCharge));
		float fullCue = ringFlightSmashHudState == JUMP_HUD_CHARGING
				? smoothStep(0.70F, 1.0F, charge)
				: 0.0F;
		float shake = (1.0F - intro) * 0.55F
				+ fullCue * (0.28F + 1.35F * smoothStep(0.0F, 7.0F, ringFlightSmashFullTicks + tickDelta));
		float shakeX = (float) (Math.sin(seconds * 43.0D) * 0.56D
				+ Math.sin(seconds * 71.0D + 0.9D) * 0.44D) * shake;
		float shakeY = (float) (Math.sin(seconds * 31.0D + 1.4D) * 0.72D
				+ Math.sin(seconds * 57.0D) * 0.28D) * shake * 0.52F;
		float scale = 0.91F + 0.09F * intro;

		context.getMatrices().push();
		context.getMatrices().translate(
				x + width * 0.5F + shakeX,
				y + height * 0.5F + slideY + shakeY, 0.0F);
		context.getMatrices().scale(scale, scale, 1.0F);
		context.getMatrices().translate(-(x + width * 0.5F), -(y + height * 0.5F), 0.0F);

		fillRoundedRect(context, x + 2, y + 2, trackWidth, height, 5, withAlpha(0x72000000, alpha));
		fillRoundedRect(context, x, y, trackWidth, height, 5, withAlpha(0xE0D6B08A, alpha));
		fillRoundedRect(context, x + 2, y + 2, trackWidth - 4, height - 4, 3, withAlpha(0xF0201412, alpha));
		int fillWidth = Math.round((trackWidth - 6) * charge);
		int red = Math.round(132 + 123 * charge);
		int green = Math.round(48 + 104 * charge);
		int blue = Math.round(38 - 20 * charge);
		int fillColor = 0xFF000000 | red << 16 | green << 8 | blue;
		if (fillWidth > 0) {
			fillRoundedRect(context, x + 3, y + 3, fillWidth, height - 6, 3, withAlpha(fillColor, alpha));
		}
		if (fillWidth > 6) {
			context.fill(x + 5, y + 4, x + 2 + fillWidth, y + 5, withAlpha(0x5AFFF0D4, alpha));
		}
		if (ringFlightSmashHudState == JUMP_HUD_CHARGING && ringFlightSmashFullTicks > 0) {
			float pulse = 0.5F + 0.5F * (float) Math.sin(seconds * 7.5D);
			fillRoundedRect(context, x + 3, y + 3, trackWidth - 6, height - 6, 3,
					withAlpha((Math.round(40.0F + pulse * 70.0F) << 24) | 0x00FFD27A, alpha));
			int centerX = x + trackWidth / 2;
			context.fill(centerX - 1, y + 4, centerX + 1, y + height - 2,
					withAlpha(0xD0FFF4D0, alpha));
			context.fill(centerX - 3, y + height - 5, centerX + 3, y + height - 3,
					withAlpha(0xD0FFF4D0, alpha));
		}
		renderAbilitySocket(context, SMASH_HUD_ICON, x, y, width, height,
				0xFFFFD39A, 0xF0381713, 0xFFFFAD59, alpha, charge, fullCue, seconds);
		context.getMatrices().pop();
	}

	private static void renderFullDashEffects(DrawContext context, int x, int y, int width, int height,
			float alpha, double seconds) {
		int innerX = x + 3;
		int innerY = y + 3;
		int innerWidth = width - 6;
		int innerHeight = height - 6;
		for (int lane = 0; lane < 4; lane++) {
			double phase = seconds * (1.55D + lane * 0.08D) + lane * 0.24D;
			phase -= Math.floor(phase);
			int streakX = innerX + (int) Math.round(phase * (innerWidth + 10)) - 8;
			fillRoundedRect(context, streakX, innerY, 8, innerHeight, 2,
					withAlpha(0xA8E8FFFF, alpha * (float) Math.sin(Math.PI * phase)));
		}
		float pulse = 0.5F + 0.5F * (float) Math.sin(seconds * 8.0D);
		fillRoundedRect(context, innerX, innerY, innerWidth, innerHeight, 2,
				withAlpha((Math.round(24.0F + pulse * 44.0F) << 24) | 0x006DDCFF, alpha));
	}

	private static void renderFullChargeEffects(DrawContext context, int x, int y, int width, int height,
			float alpha, float tickDelta, double seconds) {
		int innerX = x + 3;
		int innerY = y + 3;
		int innerWidth = width - 6;
		int innerHeight = height - 6;
		float convergence = smoothStep(0.0F, 11.0F, ringJumpFullTicks + tickDelta);
		int travel = Math.round((innerWidth * 0.5F - 3.0F) * convergence);
		int leftGlow = innerX + travel;
		int rightGlow = innerX + innerWidth - 3 - travel;
		int convergenceAlpha = Math.round((1.0F - convergence * 0.45F) * 190.0F);
		fillRoundedRect(context, leftGlow - 2, innerY, 5, innerHeight, 2,
				withAlpha((convergenceAlpha << 24) | 0x00E9FFF4, alpha));
		fillRoundedRect(context, rightGlow - 2, innerY, 5, innerHeight, 2,
				withAlpha((convergenceAlpha << 24) | 0x00E9FFF4, alpha));

		if (convergence < 0.78F) {
			return;
		}
		float pulse = 0.5F + 0.5F * (float) Math.sin(seconds * 6.5D);
		fillRoundedRect(context, innerX, innerY, innerWidth, innerHeight, 2,
				withAlpha((Math.round(32.0F + pulse * 42.0F) << 24) | 0x00B8FFE0, alpha));
		for (int index = 0; index < 12; index++) {
			double phase = seconds * (0.82D + index * 0.025D) + index * 0.173D;
			phase -= Math.floor(phase);
			int bubbleX = innerX + 3 + (index * 37 % Math.max(1, innerWidth - 7));
			int bubbleY = innerY + innerHeight - 1 - (int) Math.round(phase * (innerHeight + 2));
			int bubbleSize = index % 4 == 0 ? 2 : 1;
			float bubbleAlpha = (float) Math.sin(Math.PI * phase) * alpha;
			fillRoundedRect(context, bubbleX, bubbleY, bubbleSize, bubbleSize, 1,
					withAlpha(0xB8FFFFFF, bubbleAlpha));
		}
	}

	private static void renderAbilitySocket(DrawContext context, ItemStack icon,
			int barX, int barY, int totalWidth, int barHeight,
			int rimColor, int coreColor, int fractureColor,
			float alpha, float charge, float fullCue, double seconds) {
		int socketX = barX + totalWidth - ABILITY_SOCKET_SIZE;
		int socketY = barY + (barHeight - ABILITY_SOCKET_SIZE) / 2;
		int centerX = socketX + ABILITY_SOCKET_SIZE / 2;
		int centerY = socketY + ABILITY_SOCKET_SIZE / 2;
		float pulse = 0.5F + 0.5F * (float) Math.sin(seconds * 5.4D);
		float chargedAlpha = alpha * (0.34F + charge * 0.48F + fullCue * pulse * 0.18F);

		fillRoundedRect(context, socketX + 1, socketY + 2,
				ABILITY_SOCKET_SIZE, ABILITY_SOCKET_SIZE, 5, withAlpha(0x78000000, alpha));
		fillRoundedRect(context, socketX, socketY,
				ABILITY_SOCKET_SIZE, ABILITY_SOCKET_SIZE, 5, withAlpha(rimColor, alpha));
		fillRoundedRect(context, socketX + 2, socketY + 2,
				ABILITY_SOCKET_SIZE - 4, ABILITY_SOCKET_SIZE - 4, 4, withAlpha(0xF0090B0C, alpha));
		fillRoundedRect(context, socketX + 3, socketY + 3,
				ABILITY_SOCKET_SIZE - 6, ABILITY_SOCKET_SIZE - 6, 3, withAlpha(coreColor, alpha));
		fillRoundedRect(context, socketX + 4, socketY + 4,
				ABILITY_SOCKET_SIZE - 8, 2, 1, withAlpha(0x65FFFFFF, chargedAlpha));
		fillRoundedRect(context, socketX + 4, socketY + ABILITY_SOCKET_SIZE - 6,
				ABILITY_SOCKET_SIZE - 8, 2, 1, withAlpha(fractureColor, chargedAlpha * 0.42F));

		int crackColor = withAlpha(fractureColor, alpha * (0.48F + charge * 0.42F));
		int darkCrack = withAlpha(0xEE080A0B, alpha * 0.88F);
		int jitter = fullCue > 0.7F && Math.sin(seconds * 18.0D) > 0.68D ? 1 : 0;
		context.fill(socketX - 5, centerY - 1, socketX + 2, centerY, darkCrack);
		context.fill(socketX - 4, centerY - 2, socketX - 1, centerY - 1, crackColor);
		context.fill(socketX - 3, centerY, socketX, centerY + 1, crackColor);
		context.fill(socketX - 2, centerY + 1, socketX - 1, centerY + 4 + jitter, crackColor);
		context.fill(socketX + 1, socketY - 2 - jitter, socketX + 3, socketY, darkCrack);
		context.fill(socketX + 2, socketY - 3 - jitter, socketX + 4, socketY - 2 - jitter, crackColor);
		context.fill(socketX + ABILITY_SOCKET_SIZE - 3, socketY + 1,
				socketX + ABILITY_SOCKET_SIZE - 2, socketY + 5, crackColor);
		context.fill(socketX + ABILITY_SOCKET_SIZE - 2, socketY + 4,
				socketX + ABILITY_SOCKET_SIZE + 1 + jitter, socketY + 5, darkCrack);
		context.fill(socketX + 2, socketY + ABILITY_SOCKET_SIZE,
				socketX + 4, socketY + ABILITY_SOCKET_SIZE + 2 + jitter, crackColor);
		context.fill(socketX + ABILITY_SOCKET_SIZE - 5, socketY + ABILITY_SOCKET_SIZE - 1,
				socketX + ABILITY_SOCKET_SIZE - 3, socketY + ABILITY_SOCKET_SIZE + 1, darkCrack);

		if (alpha > 0.08F) {
			float iconScale = 0.78F + 0.22F * smoothStep(0.08F, 0.72F, alpha);
			context.getMatrices().push();
			context.getMatrices().translate(centerX, centerY, 32.0F);
			context.getMatrices().scale(iconScale, iconScale, 1.0F);
			context.getMatrices().translate(-8.0F, -8.0F, 0.0F);
			context.drawItem(icon, 0, 0);
			context.getMatrices().pop();
		}

		context.fill(socketX + 3, socketY + 3, socketX + 7, socketY + 4,
				withAlpha(0xA0FFFFFF, chargedAlpha));
		context.fill(socketX + ABILITY_SOCKET_SIZE - 5, socketY + ABILITY_SOCKET_SIZE - 4,
				socketX + ABILITY_SOCKET_SIZE - 3, socketY + ABILITY_SOCKET_SIZE - 3,
				withAlpha(fractureColor, chargedAlpha));
	}

	private static int withAlpha(int color, float alphaMultiplier) {
		int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, alphaMultiplier)));
		return color & 0x00FFFFFF | alpha << 24;
	}

	private static float smoothStep(float edge0, float edge1, float value) {
		float t = Math.max(0.0F, Math.min(1.0F, (value - edge0) / (edge1 - edge0)));
		return t * t * (3.0F - 2.0F * t);
	}

	private static void fillRoundedRect(DrawContext context, int x, int y, int width, int height, int radius, int color) {
		if (width <= 0 || height <= 0) {
			return;
		}
		int safeRadius = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
		for (int row = 0; row < height; row++) {
			int edgeDistance = Math.min(row, height - 1 - row);
			int inset = 0;
			if (edgeDistance < safeRadius && safeRadius > 0) {
				double dy = safeRadius - edgeDistance - 0.5D;
				inset = (int) Math.ceil(safeRadius - Math.sqrt(Math.max(0.0D, safeRadius * safeRadius - dy * dy)));
			}
			context.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
		}
	}
}
