package com.xc.echominecart.mixin;

import com.xc.echominecart.client.MinecartFovEffects;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void echominecart$boostMinecartFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
		if (changingFov) {
			cir.setReturnValue(MinecartFovEffects.apply(camera, tickDelta, cir.getReturnValueD()));
		}
	}
}
