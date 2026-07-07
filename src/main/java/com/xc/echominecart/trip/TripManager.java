package com.xc.echominecart.trip;

import com.xc.echominecart.network.TripSyncPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 铁轨绊倒：非玩家生物踩上任意铁轨即被绊倒，沿走上铁轨时的行进方向
 * 趴倒在轨道中心（姿态由 TripSyncPayload 同步给客户端渲染），
 * 关闭重力、清空速度、钉住位置；受到任意伤害或死亡才解除。
 * 解除时重置导航，避免生物起身后带着过期路径发呆。
 */
public final class TripManager {
	private static final int CHECK_INTERVAL_TICKS = 4;
	private static final Map<UUID, TrippedMob> TRIPPED = new HashMap<>();

	private TripManager() {
	}

	public static void tick(ServerWorld world, int serverTick) {
		if (serverTick % CHECK_INTERVAL_TICKS != 0) {
			return;
		}
		for (Entity entity : world.iterateEntities()) {
			if (entity == null || !(entity instanceof LivingEntity living) || entity instanceof PlayerEntity || entity.isRemoved()) {
				continue;
			}
			boolean tripped = TRIPPED.containsKey(living.getUuid());
			if (!tripped) {
				if (living instanceof MobEntity && railAtOrBelowFeet(world, living)) {
					trip(living);
				}
				continue;
			}
			if (!living.isAlive()) {
				TRIPPED.remove(living.getUuid());
				// 不广播解除的话，客户端会残留趴倒状态，实体 ID 复用时殃及别的生物。
				broadcast(living, new TripSyncPayload(living.getId(), false, 0.0F));
				continue;
			}
			hold(living, TRIPPED.get(living.getUuid()));
		}
	}

	public static boolean isTripped(LivingEntity entity) {
		return TRIPPED.containsKey(entity.getUuid());
	}

	/** 玩家开始追踪某个实体时补发一次绊倒状态（否则后加载的客户端看不到趴倒）。 */
	public static void sendStateTo(Entity entity, ServerPlayerEntity player) {
		if (entity instanceof LivingEntity living) {
			TrippedMob tripped = TRIPPED.get(living.getUuid());
			if (tripped != null) {
				ServerPlayNetworking.send(player, new TripSyncPayload(living.getId(), true, tripped.yaw()));
			}
		}
	}

	public static void release(LivingEntity entity) {
		if (entity.getWorld().isClient()) {
			return;
		}
		TrippedMob tripped = TRIPPED.remove(entity.getUuid());
		if (tripped == null) {
			return;
		}
		entity.setNoGravity(tripped.hadNoGravity());
		entity.setVelocity(Vec3d.ZERO);
		if (entity instanceof MobEntity mob) {
			// 清掉被钉住期间累积的过期路径，起身后立刻重新决策。
			mob.getNavigation().stop();
		}
		broadcast(entity, new TripSyncPayload(entity.getId(), false, 0.0F));
	}

	private static void trip(LivingEntity living) {
		Vec3d velocity = living.getVelocity();
		float yaw = velocity.horizontalLengthSquared() > 1.0E-4D
				? (float) (Math.toDegrees(Math.atan2(velocity.z, velocity.x)) - 90.0D)
				: living.getYaw();
		TrippedMob tripped = new TrippedMob(yaw, living.hasNoGravity());
		TRIPPED.put(living.getUuid(), tripped);
		hold(living, tripped);
		broadcast(living, new TripSyncPayload(living.getId(), true, yaw));
	}

	private static void hold(LivingEntity living, TrippedMob tripped) {
		living.setYaw(tripped.yaw());
		living.setBodyYaw(tripped.yaw());
		living.setHeadYaw(tripped.yaw());
		living.setNoGravity(true);
		BlockPos railPos = nearestRail(living).orElse(living.getBlockPos());
		living.refreshPositionAndAngles(railPos.getX() + 0.5D, railPos.getY() + 0.08D, railPos.getZ() + 0.5D, tripped.yaw(), 0.0F);
		living.setVelocity(Vec3d.ZERO);
		living.velocityModified = true;
		if (living instanceof MobEntity mob) {
			mob.getNavigation().stop();
		}
	}

	private static void broadcast(Entity entity, TripSyncPayload payload) {
		for (ServerPlayerEntity player : PlayerLookup.tracking(entity)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static boolean railAtOrBelowFeet(ServerWorld world, LivingEntity living) {
		BlockPos pos = living.getBlockPos();
		return AbstractRailBlock.isRail(world.getBlockState(pos))
				|| AbstractRailBlock.isRail(world.getBlockState(pos.down()));
	}

	private static Optional<BlockPos> nearestRail(LivingEntity living) {
		BlockPos base = living.getBlockPos();
		for (BlockPos pos : new BlockPos[]{base, base.down(), base.up()}) {
			if (AbstractRailBlock.isRail(living.getWorld().getBlockState(pos))) {
				return Optional.of(pos);
			}
		}
		return Optional.empty();
	}

	private record TrippedMob(float yaw, boolean hadNoGravity) {
	}
}
