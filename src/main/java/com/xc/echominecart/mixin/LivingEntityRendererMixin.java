package com.xc.echominecart.mixin;

import com.xc.echominecart.client.CarriageClientVisuals;
import com.xc.echominecart.client.TripClientVisuals;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
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
		if (!(entity.getVehicle() instanceof AbstractMinecartEntity minecart)) {
			return;
		}
		Direction face = CarriageClientVisuals.attachedFace(minecart);
		if (face == Direction.UP) {
			return;
		}
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-(180.0F - bodyYaw)));
		if (face == Direction.DOWN) {
			matrices.translate(0.0F, entity.getHeight(), 0.0F);
		}
		matrices.multiply(CarriageClientVisuals.faceRotation(face));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F - bodyYaw));
	}
}
