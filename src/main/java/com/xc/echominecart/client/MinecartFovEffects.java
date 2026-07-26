package com.xc.echominecart.client;

import com.xc.echominecart.rail.OmniRailBlock;
import com.xc.echominecart.rail.RailPhysics;
import com.xc.echominecart.ringvehicle.RingVehicleEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Computes smoothed speed, powered-rail, descent, transition, and ring-vehicle FOV contributions. */
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
	private static final double MAX_CONFIGURED_BOOST = 1.25D;
	private static final double MAX_FINAL_FOV = 150.0D;
	private static final double RISE_SMOOTHING_RATE = 3.25D;
	private static final double FALL_SMOOTHING_RATE = 5.0D;
	private static final long STATE_CHANGE_RESET_NANOS = 320_000_000L;

	private static int lastCartId = Integer.MIN_VALUE;
	private static int lastMovementSignature = Integer.MIN_VALUE;
	private static double lastSpeed;
	private static double currentBoost;
	private static long lastUpdateNanos;
	private static long stateChangeResetUntilNanos;
	private static int lastRingTransientVehicleId = Integer.MIN_VALUE;
	private static int lastRingLaunchEvent;
	private static double ringTransientBoost;
	private static double ringLaunchHoldSeconds;
	private static int lastSpiderFovVehicleId = Integer.MIN_VALUE;
	private static float lastSpiderGaitPhase;
	private static double filteredSpiderSpeed;
	private static long spiderMotionHoldUntilNanos;

	private MinecartFovEffects() {
	}

	public static double apply(Camera camera, float tickDelta, double fov) {
		RingVehicleEntity ringVehicle = ringVehicleView(camera);
		double strength = ringVehicle != null
				? NestedChestClientConfig.ringVehicleFovStrength()
				: NestedChestClientConfig.minecartFovStrength();
		double target = clamp(targetBoost(camera) * strength, 0.0D, MAX_CONFIGURED_BOOST);
		double rate = target > currentBoost ? RISE_SMOOTHING_RATE : FALL_SMOOTHING_RATE;
		double deltaSeconds = deltaSeconds();
		currentBoost = ease(currentBoost, target, deltaSeconds, rate);
		updateRingTransient(camera, ringVehicle, strength, deltaSeconds);
		double boostedFov = fov
				* (1.0D + clamp(currentBoost + ringTransientBoost, -0.22D, MAX_CONFIGURED_BOOST));
		return Math.min(boostedFov, MAX_FINAL_FOV);
	}

	private static double targetBoost(Camera camera) {
		if (camera.isThirdPerson()) {
			resetCartTracking();
			return 0.0D;
		}
		Entity focused = camera.getFocusedEntity();
		if (focused != null && focused.getVehicle() instanceof RingVehicleEntity ringVehicle) {
			return ringVehicleBoost(ringVehicle);
		}
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

	private static RingVehicleEntity ringVehicleView(Camera camera) {
		Entity focused = camera.getFocusedEntity();
		return focused != null && focused.getVehicle() instanceof RingVehicleEntity vehicle ? vehicle : null;
	}

	private static void updateRingTransient(Camera camera, RingVehicleEntity vehicle,
			double strength, double deltaSeconds) {
		if (vehicle == null || camera.isThirdPerson() || strength <= 0.0D) {
			ringTransientBoost = ease(ringTransientBoost, 0.0D, deltaSeconds, 8.0D);
			ringLaunchHoldSeconds = 0.0D;
			lastRingTransientVehicleId = vehicle == null ? Integer.MIN_VALUE : vehicle.getId();
			lastRingLaunchEvent = vehicle == null ? 0 : vehicle.getLaunchEvent();
			return;
		}
		if (lastRingTransientVehicleId != vehicle.getId()) {
			lastRingTransientVehicleId = vehicle.getId();
			lastRingLaunchEvent = vehicle.getLaunchEvent();
			ringTransientBoost = 0.0D;
			ringLaunchHoldSeconds = 0.0D;
		}
		if (vehicle.getLaunchEvent() != lastRingLaunchEvent) {
			lastRingLaunchEvent = vehicle.getLaunchEvent();
			double launchStrength = Math.sqrt(clamp(vehicle.getLaunchStrength(), 0.0D, 1.0D));
			double configuredScale = strength / NestedChestClientConfig.DEFAULT_RING_VEHICLE_FOV_STRENGTH;
			ringTransientBoost = Math.min(1.25D,
					(0.34D + 0.46D * launchStrength) * configuredScale);
			ringLaunchHoldSeconds = 0.42D + 0.38D * launchStrength;
		}
		if (ringLaunchHoldSeconds > 0.0D) {
			ringLaunchHoldSeconds = Math.max(0.0D, ringLaunchHoldSeconds - deltaSeconds);
			return;
		}
		if (vehicle.getClutchPhase() == RingVehicleEntity.CLUTCH_ALIGNING
				|| vehicle.getClutchPhase() == RingVehicleEntity.CLUTCH_ENGAGED) {
			double compression = -Math.min(0.38D,
					currentBoost + Math.min(0.14D, 0.07D * strength));
			ringTransientBoost = ease(ringTransientBoost, compression, deltaSeconds, 12.5D);
		} else {
			ringTransientBoost = ease(ringTransientBoost, 0.0D, deltaSeconds,
					ringTransientBoost > 0.0D ? 1.35D : 7.0D);
		}
	}

	private static double ringVehicleBoost(RingVehicleEntity vehicle) {
		Vec3d velocity = vehicle.getVelocity();
		double speed = velocity.length();
		if (vehicle.getId() != lastCartId) {
			lastCartId = vehicle.getId();
			lastMovementSignature = Integer.MIN_VALUE;
			lastSpeed = speed;
			stateChangeResetUntilNanos = 0L;
		}
		if (vehicle.isSpiderMode()) {
			return spiderVehicleBoost(vehicle, speed);
		}
		resetSpiderFovTracking();
		if (speed < 0.04D) {
			lastSpeed = speed;
			return 0.0D;
		}
		double movementLevel = smoothStep(0.04D, 0.14D, speed);
		double speedLevel = smoothStep(0.12D, 1.15D, speed);
		double speedBoost = speedLevel * 0.19D;
		double acceleration = Math.max(0.0D, speed - lastSpeed);
		lastSpeed = speed;
		double accelerationBoost = clamp(acceleration * 5.5D, 0.0D, 0.065D);
		double poweredBoost = vehicle.getVariant().powered() && vehicle.isHighGear()
				? 0.035D * smoothStep(0.15D, 0.55D, speed)
				: 0.0D;
		double verticalDescent = velocity.y < -0.08D
				? smoothStep(0.08D, 1.25D, -velocity.y) * 0.085D
				: 0.0D;
		return clamp((speedBoost + poweredBoost + verticalDescent) * movementLevel + accelerationBoost,
				0.0D, 0.30D);
	}

	private static double spiderVehicleBoost(RingVehicleEntity vehicle, double speed) {
		long now = System.nanoTime();
		float gaitPhase = vehicle.getSpiderGaitPhase();
		if (lastSpiderFovVehicleId != vehicle.getId()) {
			lastSpiderFovVehicleId = vehicle.getId();
			lastSpiderGaitPhase = gaitPhase;
			filteredSpiderSpeed = speed;
			spiderMotionHoldUntilNanos = 0L;
		}
		double phaseDelta = Math.abs(gaitPhase - lastSpiderGaitPhase);
		phaseDelta = Math.min(phaseDelta, 1.0D - Math.min(phaseDelta, 1.0D));
		if (phaseDelta > 0.0005D) {
			spiderMotionHoldUntilNanos = now + 420_000_000L;
		}
		lastSpiderGaitPhase = gaitPhase;
		boolean walking = speed >= 0.028D || now < spiderMotionHoldUntilNanos;
		double response = speed > filteredSpiderSpeed ? 0.24D : walking ? 0.018D : 0.085D;
		filteredSpiderSpeed += (speed - filteredSpiderSpeed) * response;
		if (!walking && filteredSpiderSpeed < 0.025D) {
			lastSpeed = filteredSpiderSpeed;
			return 0.0D;
		}
		double movementLevel = smoothStep(0.025D, 0.12D, filteredSpiderSpeed);
		double speedLevel = smoothStep(0.10D, 1.15D, filteredSpiderSpeed);
		double poweredBoost = vehicle.getVariant().powered() && vehicle.isHighGear()
				? 0.035D * smoothStep(0.12D, 0.52D, filteredSpiderSpeed)
				: 0.0D;
		lastSpeed = filteredSpiderSpeed;
		return clamp((speedLevel * 0.19D + poweredBoost) * movementLevel, 0.0D, 0.26D);
	}

	private static void resetSpiderFovTracking() {
		lastSpiderFovVehicleId = Integer.MIN_VALUE;
		lastSpiderGaitPhase = 0.0F;
		filteredSpiderSpeed = 0.0D;
		spiderMotionHoldUntilNanos = 0L;
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
		resetSpiderFovTracking();
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
