package com.xc.echominecart.mixin;

import com.xc.echominecart.client.CarriageClientVisuals;
import com.xc.echominecart.client.TripClientVisuals;
import com.xc.echominecart.rail.RailPhysics;
import com.xc.echominecart.ringvehicle.RingVehicleEntity;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 两类姿态叠加，都要先用 bodyYaw 共轭撤销原版的 Ry(180−bodyYaw)，
 * 在世界系里旋转后再考虑是否重放：
 * - 被铁轨绊倒的生物：面朝下趴倒，身体沿绊倒时的行进方向。
 * - 墙面/天花板轨上的乘客：随矿车贴面旋转，保留自己的面内朝向。
 */
/** Applies tripped poses and ring-vehicle passenger render-anchor corrections. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(
			method = "setupTransforms(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/util/math/MatrixStack;FFFF)V",
			at = @At("RETURN"))
	private void echominecart$applyCustomPose(LivingEntity entity, MatrixStack matrices, float animationProgress, float bodyYaw, float tickDelta, float scale, CallbackInfo ci) {
		Float trippedYaw = TripClientVisuals.trippedYaw(entity.getId());
		if (trippedYaw != null && !entity.hasVehicle()) {
			// 撤销原版 yaw → 转到绊倒朝向 → 抬到轨面上方 → 向前扑倒（面朝下）。
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-(180.0F - bodyYaw)));
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F - trippedYaw));
			matrices.translate(0.0F, 0.14F, 0.0F);
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90.0F));
			return;
		}
		if (entity.getVehicle() instanceof RingVehicleEntity ringVehicle) {
			float angle = ringVehicle.getVisualInnerCartAngle(tickDelta);
			double yawRadians = Math.toRadians(ringVehicle.getVisualBodyYaw(tickDelta));
			Vec3d heading = new Vec3d(-Math.sin(yawRadians), 0.0D, Math.cos(yawRadians));
			Vec3d worldUp = Vec3d.of(Direction.UP.getVector());
			Vec3d axis = worldUp.crossProduct(heading).normalize();
			double angleRadians = Math.toRadians(angle);
			Quaternionf orbit = new Quaternionf().rotationAxis((float) angleRadians,
					(float) axis.x, (float) axis.y, (float) axis.z);
			float leanAngle = ringVehicle.getTurnVisualLean(tickDelta);
			Quaternionf lean = new Quaternionf().rotationAxis((float) Math.toRadians(leanAngle),
					(float) heading.x, (float) heading.y, (float) heading.z);
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-(180.0F - bodyYaw)));
			Vec3d seatCorrection = ringVehicle.getVisualPassengerAnchor(tickDelta)
					.subtract(entity.getLerpedPos(tickDelta));
			matrices.translate(seatCorrection.x, seatCorrection.y, seatCorrection.z);
			if (ringVehicle.isSpiderMode()) {
				Vec3d bodyNormal = ringVehicle.getSpiderBodyNormal(tickDelta);
				matrices.multiply(new Quaternionf().rotationTo(
						new Vector3f(0.0F, 1.0F, 0.0F),
						new Vector3f((float) bodyNormal.x, (float) bodyNormal.y, (float) bodyNormal.z)));
				matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F - bodyYaw));
				return;
			}
			if (ringVehicle.isDiscMode()) {
				Quaternionf discTilt = new Quaternionf().rotationAxis((float) Math.toRadians(90.0D),
						(float) heading.x, (float) heading.y, (float) heading.z);
				Quaternionf discOrbit = new Quaternionf().rotationAxis((float) angleRadians, 0.0F, 1.0F, 0.0F);
				matrices.multiply(discOrbit);
				matrices.multiply(discTilt);
			} else {
				matrices.multiply(lean);
				matrices.multiply(orbit);
			}
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F - bodyYaw));
			return;
		}
		if (!(entity.getVehicle() instanceof AbstractMinecartEntity minecart)) {
			return;
		}
		Direction face = CarriageClientVisuals.attachedFace(minecart);
		if (face == Direction.UP) {
			return;
		}
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-(180.0F - bodyYaw)));
		if (entity instanceof PlayerEntity && face.getAxis().isHorizontal()) {
			alignVerticalPassengerRender(entity, minecart, tickDelta, matrices);
		}
		if (face == Direction.DOWN) {
			matrices.translate(0.0F, entity.getHeight(), 0.0F);
		}
		matrices.multiply(CarriageClientVisuals.faceRotation(face));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F - bodyYaw));
	}

	private static void alignVerticalPassengerRender(LivingEntity entity, AbstractMinecartEntity minecart,
			float tickDelta, MatrixStack matrices) {
		Vec3d velocity = minecart.getVelocity();
		if (Math.abs(velocity.y) < 0.04D || Math.abs(velocity.y) < velocity.horizontalLength() * 1.5D) {
			return;
		}
		RailPhysics.passengerAttachmentOffset(minecart).ifPresent(offset -> {
			Vec3d expected = minecart.getLerpedPos(tickDelta).add(offset);
			Vec3d correction = expected.subtract(entity.getLerpedPos(tickDelta));
			if (correction.lengthSquared() > 0.75D * 0.75D) {
				correction = correction.normalize().multiply(0.75D);
			}
			matrices.translate(correction.x, correction.y, correction.z);
		});
	}

}
