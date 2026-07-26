package com.xc.echominecart.client;

import com.xc.echominecart.ringvehicle.RingVehicleEntity;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;

/** Loops minecart movement audio and derives volume from linear or inner-cart orbital speed. */
public final class RingVehicleMovingSoundInstance extends MovingSoundInstance {
	private final RingVehicleEntity vehicle;

	public RingVehicleMovingSoundInstance(RingVehicleEntity vehicle) {
		super(SoundEvents.ENTITY_MINECART_RIDING, SoundCategory.NEUTRAL, SoundInstance.createRandom());
		this.vehicle = vehicle;
		this.repeat = true;
		this.repeatDelay = 0;
		this.volume = 0.0F;
		this.pitch = 1.0F;
		this.attenuationType = SoundInstance.AttenuationType.LINEAR;
		updatePosition();
	}

	@Override
	public boolean canPlay() {
		return !vehicle.isSilent();
	}

	@Override
	public boolean shouldAlwaysPlay() {
		return true;
	}

	@Override
	public void tick() {
		if (vehicle.isRemoved()) {
			setDone();
			return;
		}
		updatePosition();
		if (vehicle.isSpiderMode()) {
			setDone();
			return;
		}
		double linearSpeed = vehicle.getVelocity().length();
		double innerCartSpeed = Math.toRadians(Math.abs(vehicle.getInnerCartAngularSpeed()))
				* vehicle.getInnerCartOrbitRadius();
		double orbitalSpeed = innerCartSpeed;
		double audibleSpeed = Math.max(linearSpeed, orbitalSpeed * 0.72D);
		float speedLevel = (float) MathHelper.clamp(audibleSpeed / 0.95D, 0.0D, 1.0D);
		float targetVolume = audibleSpeed < 0.025D ? 0.0F : 0.12F + speedLevel * 0.58F;
		this.volume += (targetVolume - this.volume) * 0.22F;

		float variantPitch = vehicle.getVariant().powered() ? 1.06F : 0.94F;
		if (vehicle.isLavaProof()) {
			variantPitch -= 0.07F;
		}
		if (vehicle.isHighGear()) {
			variantPitch += 0.06F;
		}
		float rpmPitch = (float) MathHelper.clamp(orbitalSpeed / 2.8D, 0.0D, 1.0D) * 0.20F;
		this.pitch = MathHelper.clamp(variantPitch + speedLevel * 0.16F + rpmPitch, 0.72F, 1.52F);
	}

	private void updatePosition() {
		this.x = vehicle.getX();
		this.y = vehicle.getY() + vehicle.getRenderCenterHeight();
		this.z = vehicle.getZ();
	}
}
