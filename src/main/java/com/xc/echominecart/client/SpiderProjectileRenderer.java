package com.xc.echominecart.client;

import com.xc.echominecart.ringvehicle.SpiderAmmoType;
import com.xc.echominecart.ringvehicle.SpiderProjectileEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Renders emissive short laser bolts or animated vanilla explosive-ammunition models. */
public final class SpiderProjectileRenderer extends EntityRenderer<SpiderProjectileEntity> {
	private static final Identifier BLOCK_ATLAS = Identifier.ofVanilla("textures/atlas/blocks.png");
	private static final Identifier WHITE_TEXTURE = Identifier.ofVanilla("textures/misc/white.png");
	private static final Identifier END_CRYSTAL_TEXTURE = Identifier.ofVanilla(
			"textures/entity/end_crystal/end_crystal.png");
	private static final float SINE_45 = (float) Math.sin(Math.PI / 4.0D);
	private final BlockRenderManager blockRenderManager;
	private final ModelPart crystalFrame;
	private final ModelPart crystalCore;

	public SpiderProjectileRenderer(EntityRendererFactory.Context context) {
		super(context);
		blockRenderManager = context.getBlockRenderManager();
		ModelPart crystal = context.getPart(EntityModelLayers.END_CRYSTAL);
		crystalFrame = crystal.getChild("glass");
		crystalCore = crystal.getChild("cube");
		shadowRadius = 0.0F;
	}

	@Override
	public void render(SpiderProjectileEntity entity, float yaw, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		matrices.push();
		orientToVelocity(entity, matrices);
		SpiderAmmoType ammo = entity.getAmmoType();
		if (ammo.projectileModel() == SpiderAmmoType.ProjectileModel.LASER) {
			renderLaser(entity, ammo, matrices, vertexConsumers);
		} else {
			renderExplosiveModel(entity, tickDelta, ammo, matrices, vertexConsumers, light);
		}
		matrices.pop();
		super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
	}

	private static void orientToVelocity(SpiderProjectileEntity entity, MatrixStack matrices) {
		Vec3d velocity = entity.getVelocity();
		if (velocity.lengthSquared() < 1.0E-6D) {
			return;
		}
		Vec3d direction = velocity.normalize();
		matrices.multiply(new Quaternionf().rotationTo(new Vector3f(0.0F, 0.0F, 1.0F),
				new Vector3f((float) direction.x, (float) direction.y, (float) direction.z)));
	}

	private static void renderLaser(SpiderProjectileEntity entity, SpiderAmmoType ammo, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers) {
		int color = ammo.color();
		int red = color >> 16 & 0xFF;
		int green = color >> 8 & 0xFF;
		int blue = color & 0xFF;
		float tailLength = (float) MathHelper.clamp(entity.getVelocity().length() * 1.45D,
				1.75D, 5.65D);
		VertexConsumer consumer = vertexConsumers.getBuffer(
				RenderLayer.getEntityTranslucentEmissive(WHITE_TEXTURE));
		MatrixStack.Entry entry = matrices.peek();
		renderTexturedCrossedBeam(consumer, entry, 0.31F, -tailLength * 1.06F, 0.82F,
				red, green, blue, 82, -0.005F);
		renderTexturedCrossedBeam(consumer, entry, 0.215F, -tailLength, 0.72F,
				red, green, blue, 232, 0.0F);
		renderTexturedCrossedBeam(consumer, entry, 0.078F, -tailLength * 0.92F, 0.66F,
				255, 255, 255, 252, 0.004F);
	}

	private static void renderTexturedCrossedBeam(VertexConsumer consumer, MatrixStack.Entry entry,
			float radius, float tail, float nose, int red, int green, int blue, int alpha,
			float planeOffset) {
		texturedQuad(consumer, entry,
				-radius, planeOffset, tail,
				radius, planeOffset, tail,
				radius, planeOffset, nose,
				-radius, planeOffset, nose,
				red, green, blue, alpha, 0.0F, 1.0F, 0.0F);
		texturedQuad(consumer, entry,
				planeOffset, -radius, tail,
				planeOffset, radius, tail,
				planeOffset, radius, nose,
				planeOffset, -radius, nose,
				red, green, blue, alpha, 1.0F, 0.0F, 0.0F);
	}

	private static void texturedQuad(VertexConsumer consumer, MatrixStack.Entry entry,
			float x1, float y1, float z1, float x2, float y2, float z2,
			float x3, float y3, float z3, float x4, float y4, float z4,
			int red, int green, int blue, int alpha, float nx, float ny, float nz) {
		texturedVertex(consumer, entry, x1, y1, z1, 0.0F, 0.0F, red, green, blue, alpha, nx, ny, nz);
		texturedVertex(consumer, entry, x2, y2, z2, 1.0F, 0.0F, red, green, blue, alpha, nx, ny, nz);
		texturedVertex(consumer, entry, x3, y3, z3, 1.0F, 1.0F, red, green, blue, alpha, nx, ny, nz);
		texturedVertex(consumer, entry, x4, y4, z4, 0.0F, 1.0F, red, green, blue, alpha, nx, ny, nz);
	}

