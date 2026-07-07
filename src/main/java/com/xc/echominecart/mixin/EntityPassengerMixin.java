package com.xc.echominecart.mixin;

import com.xc.echominecart.carriage.CarriageManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(Entity.class)
public abstract class EntityPassengerMixin {
	@Inject(method = "canAddPassenger", at = @At("HEAD"), cancellable = true)
	private void echominecart$allowStretchedMinecartSeats(Entity passenger, CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof AbstractMinecartEntity minecart) {
			Optional<Boolean> admission = CarriageManager.passengerAdmission(minecart);
			admission.ifPresent(cir::setReturnValue);
		}
	}

	@Inject(method = "remove", at = @At("HEAD"))
	private void echominecart$dropAttachedChestsOnDestroy(Entity.RemovalReason reason, CallbackInfo ci) {
		if ((Object) this instanceof AbstractMinecartEntity minecart
				&& reason.shouldDestroy()
				&& minecart.getWorld() instanceof ServerWorld) {
			CarriageManager.dropAttachedChests(minecart);
		}
	}
}
