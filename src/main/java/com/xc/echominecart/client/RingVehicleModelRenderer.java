package com.xc.echominecart.client;

import com.xc.echominecart.ringvehicle.RingVehicleEntity;
import com.xc.echominecart.ringvehicle.RingVehicleVariant;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.PoweredRailBlock;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.model.MinecartEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;

/** Shared immediate-mode model renderer used by both entity and item render paths. */
final class RingVehicleModelRenderer {
	private static final Identifier MINECART_TEXTURE = Identifier.ofVanilla("textures/entity/minecart.png");
	private static final float[][] RING_SEGMENTS = {
			{ -1.5F, 0.0F, 0.0F, 1.0F },
			{ -1.0F, 1.0F, 45.0F, 1.41421356F },
			{ 0.0F, 1.5F, 90.0F, 1.0F },
			{ 1.0F, 1.0F, 135.0F, 1.41421356F },
			{ 1.5F, 0.0F, 180.0F, 1.0F },
			{ 1.0F, -1.0F, 225.0F, 1.41421356F },
			{ 0.0F, -1.5F, 270.0F, 1.0F },
			{ -1.0F, -1.0F, 315.0F, 1.41421356F }
	};
	private static final float[][] EXPANDED_RING_SEGMENTS = createCircularSegments(16, 2.5F);

	private final MinecartEntityModel<RingVehicleEntity> minecartModel;
	private final BlockRenderManager blockRenderManager;

	RingVehicleModelRenderer(MinecartEntityModel<RingVehicleEntity> minecartModel,
			BlockRenderManager blockRenderManager) {
		this.minecartModel = minecartModel;
		this.blockRenderManager = blockRenderManager;
	}

	void render(RingVehicleVariant variant, boolean lavaProof, boolean chestAttached, int ringLevel,
			float ringAngle, float innerCartAngle, boolean discMode, int extraMinecarts,
			MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		if (lavaProof) {
			renderObsidianOutline(ringAngle, ringLevel, matrices, vertexConsumers, light);
		}
		renderRailRing(variant, ringAngle, ringLevel, matrices, vertexConsumers, light);
		int minecartCount = discMode
				? 1 + Math.max(0, Math.min(extraMinecarts, RingVehicleEntity.MAX_DISC_EXTRA_MINECARTS))
				: 1;
		for (int minecart = 0; minecart < minecartCount; minecart++) {
			float angle = innerCartAngle + 360.0F * minecart / minecartCount;
			matrices.push();
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(angle));
			matrices.translate(0.0F, (float) -(RingVehicleEntity.INNER_CART_ORBIT_RADIUS
					+ Math.min(ringLevel, RingVehicleEntity.MAX_RING_LEVEL)), 0.0F);
			renderMinecart(matrices, vertexConsumers, light);
			if (chestAttached && minecart == 0) {
				renderChest(matrices, vertexConsumers, light);
			}
			matrices.pop();
		}
	}

	private void renderRailRing(RingVehicleVariant variant, float rotation, int ringLevel, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		BlockState rail = variant.powered()
				? Blocks.POWERED_RAIL.getDefaultState().with(PoweredRailBlock.POWERED, true)
				: Blocks.RAIL.getDefaultState();
		matrices.push();
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(rotation));
		for (float[] segment : segments(ringLevel)) {
			matrices.push();
			matrices.translate(0.0F, segment[0], segment[1]);
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-segment[2]));
			matrices.scale(1.0F, 1.0F, segment[3]);
			matrices.translate(-0.5F, -0.03F, -0.5F);
			blockRenderManager.renderBlockAsEntity(rail, matrices, vertexConsumers, light, OverlayTexture.DEFAULT_UV);
			matrices.pop();
		}
		matrices.pop();
	}

	private void renderMinecart(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		matrices.push();
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90.0F));
		matrices.scale(-1.0F, -1.0F, 1.0F);
		VertexConsumer consumer = vertexConsumers.getBuffer(minecartModel.getLayer(MINECART_TEXTURE));
		minecartModel.render(matrices, consumer, light, OverlayTexture.DEFAULT_UV);
		matrices.pop();
	}

	private void renderChest(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		matrices.push();
		matrices.translate(0.0F, 0.57F, -0.48F);
		matrices.scale(0.62F, 0.62F, 0.62F);
		matrices.translate(-0.5F, -0.5F, -0.5F);
		blockRenderManager.renderBlockAsEntity(
				Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH),
				matrices, vertexConsumers, light, OverlayTexture.DEFAULT_UV);
		matrices.pop();
	}

	private void renderObsidianOutline(float rotation, int ringLevel, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		matrices.push();
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(rotation));
		for (float[] segment : segments(ringLevel)) {
			float radius = (float) Math.sqrt(segment[0] * segment[0] + segment[1] * segment[1]);
			float inwardY = radius > 0.001F ? -segment[0] / radius * 0.035F : 0.0F;
			float inwardZ = radius > 0.001F ? -segment[1] / radius * 0.035F : 0.0F;
			float edgeOffset = ringLevel > 0 ? 0.455F : 0.47F;
			for (float edge : new float[]{-edgeOffset, edgeOffset}) {
				matrices.push();
				matrices.translate(edge, segment[0] + inwardY, segment[1] + inwardZ);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-segment[2]));
				matrices.scale(0.09F, 0.065F, segment[3] * (ringLevel > 0 ? 1.0F : 1.04F));
				matrices.translate(-0.5F, -0.5F, -0.5F);
				blockRenderManager.renderBlockAsEntity(
						Blocks.OBSIDIAN.getDefaultState(), matrices, vertexConsumers, light, OverlayTexture.DEFAULT_UV);
				matrices.pop();
			}
		}
		matrices.pop();
	}

	private static float[][] segments(int ringLevel) {
		return ringLevel > 0 ? EXPANDED_RING_SEGMENTS : RING_SEGMENTS;
	}

	private static float[][] createCircularSegments(int count, float radius) {
		float[][] segments = new float[count][4];
		float chordLength = (float) (2.0D * radius * Math.sin(Math.PI / count));
		for (int index = 0; index < count; index++) {
			float angle = 360.0F * index / count;
			double radians = Math.toRadians(angle);
			segments[index][0] = (float) (-radius * Math.cos(radians));
			segments[index][1] = (float) (radius * Math.sin(radians));
			segments[index][2] = angle;
			segments[index][3] = chordLength;
		}
		return segments;
	}
}
