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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Matrix3f;
import org.joml.Vector3f;

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

	void render(RingVehicleEntity entity, float tickDelta, RingVehicleVariant variant, boolean lavaProof, boolean chestAttached, int ringLevel,
			float ringAngle, float innerCartAngle, boolean spiderMode, boolean discMode, int extraMinecarts,
			MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		if (spiderMode) {
			renderSpiderVehicle(entity, tickDelta, variant, lavaProof, chestAttached, ringLevel, ringAngle,
					matrices, vertexConsumers, light);
			return;
		}
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
			if (chestAttached && !discMode && minecart == 0) {
				renderChest(matrices, vertexConsumers, light);
			}
			matrices.pop();
		}
	}

	void render(RingVehicleVariant variant, boolean lavaProof, boolean chestAttached, int ringLevel,
			float ringAngle, float innerCartAngle, boolean spiderMode, boolean discMode, int extraMinecarts,
			MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		render(null, 1.0F, variant, lavaProof, chestAttached, ringLevel, ringAngle, innerCartAngle,
				spiderMode, discMode, extraMinecarts, matrices, vertexConsumers, light);
	}

	void renderSpiderLegItem(RingVehicleVariant variant, boolean lavaProof, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		BlockState legState = variant.powered()
				? Blocks.POWERED_RAIL.getDefaultState().with(PoweredRailBlock.POWERED, true)
				: Blocks.RAIL.getDefaultState();
		BlockState jointState = Blocks.IRON_BLOCK.getDefaultState();
		BlockState frameState = Blocks.OBSIDIAN.getDefaultState();
		Vec3d radial = new Vec3d(1.0D, 0.0D, 0.0D);
		Vec3d bodyUp = new Vec3d(0.0D, 1.0D, 0.0D);
		Vec3d[] points = {
				new Vec3d(-1.18D, 0.48D, 0.0D),
				new Vec3d(-0.62D, 0.72D, 0.04D),
				new Vec3d(-0.05D, 0.52D, 0.0D),
				new Vec3d(0.58D, 1.12D, 0.10D),
				new Vec3d(1.18D, 0.24D, -0.04D),
				new Vec3d(1.48D, -0.76D, 0.02D)
		};
		float[] widths = {0.50F, 0.56F, 0.54F, 0.47F, 0.47F};
		if (lavaProof) {
			for (int index = 2; index < points.length - 1; index++) {
				renderSpiderRailOutlineSegment(points[index], points[index + 1], frameState,
						widths[index], bodyUp, radial, matrices, vertexConsumers, light);
			}
		}
		renderSpiderIronConnector(points[0], points[2], 0.52F, bodyUp, radial,
				matrices, vertexConsumers, light);
		for (int index = 2; index < points.length - 1; index++) {
			double startTrim = switch (index) {
				case 2 -> 0.24D;
				case 3 -> 0.215D;
				default -> 0.195D;
			};
			double endTrim = switch (index) {
				case 2 -> 0.215D;
				case 3 -> 0.195D;
				default -> 0.17D;
			};
			renderSpiderRailSegmentTrimmed(points[index], points[index + 1], legState,
					widths[index], bodyUp, radial, false, 1, startTrim, endTrim,
					matrices, vertexConsumers, light);
		}
		renderSpiderJoint(points[2], jointState, 0.40F, matrices, vertexConsumers, light);
		renderSpiderJoint(points[3], jointState, 0.36F, matrices, vertexConsumers, light);
		renderSpiderJoint(points[4], jointState, 0.33F, matrices, vertexConsumers, light);
		Vec3d footCenter = points[points.length - 1].add(0.0D, 0.10D, 0.0D);
		renderSpiderJoint(footCenter, jointState, 0.28F, matrices, vertexConsumers, light);
		matrices.push();
		matrices.translate((float) footCenter.x, (float) footCenter.y, (float) footCenter.z);
		matrices.multiply(segmentRotation(radial, bodyUp, new Vec3d(0.0D, 0.0D, 1.0D)));
		matrices.scale(0.52F, 0.16F, 0.68F);
		matrices.translate(-0.5F, -0.5F, -0.5F);
		blockRenderManager.renderBlockAsEntity(Blocks.IRON_BLOCK.getDefaultState(), matrices,
				vertexConsumers, light, OverlayTexture.DEFAULT_UV);
		matrices.pop();
	}

	private void renderSpiderVehicle(RingVehicleEntity entity, float tickDelta, RingVehicleVariant variant, boolean lavaProof, boolean chestAttached,
			int ringLevel, float ringAngle, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		Quaternionf bodyTilt = spiderBodyTilt(entity, tickDelta);
		matrices.push();
		matrices.multiply(bodyTilt);
		renderSpiderSuspensionRailLoop(variant, ringLevel, matrices, vertexConsumers, light);
		matrices.pop();
		renderSpiderLegs(entity, tickDelta, variant, lavaProof, ringLevel, bodyTilt,
				matrices, vertexConsumers, light);
		float suspensionOffset = entity == null ? -0.075F : entity.getSpiderSuspensionOffset(tickDelta);
		renderSpiderSuspensionCables(entity, tickDelta, ringLevel, suspensionOffset, bodyTilt,
				matrices, vertexConsumers, light);

		matrices.push();
		matrices.multiply(bodyTilt);
		matrices.translate(0.0F, suspensionOffset, 0.0F);
		matrices.scale(0.86F, 0.86F, 0.86F);
		renderMinecart(matrices, vertexConsumers, light);
		if (chestAttached) {
			renderChest(matrices, vertexConsumers, light);
		}
		matrices.pop();
	}

	private void renderSpiderSuspensionRailLoop(RingVehicleVariant variant, int ringLevel, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		BlockState rail = variant.powered()
				? Blocks.POWERED_RAIL.getDefaultState().with(PoweredRailBlock.POWERED, true)
				: Blocks.RAIL.getDefaultState();
		matrices.push();
		// Match flight mode: rotate the original upright rail wheel onto its side as one unit.
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90.0F));
		for (float[] segment : segments(ringLevel)) {
			float chordLength = Math.max(0.08F, segment[3] - 0.035F);
			float ringThickness = ringLevel > 0 ? 1.42F : 1.34F;
			float coreDepth = ringLevel > 0 ? 0.34F : 0.30F;
			matrices.push();
			matrices.translate(0.0F, segment[0], segment[1]);
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-segment[2]));
			matrices.translate(0.0F, -coreDepth * 0.18F, 0.0F);
			matrices.scale(ringThickness * 0.82F, coreDepth, chordLength);
			matrices.translate(-0.5F, -0.5F, -0.5F);
			blockRenderManager.renderBlockAsEntity(Blocks.IRON_BLOCK.getDefaultState(), matrices,
					vertexConsumers, light, OverlayTexture.DEFAULT_UV);
			matrices.pop();

			matrices.push();
			matrices.translate(0.0F, segment[0], segment[1]);
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-segment[2]));
			matrices.translate(0.0F, coreDepth * 0.50F, 0.0F);
			matrices.scale(ringThickness, 1.0F, Math.max(0.08F, chordLength - 0.015F));
			matrices.translate(-0.5F, -0.03F, -0.5F);
			blockRenderManager.renderBlockAsEntity(rail, matrices, vertexConsumers, light, OverlayTexture.DEFAULT_UV);
			matrices.pop();
		}
		matrices.pop();
	}

	private void renderSpiderSuspensionCables(RingVehicleEntity entity, float tickDelta, int ringLevel,
			float suspensionOffset, Quaternionf bodyTilt, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		double bodyRadius = ringLevel > 0 ? 2.5D : 1.5D;
		for (int index = 0; index < 8; index++) {
			double angle = Math.PI * 2.0D * index / 8.0D + Math.PI / 8.0D;
			Vec3d radial = new Vec3d(Math.cos(angle), 0.0D, Math.sin(angle));
			Vec3d baseEnd = entity == null
					? radial.multiply(bodyRadius * RingVehicleEntity.SPIDER_LEG_ROOT_RADIUS_RATIO)
					: entity.getSpiderLegRootOffset(index);
			Vec3d horizontalEnd = new Vec3d(baseEnd.x, 0.0D, baseEnd.z);
			if (horizontalEnd.lengthSquared() > 0.01D) {
				radial = horizontalEnd.normalize();
			}
			Vec3d end = rotate(bodyTilt, baseEnd);
			double cartEdgeRadius = spiderMinecartCableRadius(radial, suspensionOffset);
			Vec3d start = rotate(bodyTilt,
					radial.multiply(cartEdgeRadius).add(0.0D, suspensionOffset + 0.12D, 0.0D));
			double directLength = start.distanceTo(end);
			double sag = directLength * 0.17D + Math.max(0.0D, -suspensionOffset) * 0.22D;
			Vec3d control = start.add(end).multiply(0.5D).add(0.0D, -sag, 0.0D);
			Vec3d previous = start;
			for (int section = 1; section <= 5; section++) {
				double t = section / 5.0D;
				Vec3d point = quadraticBezier(start, control, end, t);
				renderSpiderCableSegment(previous, point, matrices, vertexConsumers, light);
				previous = point;
			}
		}
	}

	private double spiderMinecartCableRadius(Vec3d radial, float suspensionOffset) {
		double rimHeight = MathHelper.clamp((suspensionOffset + 0.19D) / 0.275D, 0.0D, 1.0D);
		// renderMinecart rotates the vanilla hull by 90 degrees, so its longer rim lies on local Z.
		double halfX = MathHelper.lerp(rimHeight, 0.43D, 0.52D);
		double halfZ = MathHelper.lerp(rimHeight, 0.54D, 0.68D);
		double x = Math.abs(radial.x) / halfX;
		double z = Math.abs(radial.z) / halfZ;
		double superellipse = Math.pow(Math.pow(x, 4.0D) + Math.pow(z, 4.0D), 0.25D);
		return superellipse < 1.0E-4D ? Math.min(halfX, halfZ) : 0.94D / superellipse;
	}

	private Quaternionf spiderBodyTilt(RingVehicleEntity entity, float tickDelta) {
		if (entity == null) {
			return new Quaternionf();
		}
		Vec3d localNormal = entity.getSpiderBodyNormal(tickDelta)
				.rotateY((float) Math.toRadians(entity.getVisualBodyYaw(tickDelta)));
		if (localNormal.lengthSquared() < 0.5D) {
			return new Quaternionf();
		}
		return new Quaternionf().rotationTo(new Vector3f(0.0F, 1.0F, 0.0F),
				new Vector3f((float) localNormal.x, (float) localNormal.y, (float) localNormal.z).normalize());
	}

	private Vec3d rotate(Quaternionf rotation, Vec3d vector) {
		Vector3f transformed = rotation.transform(
				new Vector3f((float) vector.x, (float) vector.y, (float) vector.z));
		return new Vec3d(transformed.x(), transformed.y(), transformed.z());
	}

	private Vec3d quadraticBezier(Vec3d start, Vec3d control, Vec3d end, double t) {
		double inverse = 1.0D - t;
		return start.multiply(inverse * inverse)
				.add(control.multiply(2.0D * inverse * t))
				.add(end.multiply(t * t));
	}

	private void renderSpiderCableSegment(Vec3d start, Vec3d end, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		Vec3d delta = end.subtract(start);
		double length = Math.max(0.01D, delta.length());
		Vec3d midpoint = start.add(end).multiply(0.5D);
		Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0.0F, 0.0F, 1.0F),
				new Vector3f((float) (delta.x / length), (float) (delta.y / length), (float) (delta.z / length)));
		matrices.push();
		matrices.translate((float) midpoint.x, (float) midpoint.y, (float) midpoint.z);
		matrices.multiply(rotation);
		matrices.scale(0.055F, 0.055F, (float) length + 0.025F);
		matrices.translate(-0.5F, -0.5F, -0.5F);
		blockRenderManager.renderBlockAsEntity(Blocks.IRON_BLOCK.getDefaultState(), matrices,
				vertexConsumers, light, OverlayTexture.DEFAULT_UV);
		matrices.pop();
	}

	private void renderSpiderLegs(RingVehicleEntity entity, float tickDelta, RingVehicleVariant variant, boolean lavaProof, int ringLevel,
			Quaternionf bodyTilt, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		if (entity == null || entity.getSpiderRenderedLegMask() == 0) {
			return;
		}
		BlockState legState = variant.powered()
				? Blocks.POWERED_RAIL.getDefaultState().with(PoweredRailBlock.POWERED, true)
				: Blocks.RAIL.getDefaultState();
		BlockState frameState = Blocks.OBSIDIAN.getDefaultState();
		BlockState jointState = Blocks.IRON_BLOCK.getDefaultState();
		for (int index = 0; index < RingVehicleEntity.SPIDER_LEG_COUNT; index++) {
			if (!entity.hasSpiderLegVisual(index)) {
				continue;
			}
			float deployProgress = entity.getSpiderLegRenderProgress(index);
			if (deployProgress <= 0.01F) {
				continue;
			}
			double deploy = deployProgress * deployProgress * (3.0D - 2.0D * deployProgress);
			double angle = Math.PI * 2.0D * index / 8.0D + Math.PI / 8.0D;
			Vec3d fallbackRadial = new Vec3d(Math.cos(angle), 0.0D, Math.sin(angle));
			double bodyRadius = ringLevel > 0 ? 2.5D : 1.5D;
			Vec3d baseRoot = entity == null
					? fallbackRadial.multiply(bodyRadius * RingVehicleEntity.SPIDER_LEG_ROOT_RADIUS_RATIO)
					: entity.getSpiderLegRootOffset(index);
			Vec3d localRadial = baseRoot.lengthSquared() < 0.01D ? fallbackRadial : baseRoot.normalize();
			Vec3d root = rotate(bodyTilt, baseRoot);
			Vec3d extendedFoot = entity == null
					? localRadial.multiply(RingVehicleEntity.spiderLegReach(ringLevel))
							.add(0.0D, -bodyRadius - RingVehicleEntity.SPIDER_BODY_LIFT, 0.0D)
					: entity.getSpiderFootOffset(index, tickDelta);
			Vec3d foot = root.lerp(extendedFoot, deploy);
			Vec3d radial = rotate(bodyTilt, localRadial).normalize();
			Vec3d bodyUp = rotate(bodyTilt, new Vec3d(0.0D, 1.0D, 0.0D)).normalize();
			boolean pendingAssembly = entity.isSpiderLegPending(index) && !entity.isSpiderAwake();
			if (pendingAssembly) {
				Vec3d looseOffset = radial.multiply(0.12D).add(bodyUp.multiply(0.05D));
				root = root.add(looseOffset);
				foot = foot.add(looseOffset);
			}
			boolean frontFlippedLeg = localRadial.z > 0.70D;
			Vec3d[] chain = solveSpiderLeg(root, foot, radial, bodyUp, ringLevel, frontFlippedLeg);
			foot = chain[chain.length - 1];
			Vec3d bodyMount = rotate(bodyTilt, localRadial.multiply(bodyRadius * 0.92D));
			if (pendingAssembly) {
				bodyMount = bodyMount.add(radial.multiply(0.04D));
			}
			if (lavaProof) {
				for (int segment = 0; segment < chain.length - 1; segment++) {
					renderSpiderRailOutlineSegment(chain[segment], chain[segment + 1], frameState,
							segment == 0 ? 0.54F : 0.47F, bodyUp, radial,
							matrices, vertexConsumers, light);
				}
			}
			renderSpiderIronConnector(bodyMount, root, 0.52F, bodyUp, radial,
					matrices, vertexConsumers, light);
			int legSkinTiles = ringLevel > 0 ? 5 : 3;
			for (int segment = 0; segment < chain.length - 1; segment++) {
				double startTrim = segment == 0 ? 0.24D : segment == 1 ? 0.215D : 0.195D;
				double endTrim = segment == 0 ? 0.215D : segment == 1 ? 0.195D : 0.17D;
				renderSpiderRailSegmentTrimmed(chain[segment], chain[segment + 1], legState,
						segment == 0 ? 0.54F : 0.47F, bodyUp, radial,
						frontFlippedLeg, legSkinTiles, startTrim, endTrim,
						matrices, vertexConsumers, light);
			}
			renderSpiderJoint(root, jointState, 0.40F, matrices, vertexConsumers, light);
			renderSpiderJoint(chain[1], jointState, 0.36F, matrices, vertexConsumers, light);
			renderSpiderJoint(chain[2], jointState, 0.33F, matrices, vertexConsumers, light);
			Vec3d footCenter = foot.add(bodyUp.multiply(0.10D));
			renderSpiderJoint(footCenter, jointState, 0.28F,
					matrices, vertexConsumers, light);
			matrices.push();
			matrices.translate((float) footCenter.x, (float) footCenter.y, (float) footCenter.z);
			matrices.multiply(segmentRotation(radial, bodyUp, new Vec3d(0.0D, 0.0D, 1.0D)));
			matrices.scale(0.52F, 0.16F, 0.68F);
			matrices.translate(-0.5F, -0.5F, -0.5F);
			blockRenderManager.renderBlockAsEntity(Blocks.IRON_BLOCK.getDefaultState(), matrices,
					vertexConsumers, light, OverlayTexture.DEFAULT_UV);
			matrices.pop();
		}
	}

	private void renderSpiderIronConnector(Vec3d start, Vec3d end, float width,
			Vec3d preferredUp, Vec3d fallbackUp, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		Vec3d delta = end.subtract(start);
		double length = Math.max(0.05D, delta.length());
		Vec3d midpoint = start.add(end).multiply(0.5D);
		Quaternionf rotation = segmentRotation(delta, preferredUp, fallbackUp);
		matrices.push();
		matrices.translate((float) midpoint.x, (float) midpoint.y, (float) midpoint.z);
		matrices.multiply(rotation);
		matrices.scale(width, Math.max(0.30F, width * 0.68F), (float) length + 0.12F);
		matrices.translate(-0.5F, -0.5F, -0.5F);
		blockRenderManager.renderBlockAsEntity(Blocks.IRON_BLOCK.getDefaultState(), matrices,
				vertexConsumers, light, OverlayTexture.DEFAULT_UV);
		matrices.pop();
	}

	/** Builds a monotonic outward chain so neither knee can fold back through the body. */
	private Vec3d[] solveSpiderLeg(Vec3d root, Vec3d foot, Vec3d radial, Vec3d bodyUp,
			int ringLevel, boolean probeLeg) {
		double nominalReach = RingVehicleEntity.spiderLegReach(ringLevel);
		double maximumReach = RingVehicleEntity.spiderLegMaximumReach(ringLevel);
		Vec3d safeUp = bodyUp.lengthSquared() < 0.5D ? new Vec3d(0.0D, 1.0D, 0.0D) : bodyUp.normalize();
		Vec3d nominalRadial = radial.subtract(safeUp.multiply(radial.dotProduct(safeUp)));
		nominalRadial = nominalRadial.lengthSquared() < 0.01D
				? new Vec3d(1.0D, 0.0D, 0.0D)
				: nominalRadial.normalize();
		Vec3d rootToFoot = foot.subtract(root);
		double verticalDistance = rootToFoot.dotProduct(safeUp);
		Vec3d planarOffset = rootToFoot.subtract(safeUp.multiply(verticalDistance));
		double planarDistance = planarOffset.length();
		Vec3d footDirection = planarDistance < 0.01D ? nominalRadial : planarOffset.normalize();
		double radialAgreement = footDirection.dotProduct(nominalRadial);
		if (radialAgreement < 0.20D) {
			Vec3d lateral = footDirection.subtract(nominalRadial.multiply(radialAgreement));
			footDirection = lateral.lengthSquared() < 0.01D
					? nominalRadial
					: nominalRadial.multiply(0.72D).add(lateral.normalize().multiply(0.28D)).normalize();
			planarDistance = Math.max(planarDistance, 0.72D);
			foot = root.add(footDirection.multiply(planarDistance)).add(safeUp.multiply(verticalDistance));
		}
		Vec3d constrainedOffset = foot.subtract(root);
		if (constrainedOffset.length() > maximumReach * 0.98D) {
			foot = root.add(constrainedOffset.normalize().multiply(maximumReach * 0.98D));
			constrainedOffset = foot.subtract(root);
		}
		verticalDistance = constrainedOffset.dotProduct(safeUp);
		planarOffset = constrainedOffset.subtract(safeUp.multiply(verticalDistance));
		planarDistance = Math.max(0.55D, planarOffset.length());
		footDirection = planarOffset.lengthSquared() < 0.01D ? footDirection : planarOffset.normalize();
		foot = root.add(footDirection.multiply(planarDistance)).add(safeUp.multiply(verticalDistance));
		double extension = MathHelper.clamp(constrainedOffset.length() / (nominalReach * 0.98D), 0.0D, 1.0D);
		double foldReserve = nominalReach * (probeLeg ? 0.16D : 0.18D)
				* (1.0D - extension);
		double archHeight = Math.min(nominalReach * 0.25D,
				Math.max(nominalReach * (probeLeg ? 0.14D : 0.16D),
						planarDistance * 0.24D + foldReserve));
		double firstHeight = Math.max(nominalReach * (probeLeg ? 0.15D : 0.17D),
				verticalDistance * 0.20D + archHeight * 1.05D);
		double secondHeight = Math.max(nominalReach * (probeLeg ? 0.065D : 0.070D),
				verticalDistance * 0.58D + archHeight * 0.62D);
		Vec3d first = root.add(footDirection.multiply(planarDistance * 0.34D))
				.add(safeUp.multiply(firstHeight));
		Vec3d second = root.add(footDirection.multiply(planarDistance * 0.72D))
				.add(safeUp.multiply(secondHeight));
		return new Vec3d[]{root, first, second, foot};
	}

	private void renderSpiderRailSegmentTrimmed(Vec3d start, Vec3d end, BlockState state, float thickness,
			Vec3d preferredUp, Vec3d fallbackUp, boolean invertSkin, int skinTiles,
			double startTrim, double endTrim,
			MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		Vec3d delta = end.subtract(start);
		double originalLength = Math.max(0.05D, delta.length());
		Vec3d direction = delta.lengthSquared() < 1.0E-6D
				? new Vec3d(0.0D, 0.0D, 1.0D)
				: delta.normalize();
		double trimScale = startTrim + endTrim > originalLength * 0.72D
				? originalLength * 0.72D / Math.max(0.001D, startTrim + endTrim)
				: 1.0D;
		Vec3d trimmedStart = start.add(direction.multiply(startTrim * trimScale));
		Vec3d trimmedEnd = end.subtract(direction.multiply(endTrim * trimScale));
		delta = trimmedEnd.subtract(trimmedStart);
		double length = Math.max(0.05D, delta.length());
		Vec3d midpoint = trimmedStart.add(trimmedEnd).multiply(0.5D);
		Quaternionf rotation = segmentRotation(delta, preferredUp, fallbackUp);
		float beamDepth = Math.max(0.28F, thickness * 0.62F);
		float coreWidth = thickness * 0.78F;

		// A solid core gives the rail leg real side faces instead of leaving it as one flat quad.
		matrices.push();
		matrices.translate((float) midpoint.x, (float) midpoint.y, (float) midpoint.z);
		matrices.multiply(rotation);
		matrices.scale(coreWidth, beamDepth, (float) length + 0.10F);
		matrices.translate(-0.5F, -0.5F, -0.5F);
		blockRenderManager.renderBlockAsEntity(Blocks.IRON_BLOCK.getDefaultState(), matrices,
				vertexConsumers, light, OverlayTexture.DEFAULT_UV);
		matrices.pop();

		renderSpiderRailSkin(trimmedStart, trimmedEnd, state, thickness, beamDepth * 0.5F,
				invertSkin ? preferredUp.negate() : preferredUp, fallbackUp, skinTiles,
				matrices, vertexConsumers, light);
	}

	private void renderSpiderRailSkin(Vec3d start, Vec3d end, BlockState state,
			float width, float verticalOffset, Vec3d preferredUp, Vec3d fallbackUp, int requestedTiles,
			MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		Vec3d delta = end.subtract(start);
		double length = Math.max(0.05D, delta.length());
		Quaternionf rotation = segmentRotation(delta, preferredUp, fallbackUp);
		int tiles = MathHelper.clamp(requestedTiles, 1, 6);
		float tileLength = Math.max(0.04F, (float) (length / tiles) - 0.012F);
		for (int tile = 0; tile < tiles; tile++) {
			double from = (double) tile / tiles;
			double to = (double) (tile + 1) / tiles;
			Vec3d midpoint = start.lerp(end, (from + to) * 0.5D);
			matrices.push();
			matrices.translate((float) midpoint.x, (float) midpoint.y, (float) midpoint.z);
			matrices.multiply(rotation);
			matrices.translate(0.0F, verticalOffset + 0.018F, 0.0F);
			matrices.scale(width, 1.0F, tileLength);
			matrices.translate(-0.5F, -0.03F, -0.5F);
			blockRenderManager.renderBlockAsEntity(state, matrices, vertexConsumers, light, OverlayTexture.DEFAULT_UV);
			matrices.pop();
		}
	}

	private Quaternionf segmentRotation(Vec3d direction, Vec3d preferredUp, Vec3d fallbackUp) {
		Vec3d zAxis = direction.lengthSquared() < 1.0E-6D
				? new Vec3d(0.0D, 0.0D, 1.0D)
				: direction.normalize();
		Vec3d yAxis = preferredUp.subtract(zAxis.multiply(preferredUp.dotProduct(zAxis)));
		if (yAxis.lengthSquared() < 1.0E-4D) {
			yAxis = fallbackUp.subtract(zAxis.multiply(fallbackUp.dotProduct(zAxis)));
		}
		if (yAxis.lengthSquared() < 1.0E-4D) {
			yAxis = Math.abs(zAxis.y) < 0.90D ? new Vec3d(0.0D, 1.0D, 0.0D) : new Vec3d(1.0D, 0.0D, 0.0D);
			yAxis = yAxis.subtract(zAxis.multiply(yAxis.dotProduct(zAxis)));
		}
		yAxis = yAxis.normalize();
		Vec3d xAxis = yAxis.crossProduct(zAxis).normalize();
		yAxis = zAxis.crossProduct(xAxis).normalize();
		Matrix3f basis = new Matrix3f();
		basis.setColumn(0, (float) xAxis.x, (float) xAxis.y, (float) xAxis.z);
		basis.setColumn(1, (float) yAxis.x, (float) yAxis.y, (float) yAxis.z);
		basis.setColumn(2, (float) zAxis.x, (float) zAxis.y, (float) zAxis.z);
		return basis.getNormalizedRotation(new Quaternionf());
	}

	private void renderSpiderJoint(Vec3d position, BlockState state, float size,
			MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		matrices.push();
		matrices.translate((float) position.x, (float) position.y, (float) position.z);
		matrices.scale(size, size, size);
		matrices.translate(-0.5F, -0.5F, -0.5F);
		blockRenderManager.renderBlockAsEntity(state, matrices, vertexConsumers, light, OverlayTexture.DEFAULT_UV);
		matrices.pop();
	}

	private void renderSpiderRailOutlineSegment(Vec3d start, Vec3d end, BlockState state, float railWidth,
			Vec3d preferredUp, Vec3d fallbackUp,
			MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		Vec3d delta = end.subtract(start);
		double length = Math.max(0.05D, delta.length());
		if (length <= 0.86D) {
			return;
		}
		Vec3d direction = delta.normalize();
		// Keep the reinforcement out of the iron joint volume at both ends.
		double trim = Math.min(Math.max(0.43D, railWidth * 0.72D), length * 0.42D);
		Vec3d trimmedStart = start.add(direction.multiply(trim));
		Vec3d trimmedEnd = end.subtract(direction.multiply(trim));
		length = Math.max(0.04D, trimmedStart.distanceTo(trimmedEnd));
		Vec3d midpoint = trimmedStart.add(trimmedEnd).multiply(0.5D);
		Quaternionf rotation = segmentRotation(trimmedEnd.subtract(trimmedStart), preferredUp, fallbackUp);
		float edgeWidth = 0.09F;
		float edgeOffset = railWidth * 0.5F + edgeWidth * 0.18F;
		float beamDepth = Math.max(0.28F, railWidth * 0.62F) + 0.045F;
		for (int side = -1; side <= 1; side += 2) {
			matrices.push();
			matrices.translate((float) midpoint.x, (float) midpoint.y, (float) midpoint.z);
			matrices.multiply(rotation);
			matrices.translate(edgeOffset * side, 0.0F, 0.0F);
			matrices.scale(edgeWidth, beamDepth, (float) length + 0.025F);
			matrices.translate(-0.5F, -0.5F, -0.5F);
			blockRenderManager.renderBlockAsEntity(state, matrices, vertexConsumers, light, OverlayTexture.DEFAULT_UV);
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
