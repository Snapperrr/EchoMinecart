package com.xc.echominecart.trip;

import com.xc.echominecart.network.TripSyncPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Keeps only already-tripped mobs pinned; rail collision events create new trips. */
public final class TripManager {
	private static final int HOLD_INTERVAL_TICKS = 4;
	private static final Map<UUID, TrippedMob> TRIPPED = new HashMap<>();

	private TripManager() {
	}

	public static void tick(ServerWorld world, int serverTick) {
		if (serverTick % HOLD_INTERVAL_TICKS != 0) {
			return;
		}
		Iterator<Map.Entry<UUID, TrippedMob>> iterator = TRIPPED.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, TrippedMob> entry = iterator.next();
			TrippedMob tripped = entry.getValue();
			if (!tripped.worldKey().equals(world.getRegistryKey())) {
				continue;
			}
			Entity entity = world.getEntity(entry.getKey());
			if (entity == null) {
				continue;
			}
			if (!(entity instanceof LivingEntity living) || entity.isRemoved() || !living.isAlive()) {
				iterator.remove();
				if (entity instanceof LivingEntity living) {
					broadcast(living, new TripSyncPayload(living.getId(), false, 0.0F));
				}
				continue;
			}
			hold(living, tripped);
		}
	}

	public static void onRailCollision(ServerWorld world, BlockPos railPos, Entity entity) {
		if (!(entity instanceof MobEntity mob) || entity.isRemoved() || !mob.isAlive()
				|| TRIPPED.containsKey(mob.getUuid())) {
			return;
		}
		trip(world, railPos, mob);
	}

	public static boolean isTripped(LivingEntity entity) {
		return TRIPPED.containsKey(entity.getUuid());
	}

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
			mob.getNavigation().stop();
		}
		broadcast(entity, new TripSyncPayload(entity.getId(), false, 0.0F));
	}

	private static void trip(ServerWorld world, BlockPos railPos, LivingEntity living) {
		Vec3d velocity = living.getVelocity();
		float yaw = velocity.horizontalLengthSquared() > 1.0E-4D
				? (float) (Math.toDegrees(Math.atan2(velocity.z, velocity.x)) - 90.0D)
				: living.getYaw();
		TrippedMob tripped = new TrippedMob(
				yaw,
				living.hasNoGravity(),
				world.getRegistryKey(),
				railPos.toImmutable());
		TRIPPED.put(living.getUuid(), tripped);
		hold(living, tripped);
		broadcast(living, new TripSyncPayload(living.getId(), true, yaw));
	}

	private static void hold(LivingEntity living, TrippedMob tripped) {
		living.setYaw(tripped.yaw());
		living.setBodyYaw(tripped.yaw());
		living.setHeadYaw(tripped.yaw());
		living.setNoGravity(true);
		BlockPos railPos = tripped.railPos();
		living.refreshPositionAndAngles(
				railPos.getX() + 0.5D,
				railPos.getY() + 0.08D,
				railPos.getZ() + 0.5D,
				tripped.yaw(),
				0.0F);
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

	private record TrippedMob(float yaw, boolean hadNoGravity, RegistryKey<World> worldKey, BlockPos railPos) {
	}
}
