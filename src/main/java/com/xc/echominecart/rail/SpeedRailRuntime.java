package com.xc.echominecart.rail;

import com.xc.echominecart.EchoMinecartRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.RailBlock;
import net.minecraft.block.enums.RailShape;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Applies a speed rail's configured value as a replacement velocity, not an additive impulse.
 * A short per-cart record restores that velocity after vanilla rail movement and temporarily raises
 * the vanilla speed cap so intentionally extreme settings are not discarded immediately.
 */
public final class SpeedRailRuntime {
	private static final long SAME_RAIL_COOLDOWN_TICKS = 8L;
	private static final long MAX_BOOST_LIFETIME_TICKS = 20L * 60L;
	private static final Map<AbstractMinecartEntity, BoostState> BOOSTS = new WeakHashMap<>();

	private SpeedRailRuntime() {
	}

	/** Sets the target tangent velocity once per rail entry. Negative values reverse the tangent. */
	public static void beforeMoveOnRail(AbstractMinecartEntity cart, BlockPos pos, BlockState state) {
		if (!(cart.getWorld() instanceof ServerWorld world) || !state.isOf(EchoMinecartRegistry.SPEED_RAIL)) {
			return;
		}
		long now = world.getTime();
		BoostState previous = BOOSTS.get(cart);
		if (previous != null && previous.railPos.equals(pos) && now <= previous.cooldownUntil) {
			return;
		}

		double speed = SpeedRailStorage.getSpeed(world, pos);
		Vec3d targetVelocity = railTangent(cart, state).multiply(speed);
		cart.setVelocity(targetVelocity);
		cart.velocityModified = true;
		BOOSTS.put(cart, new BoostState(
				pos.toImmutable(), now, now + SAME_RAIL_COOLDOWN_TICKS,
				now + MAX_BOOST_LIFETIME_TICKS, targetVelocity));
	}

	public static void afterMoveOnRail(AbstractMinecartEntity cart, BlockPos pos, BlockState state) {
		if (!(cart.getWorld() instanceof ServerWorld world) || !state.isOf(EchoMinecartRegistry.SPEED_RAIL)) {
			return;
		}
		BoostState boost = BOOSTS.get(cart);
		if (boost != null && boost.appliedTick == world.getTime() && boost.railPos.equals(pos)) {
			cart.setVelocity(boost.targetVelocity);
			cart.velocityModified = true;
		}
	}

	public static double maxSpeed(AbstractMinecartEntity cart, double vanillaMaximum) {
		if (!(cart.getWorld() instanceof ServerWorld world)) {
			return vanillaMaximum;
		}
		BoostState boost = BOOSTS.get(cart);
		if (boost == null) {
			return vanillaMaximum;
		}
		double current = Math.max(cart.getVelocity().horizontalLength(), Math.abs(cart.getVelocity().y));
		if (world.getTime() > boost.preserveUntil || current <= vanillaMaximum * 1.01D) {
			BOOSTS.remove(cart);
			return vanillaMaximum;
		}
		return Math.max(vanillaMaximum, current);
	}

	private static Vec3d railTangent(AbstractMinecartEntity cart, BlockState state) {
		RailShape shape = state.get(RailBlock.SHAPE);
		Vec3d axis = switch (shape) {
			case NORTH_SOUTH -> new Vec3d(0.0D, 0.0D, 1.0D);
			case EAST_WEST -> new Vec3d(1.0D, 0.0D, 0.0D);
			case ASCENDING_EAST -> new Vec3d(1.0D, 1.0D, 0.0D).normalize();
			case ASCENDING_WEST -> new Vec3d(-1.0D, 1.0D, 0.0D).normalize();
			case ASCENDING_NORTH -> new Vec3d(0.0D, 1.0D, -1.0D).normalize();
			case ASCENDING_SOUTH -> new Vec3d(0.0D, 1.0D, 1.0D).normalize();
			case NORTH_EAST, SOUTH_WEST -> new Vec3d(1.0D, 0.0D, 1.0D).normalize();
			case NORTH_WEST, SOUTH_EAST -> new Vec3d(1.0D, 0.0D, -1.0D).normalize();
		};
		Vec3d reference = cart.getVelocity();
		if (reference.lengthSquared() < 1.0E-5D) {
			reference = cart.getRotationVec(1.0F);
		}
		return reference.dotProduct(axis) < 0.0D ? axis.multiply(-1.0D) : axis;
	}

	private record BoostState(BlockPos railPos, long appliedTick, long cooldownUntil,
			long preserveUntil, Vec3d targetVelocity) {
	}
}
