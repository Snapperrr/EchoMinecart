package com.xc.echominecart.mixin;

import com.xc.echominecart.client.CarriageClientVisuals;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.MinecartEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(MinecartEntityRenderer.class)
public abstract class MinecartEntityRendererMixin {
	@Shadow
	@Final
	private BlockRenderManager blockRenderManager;

	@Inject(
			method = "render(Lnet/minecraft/entity/vehicle/AbstractMinecartEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
			at = @At("HEAD"),
			cancellable = true)
	private void echominecart$hideSuppressedModules(AbstractMinecartEntity minecart, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
		if (CarriageClientVisuals.shouldSkipRender(minecart)) {
			ci.cancel();
		}
	}

	/**
	 * 贴附姿态：注入点在原版 Ry(180−yaw) 与伤害晃动之后。直接叠加固定旋转
	 * 会让 yaw 作用在错误的轴上（侧翻）。这里先撤销原版 yaw，再在世界系里
	 * 做贴面旋转，最后在贴附面内重建行进朝向。
	 */
	@Inject(
			method = "render(Lnet/minecraft/entity/vehicle/AbstractMinecartEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;multiply(Lorg/joml/Quaternionf;)V", ordinal = 1, shift = At.Shift.AFTER))
	private void echominecart$orientToAttachedRail(AbstractMinecartEntity minecart, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
		Direction face = CarriageClientVisuals.attachedFace(minecart);
		if (face == Direction.UP) {
			return;
		}
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-(180.0F - yaw)));
		matrices.multiply(CarriageClientVisuals.attachedRotation(minecart, face));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(CarriageClientVisuals.planeYawDegrees(minecart, face)));
	}

	@Inject(
			method = "render(Lnet/minecraft/entity/vehicle/AbstractMinecartEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;scale(FFF)V", ordinal = 1))
	private void echominecart$renderAttachedChests(AbstractMinecartEntity minecart, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
		List<CarriageClientVisuals.RenderChest> chests = CarriageClientVisuals.chestOffsets(minecart, yaw);
		for (CarriageClientVisuals.RenderChest chest : chests) {
			matrices.push();
			matrices.translate(chest.x(), chest.y(), chest.z());
			matrices.scale(0.72F, 0.72F, 0.72F);
			matrices.translate(-0.5F, -0.12F, -0.5F);
			blockRenderManager.renderBlockAsEntity(
					Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH),
					matrices,
					vertexConsumers,
					light,
					OverlayTexture.DEFAULT_UV);
			matrices.pop();
		}
	}

	/** 扩充车厢：按服务端同步的覆盖格拉伸可见锚点矿车模型。 */
	@Inject(
			method = "render(Lnet/minecraft/entity/vehicle/AbstractMinecartEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;scale(FFF)V", ordinal = 1, shift = At.Shift.AFTER))
	private void echominecart$stretchVisibleBody(AbstractMinecartEntity minecart, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
		CarriageClientVisuals.RenderScale scale = CarriageClientVisuals.renderScale(minecart, yaw);
		matrices.translate(scale.offsetX(), scale.offsetY(), scale.offsetZ());
		matrices.scale(scale.x(), scale.y(), scale.z());
	}
}
