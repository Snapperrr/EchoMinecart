package com.xc.echominecart.mixin;

import com.xc.echominecart.client.MinecartSoundEffects;
import net.minecraft.client.sound.MinecartInsideSoundInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecartInsideSoundInstance.class)
public abstract class MinecartInsideSoundInstanceMixin {
	@Shadow
	@Final
	private PlayerEntity player;

	@Shadow
	@Final
	private AbstractMinecartEntity minecart;

	@Shadow
	@Final
	private boolean underwater;

	@Inject(method = "tick", at = @At("TAIL"))
	private void echominecart$useAttachedRailSpeed(CallbackInfo ci) {
		if (!player.hasVehicle() || player.getVehicle() != minecart || underwater != player.isSubmergedInWater()) {
			return;
		}
		float volume = MinecartSoundEffects.insideVolume(minecart);
		if (volume >= 0.0F) {
			((AbstractSoundInstanceAccessor) this).echominecart$setVolume(volume);
		}
	}
}
