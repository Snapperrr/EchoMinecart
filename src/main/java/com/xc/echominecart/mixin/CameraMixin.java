package com.xc.echominecart.mixin;

import com.xc.echominecart.client.CarriageClientVisuals;
import com.xc.echominecart.client.NestedChestClientConfig;
import com.xc.echominecart.rail.OmniRailBlock;
import com.xc.echominecart.rail.RailPhysics;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
	private static final Vector3f BASE_FORWARD = new Vector3f(0.0F, 0.0F, -1.0F);
	private static final Vector3f BASE_UP = new Vector3f(0.0F, 1.0F, 0.0F);
	private static final Vector3f BASE_LEFT = new Vector3f(-1.0F, 0.0F, 0.0F);
	private static final double CEILING_CAMERA_DROP = 0.74D;
	private static final double WALL_CAMERA_CLEARANCE = 0.54D;
	private static final double WALL_VERTICAL_CAMERA_CLEARANCE = 0.62D;
	private static final double WALL_CAMERA_LIFT = 0.20D;
	private static final double ATTACHED_ROTATION_SMOOTHING_RATE = 10.5D;

	private int echominecart$smoothedCartId = Integer.MIN_VALUE;
	private boolean echominecart$hasSmoothedAttachedRotation;
	private long echominecart$lastRotationSmoothNanos;
	private final Quaternionf echominecart$smoothedAttachedRotation = new Quaternionf();

	@Shadow
	@Final
	private Quaternionf rotation;

	@Shadow
	@Final
	private Vector3f horizontalPlane;

	@Shadow
	@Final
	private Vector3f verticalPlane;

	@Shadow
	@Final
	private Vector3f diagonalPlane;

	@Shadow
	public abstract Vec3d getPos();

	@Shadow
	protected abstract void setPos(Vec3d pos);

	@Shadow
	protected abstract void moveBy(float f, float g, float h);

	@Shadow
	private float clipToSpace(float desiredCameraDistance) {
		throw new AssertionError();
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void echominecart$tiltFirstPersonWithAttachedMinecart(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
		if (!(focusedEntity.getVehicle() instanceof AbstractMinecartEntity minecart)) {
			resetAttachedRotationSmoothing();
			return;
		}
		var contactOptional = RailPhysics.findContact(minecart.getWorld(), minecart);
		if (contactOptional.isEmpty()) {
			resetAttachedRotationSmoothing();
			return;
		}

		RailPhysics.RailContact contact = contactOptional.get();
		Direction face = contact.face();
		Quaternionf original = new Quaternionf(rotation);
		Quaternionf attached = smoothedAttachedRotation(minecart, targetAttachedRotation(minecart, face));
		rotation.set(attached).mul(original);
		refreshPlanes();
		if (face != Direction.UP) {
			Vec3d anchor = safeCameraAnchor(area, contact, minecart, tickDelta);
			setPos(anchor);
			if (thirdPerson) {
				float scale = focusedEntity instanceof LivingEntity living ? living.getScale() : 1.0F;
				moveBy(-clipToSpace(4.0F * scale), 0.0F, 0.0F);
			}
		}
		applySubtleRideRoll(minecart, tickDelta);
		refreshPlanes();
	}

	private Quaternionf targetAttachedRotation(AbstractMinecartEntity minecart, Direction face) {
		if (face == Direction.UP) {
			return new Quaternionf();
		}
		Quaternionf attached = new Quaternionf(CarriageClientVisuals.attachedRotation(minecart, face));
		if (face == Direction.DOWN || isVerticalWallRide(face, minecart.getVelocity())) {
			attached.mul(RotationAxis.POSITIVE_Y.rotationDegrees(CarriageClientVisuals.planeYawDegrees(minecart, face)));
		}
		return attached;
	}

	private Quaternionf smoothedAttachedRotation(AbstractMinecartEntity minecart, Quaternionf target) {
		long now = System.nanoTime();
		target.normalize();
		if (!echominecart$hasSmoothedAttachedRotation || echominecart$smoothedCartId != minecart.getId()) {
			echominecart$smoothedCartId = minecart.getId();
			echominecart$hasSmoothedAttachedRotation = true;
			echominecart$lastRotationSmoothNanos = now;
			echominecart$smoothedAttachedRotation.set(target);
			return new Quaternionf(echominecart$smoothedAttachedRotation);
		}

		double deltaSeconds = echominecart$lastRotationSmoothNanos == 0L
				? 1.0D / 60.0D
				: (now - echominecart$lastRotationSmoothNanos) / 1_000_000_000.0D;
		echominecart$lastRotationSmoothNanos = now;
		deltaSeconds = Math.max(1.0D / 240.0D, Math.min(0.08D, deltaSeconds));
		float alpha = (float) (1.0D - Math.exp(-ATTACHED_ROTATION_SMOOTHING_RATE * deltaSeconds));
		Quaternionf shortestTarget = closestHemisphere(target);
		echominecart$smoothedAttachedRotation.nlerp(shortestTarget, alpha).normalize();
		return new Quaternionf(echominecart$smoothedAttachedRotation);
	}

	private Quaternionf closestHemisphere(Quaternionf target) {
		Quaternionf adjusted = new Quaternionf(target);
		if (echominecart$smoothedAttachedRotation.dot(adjusted) < 0.0F) {
			adjusted.set(-adjusted.x, -adjusted.y, -adjusted.z, -adjusted.w);
		}
		return adjusted;
	}

	private void resetAttachedRotationSmoothing() {
		echominecart$smoothedCartId = Integer.MIN_VALUE;
		echominecart$hasSmoothedAttachedRotation = false;
		echominecart$lastRotationSmoothNanos = 0L;
		echominecart$smoothedAttachedRotation.identity();
	}

	private void applySubtleRideRoll(AbstractMinecartEntity minecart, float tickDelta) {
		double configuredStrength = NestedChestClientConfig.minecartSwayStrength();
		if (configuredStrength <= 0.0D) {
			return;
		}
		double speed = minecart.getVelocity().length();
		if (speed < 0.035D) {
			return;
		}
		float intensity = (float) Math.min(1.0D, speed / 0.55D);
		float time = minecart.age + tickDelta;
		float seed = rideSeed(minecart);
		float drift = smoothNoise(time * 0.036F + seed * 0.17F, seed) * 0.72F;
		float counter = smoothNoise(time * 0.058F + seed * 0.43F, seed + 17.0F) * 0.36F;
		float railTexture = smoothNoise(time * (0.082F + intensity * 0.018F) + seed * 0.71F, seed + 43.0F) * 0.18F;
		float asymmetry = (float) Math.sin(time * (0.023F + 0.011F * smoothNoise(time * 0.012F, seed + 61.0F)) + seed * 2.37F) * 0.16F;
		float angle = (float) ((drift + counter + railTexture + asymmetry) * intensity * 1.05F * configuredStrength);
		rotation.rotateZ((float) Math.toRadians(angle));
	}

	private float smoothNoise(float x, float seed) {
		int cell = (int) Math.floor(x);
		float t = x - cell;
		float eased = t * t * (3.0F - 2.0F * t);
		return lerp(noiseAt(cell, seed), noiseAt(cell + 1, seed), eased);
	}

	private float noiseAt(int cell, float seed) {
		int n = cell * 374761393 + Float.floatToIntBits(seed) * 668265263;
		n = (n ^ (n >>> 13)) * 1274126177;
		n ^= n >>> 16;
		return ((n & 0xFFFF) / 32767.5F) - 1.0F;
	}

	private float lerp(float from, float to, float delta) {
		return from + (to - from) * delta;
	}

	private Vec3d safeCameraAnchor(BlockView area, RailPhysics.RailContact contact, AbstractMinecartEntity minecart, float tickDelta) {
		Vec3d cartPos = minecart.getLerpedPos(tickDelta);
		if (contact.face() == Direction.DOWN) {
			return ceilingCameraPos(area, contact, cartPos);
		}
		return wallCameraPos(area, contact, cartPos, Vec3d.of(contact.face().getVector()), minecart.getVelocity());
	}

	private Vec3d wallCameraPos(BlockView area, RailPhysics.RailContact contact, Vec3d cartPos, Vec3d normal, Vec3d velocity) {
		Direction face = contact.face();
		Vec3d surface = RailPhysics.surfacePoint(contact.pos(), face);
		double baseX = face.getAxis() == Direction.Axis.X ? surface.x : cartPos.x;
		double baseZ = face.getAxis() == Direction.Axis.Z ? surface.z : cartPos.z;
		double clearance = isVerticalWallRide(face, velocity) ? WALL_VERTICAL_CAMERA_CLEARANCE : WALL_CAMERA_CLEARANCE;
		for (double candidateClearance : new double[]{clearance, 0.58D, 0.50D, 0.42D, 0.34D, 0.24D}) {
			Vec3d candidate = new Vec3d(baseX, cartPos.y + WALL_CAMERA_LIFT, baseZ).add(normal.multiply(candidateClearance));
			if (!hasSolidCollisionAt(area, candidate)) {
				return candidate;
			}
		}
		return new Vec3d(baseX, cartPos.y + WALL_CAMERA_LIFT, baseZ).add(normal.multiply(0.18D));
	}

	private Vec3d ceilingCameraPos(BlockView area, RailPhysics.RailContact contact, Vec3d cartPos) {
		double railY = RailPhysics.surfacePoint(contact.pos(), Direction.DOWN).y;
		for (double drop : new double[]{CEILING_CAMERA_DROP, 0.64D, 0.54D, 0.44D, 0.34D, 0.24D}) {
			Vec3d candidate = new Vec3d(cartPos.x, railY - drop, cartPos.z);
			if (!hasSolidCollisionAt(area, candidate)) {
				return candidate;
			}
		}
		return new Vec3d(cartPos.x, railY - 0.18D, cartPos.z);
	}

	private void refreshPlanes() {
		BASE_FORWARD.rotate(rotation, horizontalPlane);
		BASE_UP.rotate(rotation, verticalPlane);
		BASE_LEFT.rotate(rotation, diagonalPlane);
	}

	private boolean hasSolidCollisionAt(BlockView area, Vec3d pos) {
		BlockPos blockPos = BlockPos.ofFloored(pos);
		if (OmniRailBlock.isOmniRail(area.getBlockState(blockPos))) {
			return false;
		}
		double localX = pos.x - blockPos.getX();
		double localY = pos.y - blockPos.getY();
		double localZ = pos.z - blockPos.getZ();
		for (Box box : area.getBlockState(blockPos).getCollisionShape(area, blockPos).getBoundingBoxes()) {
			if (localX > box.minX + 1.0E-5D && localX < box.maxX - 1.0E-5D
					&& localY > box.minY + 1.0E-5D && localY < box.maxY - 1.0E-5D
					&& localZ > box.minZ + 1.0E-5D && localZ < box.maxZ - 1.0E-5D) {
				return true;
			}
		}
		return false;
	}

	private float rideSeed(AbstractMinecartEntity minecart) {
		long bits = minecart.getUuid().getLeastSignificantBits() ^ ((long) minecart.getId() << 32);
		return (bits & 65535L) * 0.0000958738F;
	}

	private boolean isVerticalWallRide(Direction face, Vec3d velocity) {
		if (!face.getAxis().isHorizontal()) {
			return false;
		}
		double horizontal = Math.max(Math.abs(velocity.x), Math.abs(velocity.z));
		return Math.abs(velocity.y) > 0.035D && Math.abs(velocity.y) > horizontal * 0.75D;
	}
}
