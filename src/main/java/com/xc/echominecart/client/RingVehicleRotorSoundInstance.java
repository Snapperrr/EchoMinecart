package com.xc.echominecart.client;

import com.xc.echominecart.ringvehicle.RingVehicleEntity;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;

/**
 * Positional airflow loop for a rotating disc-mode outer ring.
 * The instance is periodically replaced because SoundManager liveness does not guarantee that the
 * underlying audio buffer still has audible output.
 */
public final class RingVehicleRotorSoundInstance extends MovingSoundInstance {
	private static final int REFRESH_TICKS = 100;
	private final RingVehicleEntity vehicle;
	private final boolean startedWithRotor;
	private int inactiveTicks;
	private int activeTicks;

	public RingVehicleRotorSoundInstance(RingVehicleEntity vehicle) {
		super(SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.NEUTRAL, SoundInstance.createRandom());
		this.vehicle = vehicle;
		this.startedWithRotor = vehicle.isDiscMode() && Math.abs(vehicle.getFlightRotorSpeed()) > 0.45F;
		this.repeat = true;
		this.repeatDelay = 0;
		float initialRotorLevel = rotorLevel();
		this.volume = startedWithRotor ? targetVolume(initialRotorLevel, 0.5F) : 0.0F;
		this.pitch = 0.58F;
		this.attenuationType = SoundInstance.AttenuationType.LINEAR;
		updatePosition();
	}

	@Override
	public boolean canPlay() {
		return startedWithRotor && !vehicle.isSilent();
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
		float rotorLevel = rotorLevel();
		if (!vehicle.isDiscMode() || rotorLevel <= 0.005F) {
			inactiveTicks++;
			activeTicks = 0;
			this.volume *= 0.82F;
			if (inactiveTicks > 20) {
				setDone();
			}
			return;
		}
		inactiveTicks = 0;
		activeTicks++;
		float bladePulse = 0.5F + 0.5F * MathHelper.sin(
				vehicle.getWorld().getTime() * (0.36F + rotorLevel * 0.32F));
		float targetVolume = targetVolume(rotorLevel, bladePulse);
		this.volume += (targetVolume - this.volume) * 0.12F;
		float poweredOffset = vehicle.getVariant().powered() ? 0.025F : 0.0F;
		this.pitch = 0.56F + poweredOffset + rotorLevel * 0.16F + bladePulse * 0.020F;
	}

	public boolean startedWithRotor() {
		return startedWithRotor;
	}

	public boolean refreshDue() {
		return activeTicks >= REFRESH_TICKS;
	}

	private float rotorLevel() {
		return (float) MathHelper.clamp(Math.abs(vehicle.getFlightRotorSpeed()) / 90.0D, 0.0D, 1.0D);
	}

	private static float targetVolume(float rotorLevel, float bladePulse) {
		return 0.13F + rotorLevel * 0.14F + bladePulse * rotorLevel * 0.025F;
	}

	private void updatePosition() {
		this.x = vehicle.getX();
		this.y = vehicle.getY() + vehicle.getRenderCenterHeight();
		this.z = vehicle.getZ();
	}
}
