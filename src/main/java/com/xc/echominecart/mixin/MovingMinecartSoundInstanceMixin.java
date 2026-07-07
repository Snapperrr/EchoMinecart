package com.xc.echominecart.mixin;

import com.xc.echominecart.client.MinecartSoundEffects;
import net.minecraft.client.sound.MovingMinecartSoundInstance;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MovingMinecartSoundInstance.class)
public abstract class MovingMinecartSoundInstanceMixin {
	@Shadow
	@Final
	private AbstractMinecartEntity minecart;

	@Inject(method = "tick", at = @At("TAIL"))
	private void echominecart$useAttachedRailSpeed(CallbackInfo ci) {
		float volume = MinecartSoundEffects.outsideVolume(minecart);
		if (volume >= 0.0F) {
			((AbstractSoundInstanceAccessor) this).echominecart$setVolume(volume);
		}
	}
}