	private static void texturedVertex(VertexConsumer consumer, MatrixStack.Entry entry,
			float x, float y, float z, float u, float v,
			int red, int green, int blue, int alpha, float nx, float ny, float nz) {
		consumer.vertex(entry, x, y, z)
				.color(red, green, blue, alpha)
				.texture(u, v)
				.overlay(OverlayTexture.DEFAULT_UV)
				.light(LightmapTextureManager.MAX_LIGHT_COORDINATE)
				.normal(entry, nx, ny, nz);
	}

	private static void renderCrossedBeam(VertexConsumer consumer, Matrix4f matrix, float radius,
			float halfLength, int red, int green, int blue, int alpha) {
		quad(consumer, matrix,
				-radius, 0.0F, -halfLength,
				radius, 0.0F, -halfLength,
				radius, 0.0F, halfLength,
				-radius, 0.0F, halfLength,
				red, green, blue, alpha);
		quad(consumer, matrix,
				0.0F, -radius, -halfLength,
				0.0F, radius, -halfLength,
				0.0F, radius, halfLength,
				0.0F, -radius, halfLength,
				red, green, blue, alpha);
	}

	private static void quad(VertexConsumer consumer, Matrix4f matrix,
			float x1, float y1, float z1, float x2, float y2, float z2,
			float x3, float y3, float z3, float x4, float y4, float z4,
			int red, int green, int blue, int alpha) {
		consumer.vertex(matrix, x1, y1, z1).color(red, green, blue, alpha);
		consumer.vertex(matrix, x2, y2, z2).color(red, green, blue, alpha);
		consumer.vertex(matrix, x3, y3, z3).color(red, green, blue, alpha);
		consumer.vertex(matrix, x4, y4, z4).color(red, green, blue, alpha);
	}

	private void renderExplosiveModel(SpiderProjectileEntity entity, float tickDelta,
			SpiderAmmoType ammo, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		float time = entity.age + tickDelta;
		float pulse = 1.0F + MathHelper.sin(time * 0.55F) * 0.08F;
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(time * 18.0F));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(time * 11.0F));
		matrices.scale(pulse, pulse, pulse);
		renderModelGlow(ammo, matrices, vertexConsumers);
		if (ammo == SpiderAmmoType.RESPAWN_ANCHOR) {
			matrices.scale(0.62F, 0.62F, 0.62F);
			matrices.translate(-0.5F, -0.5F, -0.5F);
			blockRenderManager.renderBlockAsEntity(Blocks.RESPAWN_ANCHOR.getDefaultState()
					.with(RespawnAnchorBlock.CHARGES, 4), matrices, vertexConsumers,
					light, OverlayTexture.DEFAULT_UV);
		} else {
			renderEndCrystal(time, matrices, vertexConsumers, light);
		}
	}

	private void renderEndCrystal(float time, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		VertexConsumer consumer = vertexConsumers.getBuffer(
				RenderLayer.getEntityCutoutNoCull(END_CRYSTAL_TEXTURE));
		matrices.scale(0.72F, 0.72F, 0.72F);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(time * 3.0F));
		matrices.multiply(new Quaternionf().setAngleAxis((float) Math.PI / 3.0F,
				SINE_45, 0.0F, SINE_45));
		crystalFrame.render(matrices, consumer, light, OverlayTexture.DEFAULT_UV);
		matrices.scale(0.875F, 0.875F, 0.875F);
		matrices.multiply(new Quaternionf().setAngleAxis((float) Math.PI / 3.0F,
				SINE_45, 0.0F, SINE_45));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(time * 3.0F));
		crystalFrame.render(matrices, consumer, light, OverlayTexture.DEFAULT_UV);
		matrices.scale(0.875F, 0.875F, 0.875F);
		matrices.multiply(new Quaternionf().setAngleAxis((float) Math.PI / 3.0F,
				SINE_45, 0.0F, SINE_45));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(time * 3.0F));
		crystalCore.render(matrices, consumer, light, OverlayTexture.DEFAULT_UV);
	}

	private static void renderModelGlow(SpiderAmmoType ammo, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers) {
		int color = ammo.color();
		VertexConsumer consumer = vertexConsumers.getBuffer(RenderLayer.getLightning());
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		renderCrossedBeam(consumer, matrix, 0.46F, 0.48F,
				color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, 78);
	}

	@Override
	public Identifier getTexture(SpiderProjectileEntity entity) {
		return BLOCK_ATLAS;
	}
}
