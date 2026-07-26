package com.xc.echominecart.client;

import com.xc.echominecart.ringvehicle.RingVehicleEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.MinecartEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/** Applies interpolated body, ring, inner-cart, lean, bob, and mode-transition transforms. */
public final class RingVehicleRenderer extends EntityRenderer<RingVehicleEntity> {
	private static final Identifier MINECART_TEXTURE = Identifier.ofVanilla("textures/entity/minecart.png");
	private final RingVehicleModelRenderer modelRenderer;

	public RingVehicleRenderer(EntityRendererFactory.Context context) {
		super(context);
		this.modelRenderer = new RingVehicleModelRenderer(
				new MinecartEntityModel<>(context.getPart(EntityModelLayers.MINECART)),
				context.getBlockRenderManager());
		this.shadowRadius = 1.45F;
	}

	@Override
	public void render(RingVehicleEntity entity, float yaw, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		this.shadowRadius = entity.getRingDiameter() * 0.48F;
		super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
		matrices.push();
		Vec3d takeoffShake = entity.getDiscVisualShakeOffset(tickDelta);
		matrices.translate(takeoffShake.x, takeoffShake.y, takeoffShake.z);
		matrices.translate(0.0D, entity.getVisualRenderCenterHeight(tickDelta) + entity.getRideVisualBob(tickDelta), 0.0D);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-entity.getVisualBodyYaw(tickDelta)));
		if (entity.isSpiderMode()) {
			float recoil = entity.getSpiderWeaponRecoil(tickDelta);
			matrices.translate(0.0F, recoil * 0.035F,
					-recoil * (0.24F + entity.getRingLevel() * 0.07F));
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-recoil * 3.5F));
		}
		float discBlend = entity.getDiscVisualBlend(tickDelta);
		float visualRoll = entity.isSpiderMode()
				? 0.0F
				: MathHelper.lerp(discBlend, entity.getTurnVisualLean(tickDelta), 90.0F);
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(visualRoll));
		float ringAnimation = entity.isSpiderMode()
				? entity.getSpiderGaitPhase() * 360.0F
				: entity.getVisualRingAngle(tickDelta);
		modelRenderer.render(entity, tickDelta, entity.getVariant(), entity.isLavaProof(), entity.hasChestAttached(), entity.getRingLevel(),
				ringAnimation, entity.getVisualInnerCartAngle(tickDelta),
				entity.isSpiderMode(), entity.isDiscMode(), entity.getDiscExtraMinecarts(),
				matrices, vertexConsumers, light);
		matrices.pop();
	}

	@Override
	public Identifier getTexture(RingVehicleEntity entity) {
		return MINECART_TEXTURE;
	}
}
