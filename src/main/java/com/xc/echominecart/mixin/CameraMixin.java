package com.xc.echominecart.mixin;

import com.xc.echominecart.client.CarriageClientVisuals;
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
			return;
		}
		RailPhysics.findContact(minecart.getWorld(), minecart).ifPresent(contact -> {
			Direction face = contact.face();
			if (face != Direction.UP) {
				Quaternionf original = new Quaternionf(rotation);
				Quaternionf attached = new Quaternionf(CarriageClientVisuals.attachedRotation(minecart, face));
				if (face == Direction.DOWN || isVerticalWallRide(face, minecart.getVelocity())) {
					attached.mul(RotationAxis.POSITIVE_Y.rotationDegrees(CarriageClientVisuals.planeYawDegrees(minecart, face)));
				}
				rotation.set(attached).mul(original);
				refreshPlanes();
				Vec3d anchor = safeCameraAnchor(area, contact, minecart, tickDelta);
				setPos(anchor);
				if (thirdPerson) {
					float scale = focusedEntity instanceof LivingEntity living ? living.getScale() : 1.0F;
					moveBy(-clipToSpace(4.0F * scale), 0.0F, 0.0F);
				}
			}
			applySubtleRideRoll(minecart, tickDelta);
			refreshPlanes();
		});
	}

	private void applySubtleRideRoll(AbstractMinecartEntity minecart, float tickDelta) {
		double speed = minecart.getVelocity().length();
		if (speed < 0.035D) {
			return;
		}
		float intensity = (float) Math.min(1.0D, speed / 0.55D);
		float time = minecart.age + tickDelta;
		float seed = rideSeed(minecart);
		float sway = (float) (
				Math.sin(time * (0.115F + intensity * 0.025F) + seed) * 0.58D
						+ Math.sin(time * 0.071F + seed * 1.73F) * 0.27D
						+ Math.sin(time * 0.163F + seed * 0.41F) * 0.15D);
		float wander = (float) Math.sin(time * 0.031F + seed * 2.37F) * 0.22F;
		float angle = (sway + wander) * intensity * 0.95F;
		rotation.rotateZ((float) Math.toRadians(angle));
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
