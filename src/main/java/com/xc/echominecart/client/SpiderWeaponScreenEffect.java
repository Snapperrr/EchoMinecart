package com.xc.echominecart.client;

import com.xc.echominecart.NestedChestMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.ladysnake.satin.api.event.ShaderEffectRenderCallback;
import org.ladysnake.satin.api.managed.ManagedShaderEffect;
import org.ladysnake.satin.api.managed.ShaderEffectManager;

/** Short, local-only post-process response for a confirmed spider-weapon shot. */
public final class SpiderWeaponScreenEffect {
	private static final ManagedShaderEffect SHADER = ShaderEffectManager.getInstance().manage(
			Identifier.of(NestedChestMod.MOD_ID, "shaders/post/spider_weapon_impact.json"));
	private static long startedAtNanos;
	private static float durationSeconds;
	private static float peakIntensity;
	private static float red = 1.0F;
	private static float green = 1.0F;
	private static float blue = 1.0F;
	private static boolean explosive;

	private SpiderWeaponScreenEffect() {
	}

	public static void initialize() {
		ShaderEffectRenderCallback.EVENT.register(SpiderWeaponScreenEffect::render);
	}

	public static void trigger(float intensity, boolean isExplosive, int color) {
		peakIntensity = clamp(intensity, 0.0F, 1.0F);
		explosive = isExplosive;
		durationSeconds = 0.30F + peakIntensity * 0.08F + (explosive ? 0.16F : 0.0F);
		red = ((color >> 16) & 0xFF) / 255.0F;
		green = ((color >> 8) & 0xFF) / 255.0F;
		blue = (color & 0xFF) / 255.0F;
		startedAtNanos = System.nanoTime();
	}

	private static void render(float tickDelta) {
		if (startedAtNanos == 0L || SHADER.isErrored()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) {
			startedAtNanos = 0L;
			return;
		}
		float ageSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0F;
		if (ageSeconds >= durationSeconds) {
			startedAtNanos = 0L;
			return;
		}

		float tail = 1.0F - smoothstep(durationSeconds * 0.16F, durationSeconds, ageSeconds);
		float initialFlash = (float) Math.exp(-ageSeconds * 18.0F);
		float envelope = peakIntensity * (tail * 0.72F + initialFlash * 0.28F);
		SHADER.setUniformValue("Intensity", envelope);
		SHADER.setUniformValue("Time", ageSeconds);
		SHADER.setUniformValue("FlashColor", red, green, blue);
		SHADER.setUniformValue("Explosive", explosive ? 1.0F : 0.0F);
		SHADER.render(tickDelta);
	}

	private static float smoothstep(float edge0, float edge1, float value) {
		float t = clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}
}
