package com.xc.echominecart.client;

import com.xc.echominecart.rail.OmniRailBlock;
import com.xc.echominecart.rail.RailPhysics;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public final class MinecartFovEffects {
	private static final double ACTIVATION_SPEED = 0.075D;
	private static final double MIN_SPEED = 0.16D;
	private static final double FULL_SPEED = 0.68D;
	private static final double MAX_SPEED_BOOST = 0.105D;
	private static final double MAX_ACCEL_BOOST = 0.045D;
	private static final double DESCENT_BOOST = 0.11D;
	private static final double POWERED_FLAT_BASE = 0.105D;
	private static final double POWERED_DESCENT_BASE = 0.17D;
	private static final double VERTICAL_DESCENT_BASE = 0.205D;
	private static final double MAX_TOTAL_BOOST = 0.255D;
	private static final double MAX_CONFIGURED_BOOST = 0.75D;
	private static final double RISE_SMOOTHING_RATE = 3.25D;
	private static final double FALL_SMOOTHING_RATE = 5.0D;
	private static final long STATE_CHANGE_RESET_NANOS = 320_000_000L;

	private static int lastCartId = Integer.MIN_VALUE;
	private static int lastMovementSignature = Integer.MIN_VALUE;
	private static double lastSpeed;
	private static double currentBoost;
	private static long lastUpdateNanos;
	private static long stateChangeResetUntilNanos;

	private MinecartFovEffects() {
	}

	public static double apply(Camera camera, float tickDelta, double fov) {
		double target = clamp(targetBoost(camera) * NestedChestClientConfig.minecartFovStrength(), 0.0D, MAX_CONFIGURED_BOOST);
		double rate = target > currentBoost ? RISE_SMOOTHING_RATE : FALL_SMOOTHING_RATE;
		currentBoost = ease(currentBoost, target, deltaSeconds(), rate);
		return fov * (1.0D + currentBoost);
	}

	private static double targetBoost(Camera camera) {
		if (camera.isThirdPerson()) {
			resetCartTracking();
			return 0.0D;
		}
		Entity focused = camera.getFocusedEntity();
		if (focused == null || !(focused.getVehicle() instanceof AbstractMinecartEntity cart)) {
			resetCartTracking();
			return 0.0D;
		}

		Vec3d velocity = cart.getVelocity();
		double speed = velocity.length();
		RailState rail = railState(cart, velocity);
		long now = System.nanoTime();
		if (cart.getId() != lastCartId) {
			lastCartId = cart.getId();
			lastMovementSignature = rail.movementSignature();
			lastSpeed = speed;
			stateChangeResetUntilNanos = 0L;
		} else {
			int movementSignature = rail.movementSignature();
			if (movementSignature != lastMovementSignature) {
				lastMovementSignature = movementSignature;
				if (speed >= ACTIVATION_SPEED) {
					stateChangeResetUntilNanos = now + STATE_CHANGE_RESET_NANOS;
				}
			}
		}
		if (speed < ACTIVATION_SPEED || now < stateChangeResetUntilNanos) {
			lastSpeed = speed;
			return 0.0D;
		}

		double movementLevel = smoothStep(ACTIVATION_SPEED, MIN_SPEED, speed);
		double speedLevel = smoothStep(MIN_SPEED, FULL_SPEED, speed);
		double speedBoost = speedLevel * MAX_SPEED_BOOST;
		double accelerationBoost = accelerationBoost(speed) * movementLevel;

		double tierBoost;
		if (rail.verticalDescent()) {
			tierBoost = VERTICAL_DESCENT_BASE + speedLevel * 0.055D;
		} else if (rail.powered() && rail.descending()) {
			tierBoost = POWERED_DESCENT_BASE + speedLevel * 0.075D;
		} else if (rail.powered()) {
			tierBoost = POWERED_FLAT_BASE + speedLevel * 0.055D;
		} else if (rail.descending()) {
			tierBoost = DESCENT_BOOST + speedLevel * 0.04D;
		} else {
			tierBoost = speedBoost;
		}
		return clamp(tierBoost * movementLevel + accelerationBoost, 0.0D, MAX_TOTAL_BOOST);
	}

	private static double accelerationBoost(double speed) {
		double acceleration = Math.max(0.0D, speed - lastSpeed);
		lastSpeed = speed;
		return clamp(acceleration * 7.0D, 0.0D, MAX_ACCEL_BOOST);
	}

	private static RailState railState(AbstractMinecartEntity cart, Vec3d velocity) {
		return RailPhysics.findContact(cart.getWorld(), cart)
				.map(contact -> {
					boolean powered = OmniRailBlock.isAccelerating(contact.state());
					Descent descent = descent(contact, velocity);
					Direction travel = movementDirection(contact, velocity);
					return new RailState(powered, descent.descending(), descent.vertical(), contact.face(), travel);
				})
				.orElse(new RailState(false, false, false, null, null));
	}

	private static Descent descent(RailPhysics.RailContact contact, Vec3d velocity) {
		if (velocity.y >= -0.045D || velocity.lengthSquared() < 0.0025D) {
			return new Descent(false, false);
		}
		double verticalShare = -velocity.y / Math.max(velocity.length(), 1.0E-4D);
		if (verticalShare >= 0.45D) {
			List<Direction> connections = OmniRailBlock.connections(contact.state());
			boolean straightDown = connections.contains(Direction.DOWN)
					&& connections.size() <= 2
					&& contact.face().getAxis().isHorizontal();
			return new Descent(straightDown, verticalShare >= 0.80D || straightDown);
		}
		Direction ascending = switch (contact.state().get(OmniRailBlock.SHAPE)) {
			case ASCENDING_NORTH -> Direction.NORTH;
			case ASCENDING_SOUTH -> Direction.SOUTH;
			case ASCENDING_EAST -> Direction.EAST;
			case ASCENDING_WEST -> Direction.WEST;
			default -> null;
		};
		return new Descent(ascending != null && velocity.dotProduct(Vec3d.of(ascending.getVector())) < -0.03D, false);
	}

	private static Direction movementDirection(RailPhysics.RailContact contact, Vec3d velocity) {
		if (velocity.lengthSquared() < 1.0E-4D) {
			return null;
		}
		Direction best = null;
		double bestDot = 0.015D;
		for (Direction connection : OmniRailBlock.connections(contact.state())) {
			double dot = velocity.dotProduct(Vec3d.of(connection.getVector()));
			if (dot > bestDot) {
				bestDot = dot;
				best = connection;
			}
		}
		return best;
	}

	private static double ease(double current, double target, double deltaSeconds, double rate) {
		double alpha = 1.0D - Math.exp(-rate * deltaSeconds);
		return current + (target - current) * clamp(alpha, 0.0D, 1.0D);
	}

	private static double deltaSeconds() {
		long now = System.nanoTime();
		if (lastUpdateNanos == 0L) {
			lastUpdateNanos = now;
			return 1.0D / 60.0D;
		}
		double delta = (now - lastUpdateNanos) / 1_000_000_000.0D;
		lastUpdateNanos = now;
		return clamp(delta, 1.0D / 240.0D, 0.08D);
	}

	private static double smoothStep(double edge0, double edge1, double value) {
		double t = clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
		return t * t * (3.0D - 2.0D * t);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static void resetCartTracking() {
		lastCartId = Integer.MIN_VALUE;
		lastMovementSignature = Integer.MIN_VALUE;
		lastSpeed = 0.0D;
		stateChangeResetUntilNanos = 0L;
	}

	private record RailState(boolean powered, boolean descending, boolean verticalDescent, Direction face, Direction travel) {
		private int movementSignature() {
			int faceId = face == null ? 6 : face.ordinal();
			int travelId = travel == null ? 6 : travel.ordinal();
			return faceId * 8 + travelId;
		}
	}

	private record Descent(boolean descending, boolean vertical) {
	}
}
