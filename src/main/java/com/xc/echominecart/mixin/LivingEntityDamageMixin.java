package com.xc.echominecart.mixin;

import com.xc.echominecart.trip.TripManager;
import com.xc.echominecart.ringvehicle.RingVehicleEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.tag.DamageTypeTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Releases rail-tripped entities on damage and grants ring-vehicle passenger fall immunity. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {
	@Inject(method = "damage", at = @At("HEAD"), cancellable = true)
	private void echominecart$releaseRailTrip(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		LivingEntity living = (LivingEntity) (Object) this;
		if (source.isIn(DamageTypeTags.IS_FALL) && living.getVehicle() instanceof RingVehicleEntity) {
			living.fallDistance = 0.0F;
			cir.setReturnValue(false);
			return;
		}
		if (source.isIn(DamageTypeTags.IS_FIRE)
				&& living.getVehicle() instanceof RingVehicleEntity vehicle
				&& vehicle.isLavaProof()) {
			living.extinguish();
			cir.setReturnValue(false);
			return;
		}
		// damage 在客户端线程也会被调用（玩家攻击预测、伤害包处理），
		// 单人模式静态表共享，必须挡住客户端侧，否则 PlayerLookup 直接抛异常。
		if (!living.getWorld().isClient()) {
			TripManager.release(living);
		}
	}
}
