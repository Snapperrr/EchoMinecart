package com.xc.echominecart.ringvehicle;

import com.xc.echominecart.EchoMinecartRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.List;
import java.util.UUID;
import org.joml.Vector3f;

/** Server-authoritative spider ammunition with swept collision and custom leg hit detection. */
public final class SpiderProjectileEntity extends Entity {
	private static final TrackedData<Integer> AMMO_TYPE = DataTracker.registerData(
			SpiderProjectileEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final int MAX_LIFETIME_TICKS = 80;
	private static final double EMERALD_HOMING_RADIUS = 8.0D;
	private static final double EMERALD_HOMING_BLEND = 0.30D;
	private UUID ownerUuid;
	private int directSpiderId = -1;
	private int homingTargetId = -1;

	public SpiderProjectileEntity(EntityType<? extends SpiderProjectileEntity> type, World world) {
		super(type, world);
		setNoGravity(true);
		noClip = true;
		ignoreCameraFrustum = true;
	}

	public static SpiderProjectileEntity create(ServerWorld world, Entity owner, SpiderAmmoType ammo,
			Vec3d position, Vec3d direction) {
		SpiderProjectileEntity projectile = new SpiderProjectileEntity(
				EchoMinecartRegistry.SPIDER_PROJECTILE_ENTITY, world);
		projectile.ownerUuid = owner == null ? null : owner.getUuid();
		projectile.dataTracker.set(AMMO_TYPE, ammo.ordinal());
		projectile.setPosition(position);
		projectile.setVelocity(direction.normalize().multiply(ammo.speed()));
		projectile.setGlowing(true);
		return projectile;
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		builder.add(AMMO_TYPE, SpiderAmmoType.IRON.ordinal());
	}

	@Override
	public void tick() {
		super.tick();
		Vec3d velocity = getVelocity();
		if (velocity.lengthSquared() < 1.0E-5D || age > MAX_LIFETIME_TICKS) {
			discard();
			return;
		}
		Vec3d start = getPos();
		Vec3d end = start.add(velocity);
		if (getWorld().isClient()) {
			setPosition(end);
			return;
		}
		if (getAmmoType() == SpiderAmmoType.EMERALD) {
			velocity = steerTowardHomingTarget(velocity);
			setVelocity(velocity);
			end = start.add(velocity);
		}

		Collision collision = findCollision(start, end);
		if (collision != null) {
			setPosition(collision.position());
			handleCollision(collision);
			return;
		}
		setPosition(end);
		setVelocity(velocity.multiply(0.998D));
		spawnTrail();
	}

	private Vec3d steerTowardHomingTarget(Vec3d velocity) {
		Entity target = currentHomingTarget();
		if (target == null) {
			target = acquireHomingTarget();
			homingTargetId = target == null ? -1 : target.getId();
		}
		if (target == null) {
			return velocity;
		}
		Vec3d desired = target.getBoundingBox().getCenter().subtract(getPos());
		if (desired.lengthSquared() < 1.0E-5D) {
			return velocity;
		}
		double speed = velocity.length();
		Vec3d steered = velocity.normalize().lerp(desired.normalize(), EMERALD_HOMING_BLEND);
		return steered.lengthSquared() < 1.0E-5D ? velocity : steered.normalize().multiply(speed);
	}

	private Entity currentHomingTarget() {
		if (homingTargetId < 0 || !(getWorld() instanceof ServerWorld world)) {
			return null;
		}
		Entity target = world.getEntityById(homingTargetId);
		if (!isHomingCandidate(target)
				|| target.squaredDistanceTo(this) > EMERALD_HOMING_RADIUS * EMERALD_HOMING_RADIUS) {
			homingTargetId = -1;
			return null;
		}
		return target;
	}

	private Entity acquireHomingTarget() {
		Box searchBox = getBoundingBox().expand(EMERALD_HOMING_RADIUS);
		Entity nearest = null;
		double nearestDistance = EMERALD_HOMING_RADIUS * EMERALD_HOMING_RADIUS;
		for (Entity candidate : getWorld().getOtherEntities(this, searchBox, this::isHomingCandidate)) {
			double distance = candidate.squaredDistanceTo(this);
			if (distance < nearestDistance) {
				nearest = candidate;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	private boolean isHomingCandidate(Entity entity) {
		if (entity == null || !isValidTarget(entity)) {
			return false;
		}
		if (entity instanceof RingVehicleEntity vehicle) {
			return vehicle.isSpiderMode();
		}
		return entity instanceof LivingEntity
				&& !(entity.getVehicle() instanceof RingVehicleEntity vehicle && vehicle.isSpiderMode())
				&& entity.canBeHitByProjectile();
	}

	private Collision findCollision(Vec3d start, Vec3d end) {
		BlockHitResult blockHit = getWorld().raycast(new RaycastContext(start, end,
				RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
		double bestFraction = blockHit.getType() == HitResult.Type.MISS
				? Double.POSITIVE_INFINITY
				: pathFraction(start, end, blockHit.getPos());
		Collision best = blockHit.getType() == HitResult.Type.MISS
				? null
				: new Collision(blockHit.getPos(), null, null, bestFraction);

		Box swept = new Box(start, end);
		List<Entity> nearbySpiders = getWorld().getOtherEntities(this, swept.expand(18.0D),
				entity -> entity instanceof RingVehicleEntity vehicle && vehicle.isSpiderMode()
						&& isValidTarget(vehicle));
		for (Entity entity : nearbySpiders) {
			RingVehicleEntity vehicle = (RingVehicleEntity) entity;
			RingVehicleEntity.SpiderWeaponHit hit = vehicle.traceSpiderWeaponHit(start, end, 0.34D);
			if (hit != null && hit.pathFraction() < bestFraction) {
				bestFraction = hit.pathFraction();
				best = new Collision(hit.position(), vehicle, hit, bestFraction);
			}
		}

		List<Entity> nearbyEntities = getWorld().getOtherEntities(this, swept.expand(0.75D),
				entity -> !(entity instanceof SpiderProjectileEntity)
						&& !(entity instanceof RingVehicleEntity vehicle && vehicle.isSpiderMode())
						&& !(entity.getVehicle() instanceof RingVehicleEntity riddenVehicle
								&& riddenVehicle.isSpiderMode())
						&& isValidTarget(entity) && entity.canBeHitByProjectile());
		for (Entity entity : nearbyEntities) {
			var hit = entity.getBoundingBox().expand(entity.getTargetingMargin() + 0.12D).raycast(start, end);
			if (hit.isEmpty()) {
				continue;
			}
			double fraction = pathFraction(start, end, hit.get());
			if (fraction < bestFraction) {
				bestFraction = fraction;
				best = new Collision(hit.get(), entity, null, fraction);
			}
		}
		return best;
	}

	private boolean isValidTarget(Entity entity) {
		if (!entity.isAlive() || entity == this) {
			return false;
		}
		Entity owner = getOwnerEntity();
		if (owner == null) {
			return true;
		}
		return entity != owner
				&& entity != owner.getVehicle()
				&& !entity.hasPassengerDeep(owner)
				&& !owner.hasPassengerDeep(entity);
	}

	private void handleCollision(Collision collision) {
		SpiderAmmoType ammo = getAmmoType();
		DamageSource source = projectileDamageSource();
		if (collision.entity() instanceof RingVehicleEntity vehicle && collision.spiderHit() != null) {
			directSpiderId = vehicle.getId();
			vehicle.applySpiderWeaponHit(collision.spiderHit().legIndex(), ammo.damage(), source,
					collision.position());
		} else if (collision.entity() != null) {
			collision.entity().damage(source, ammo.damage());
		}

		if (ammo.explosive()) {
			explode(ammo, collision.position());
		} else {
			spawnImpact(collision.position());
			discard();
		}
	}

	private void explode(SpiderAmmoType ammo, Vec3d position) {
		if (!(getWorld() instanceof ServerWorld world)) {
			discard();
			return;
		}
		DamageSource source = projectileDamageSource();
		double radius = ammo.explosionPower() + 1.5D;
		for (Entity entity : world.getOtherEntities(this, Box.of(position, radius * 2.0D,
				radius * 2.0D, radius * 2.0D), candidate -> candidate instanceof RingVehicleEntity vehicle
						&& vehicle.isSpiderMode())) {
			RingVehicleEntity vehicle = (RingVehicleEntity) entity;
			if (vehicle.getId() == directSpiderId) {
				continue;
			}
			double distance = Math.sqrt(vehicle.squaredDistanceTo(position));
			if (distance >= radius) {
				continue;
			}
			float splashDamage = (float) (ammo.damage() * 0.65D * (1.0D - distance / radius));
			vehicle.applySpiderExplosionDamage(splashDamage, source, position);
		}
		world.createExplosion(this, position.x, position.y, position.z, ammo.explosionPower(), false,
				World.ExplosionSourceType.TNT);
		discard();
	}

	private void spawnTrail() {
		if (!(getWorld() instanceof ServerWorld world)) {
			return;
		}
		SpiderAmmoType ammo = getAmmoType();
		if (ammo.projectileModel() == SpiderAmmoType.ProjectileModel.LASER) {
			int color = ammo.color();
			Vector3f rgb = new Vector3f(
					(color >> 16 & 0xFF) / 255.0F,
					(color >> 8 & 0xFF) / 255.0F,
					(color & 0xFF) / 255.0F);
			world.spawnParticles(new DustParticleEffect(rgb, 1.05F),
					getX(), getY(), getZ(), 2, 0.045D, 0.045D, 0.045D, 0.01D);
		} else if (age % 2 == 0 && ammo.explosive()) {
			world.spawnParticles(ammo == SpiderAmmoType.END_CRYSTAL ? ParticleTypes.PORTAL : ParticleTypes.REVERSE_PORTAL,
					getX(), getY(), getZ(), 2, 0.10D, 0.10D, 0.10D, 0.015D);
		}
	}

	private void spawnImpact(Vec3d position) {
		if (!(getWorld() instanceof ServerWorld world)) {
			return;
		}
		world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, position.x, position.y, position.z,
				12, 0.18D, 0.18D, 0.18D, 0.12D);
		world.spawnParticles(ParticleTypes.END_ROD, position.x, position.y, position.z,
				4, 0.10D, 0.10D, 0.10D, 0.04D);
		world.playSound(null, position.x, position.y, position.z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT,
				SoundCategory.PLAYERS, 0.9F, 1.35F);
	}

	private DamageSource projectileDamageSource() {
		return getWorld().getDamageSources().thrown(this, getOwnerEntity());
	}

	public Entity getOwnerEntity() {
		return ownerUuid == null || !(getWorld() instanceof ServerWorld world)
				? null
				: world.getEntity(ownerUuid);
	}

	public SpiderAmmoType getAmmoType() {
		return SpiderAmmoType.byId(dataTracker.get(AMMO_TYPE));
	}

	private static double pathFraction(Vec3d start, Vec3d end, Vec3d point) {
		double lengthSquared = start.squaredDistanceTo(end);
		return lengthSquared < 1.0E-8D ? 0.0D : MathHelper.clamp(
				point.subtract(start).dotProduct(end.subtract(start)) / lengthSquared, 0.0D, 1.0D);
	}

	@Override
	public boolean canHit() {
		return false;
	}

	@Override
	public boolean isCollidable() {
		return false;
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		dataTracker.set(AMMO_TYPE, SpiderAmmoType.byId(nbt.getInt("AmmoType")).ordinal());
		ownerUuid = nbt.containsUuid("Owner") ? nbt.getUuid("Owner") : null;
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		nbt.putInt("AmmoType", getAmmoType().ordinal());
		if (ownerUuid != null) {
			nbt.putUuid("Owner", ownerUuid);
		}
	}

	private record Collision(Vec3d position, Entity entity,
			RingVehicleEntity.SpiderWeaponHit spiderHit, double fraction) {
	}
}
