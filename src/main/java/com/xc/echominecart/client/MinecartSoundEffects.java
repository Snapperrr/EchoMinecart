package com.xc.echominecart.client;

import com.xc.echominecart.rail.RailPhysics;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

public final class MinecartSoundEffects {
	private static final float NO_ATTACHED_SOUND = -1.0F;

	private MinecartSoundEffects() {
	}

	public static float outsideVolume(AbstractMinecartEntity minecart) {
		float speed = attachedRailSpeed(minecart);
		if (speed < 0.0F) {
			return NO_ATTACHED_SOUND;
		}
		float speedLevel = MathHelper.clamp(speed, 0.0F, 0.5F);
		return MathHelper.lerp(speedLevel, 0.0F, 0.7F);
	}

	public static float insideVolume(AbstractMinecartEntity minecart) {
		float speed = attachedRailSpeed(minecart);
		if (speed < 0.0F) {
			return NO_ATTACHED_SOUND;
		}
		return MathHelper.clampedLerp(0.0F, 0.75F, speed);
	}

	private static float attachedRailSpeed(AbstractMinecartEntity minecart) {
		if (minecart.isRemoved() || minecart.isSilent() || !minecart.getWorld().getTickManager().shouldTick()) {
			return NO_ATTACHED_SOUND;
		}
		return RailPhysics.findContact(minecart.getWorld(), minecart)
				.filter(contact -> contact.face() != Direction.UP)
				.map(contact -> {
					float speed = (float) minecart.getVelocity().length();
					return speed >= 0.01F ? speed : NO_ATTACHED_SOUND;
				})
				.orElse(NO_ATTACHED_SOUND);
	}
}
