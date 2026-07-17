package com.xc.echominecart.client;

import com.xc.echominecart.rail.OmniRailBlock;
import com.xc.echominecart.rail.RailPhysics;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.RailShape;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端车厢视觉。
 *
 * 拉伸：服务端通过 CarriageSyncPayload 下发车厢覆盖格，这里只做查表 + 过期清理，
 * 不再自行扫描推断。
 *
 * 贴附姿态的旋转复合（核心）：原版渲染先做 Ry(180−yaw)，直接在其后叠加固定
 * 贴面旋转会让 yaw 作用在错误的轴上（侧翻的来源）。正确顺序是：
 *   撤销 Ry(180−yaw) → Q_face（世界系里把模型 +Y 转到贴附面法线）→ Ry(θ 面内)
 * θ 由速度在该面二维基底 (u,v) 上的分量决定，基底与 Q_face 严格对应：
 *   Q(SOUTH)=Rx(+90): 模型X→世界X, 模型Z→世界−Y ⇒ (u,v)=(vx,−vy)
 *   Q(NORTH)=Rx(−90): 模型X→世界X, 模型Z→世界+Y ⇒ (u,v)=(vx, vy)
 *   Q(EAST) =Rz(−90): 模型X→世界−Y, 模型Z→世界Z ⇒ (u,v)=(−vy, vz)
 *   Q(WEST) =Rz(+90): 模型X→世界+Y, 模型Z→世界Z ⇒ (u,v)=( vy, vz)
 *   Q(DOWN) =Rx(180): 模型X→世界X, 模型Z→世界−Z ⇒ (u,v)=(vx,−vz)
 */
public final class CarriageClientVisuals {
	private static final int SHAPE_EXPIRY_TICKS = 15;
	private static final RenderScale NORMAL = new RenderScale(1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F);
	private static final Map<Integer, SyncedShape> SHAPES = new HashMap<>();

	private CarriageClientVisuals() {
	}

	// ------------------------------------------------------------ sync store

	public static void updateShape(int anchorId, long anchorCell, List<Long> cells, int chestCount, long clientTime) {
		SHAPES.put(anchorId, new SyncedShape(anchorCell, cells, chestCount, clientTime + SHAPE_EXPIRY_TICKS));
	}

	public static void clearShapes() {
		SHAPES.clear();
	}

	public static boolean shouldSkipRender(AbstractMinecartEntity cart) {
		return cart.isInvisible();
	}

	/** 拉伸缩放：世界轴跨度映射到模型局部轴（模型长轴经 Ry(180−yaw) 后落在哪个世界轴由 yaw 决定）。 */
	public static RenderScale renderScale(AbstractMinecartEntity cart, float yaw) {
		SyncedShape shape = shapeFor(cart);
		if (shape == null) {
			return NORMAL;
		}
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (long packed : shape.cells()) {
			BlockPos cell = BlockPos.fromLong(packed);
			minX = Math.min(minX, cell.getX());
			maxX = Math.max(maxX, cell.getX());
			minZ = Math.min(minZ, cell.getZ());
			maxZ = Math.max(maxZ, cell.getZ());
		}
		if (minX > maxX) {
			return NORMAL;
		}
		float spanX = maxX - minX + 1.0F;
		float spanZ = maxZ - minZ + 1.0F;
		if (spanX <= 1.0F && spanZ <= 1.0F) {
			return NORMAL;
		}
		double yawRad = Math.toRadians(yaw);
		double centerX = (minX + maxX + 1.0D) * 0.5D;
		double centerZ = (minZ + maxZ + 1.0D) * 0.5D;
		BlockPos anchor = BlockPos.fromLong(shape.anchorCell());
		double dx = centerX - (anchor.getX() + 0.5D);
		double dz = centerZ - (anchor.getZ() + 0.5D);
		float localOffsetX = (float) (dx * Math.cos(yawRad) - dz * Math.sin(yawRad));
		float localOffsetZ = (float) (dx * Math.sin(yawRad) + dz * Math.cos(yawRad));
		float localX = clampScale(Math.max(spanX, spanZ));
		float localZ = clampScale(Math.min(spanX, spanZ));
		return new RenderScale(localX, 1.0F, localZ, localOffsetX, 0.0F, localOffsetZ);
	}

	public static List<RenderChest> chestOffsets(AbstractMinecartEntity cart, float yaw) {
		SyncedShape shape = shapeFor(cart);
		if (shape == null || shape.chestCount() <= 0 || shape.cells().isEmpty()) {
			return List.of();
		}
		Direction face = attachedFace(cart);
		BlockPos anchor = BlockPos.fromLong(shape.anchorCell());
		double theta = Math.toRadians(face == Direction.UP ? yaw : planeYawDegrees(cart, face));
		double cos = Math.cos(theta);
		double sin = Math.sin(theta);
		Vec3d normal = Vec3d.of(face.getVector());
		int wanted = Math.min(shape.chestCount(), shape.cells().size());
		List<RenderChest> chests = new ArrayList<>(wanted);
		for (int i = 0; i < wanted; i++) {
			BlockPos seat = BlockPos.fromLong(shape.cells().get(shape.cells().size() - 1 - i));
			Vec3d relative = new Vec3d(
					seat.getX() - anchor.getX(),
					seat.getY() - anchor.getY(),
					seat.getZ() - anchor.getZ());
			double u = planeU(relative, face);
			double v = planeV(relative, face);
			float localX = (float) (u * cos - v * sin);
			float localZ = (float) (u * sin + v * cos);
			float localY = (float) relative.dotProduct(normal) + 0.36F;
			chests.add(new RenderChest(localX, localY, localZ));
		}
		return chests;
	}

	private static SyncedShape shapeFor(AbstractMinecartEntity cart) {
		SyncedShape shape = SHAPES.get(cart.getId());
		if (shape == null) {
			return null;
		}
		if (cart.getWorld().getTime() > shape.expiryTick()) {
			SHAPES.remove(cart.getId());
			return null;
		}
		return shape;
	}

	// -------------------------------------------------------- attached pose

	public static Direction attachedFace(AbstractMinecartEntity cart) {
		return RailPhysics.findContact(cart.getWorld(), cart)
				.map(RailPhysics.RailContact::face)
				.orElse(Direction.UP);
	}

	/** 世界系贴面旋转：把模型 +Y 转到 face 法线方向。 */
	public static Quaternionf faceRotation(Direction face) {
		return switch (face) {
			case DOWN -> RotationAxis.POSITIVE_X.rotationDegrees(180.0F);
			case SOUTH -> RotationAxis.POSITIVE_X.rotationDegrees(90.0F);
			case NORTH -> RotationAxis.POSITIVE_X.rotationDegrees(-90.0F);
			case EAST -> RotationAxis.POSITIVE_Z.rotationDegrees(-90.0F);
			case WEST -> RotationAxis.POSITIVE_Z.rotationDegrees(90.0F);
			default -> new Quaternionf();
		};
	}

	public static Quaternionf attachedRotation(AbstractMinecartEntity cart, Direction face) {
		BlockState occupied = cart.getWorld().getBlockState(cart.getBlockPos());
		Vec3d velocity = cart.getVelocity();
		if (OmniRailBlock.isOmniRail(occupied)
				&& OmniRailBlock.face(occupied) == Direction.DOWN
				&& occupied.get(OmniRailBlock.MANUAL)
				&& RailPhysics.ascendingDirection(occupied) == null
				&& velocity.horizontalLengthSquared() > velocity.y * velocity.y) {
			return faceRotation(Direction.DOWN);
		}
		var contact = RailPhysics.findContact(cart.getWorld(), cart);
		if (contact.isEmpty()) {
			return faceRotation(face);
		}
		Quaternionf lateral = lateralTransitionRotation(cart, contact.get());
		return lateral != null ? lateral : transitionRotation(contact.get().state(), face);
	}

	private static Quaternionf lateralTransitionRotation(AbstractMinecartEntity cart, RailPhysics.RailContact contact) {
		BlockState state = contact.state();
		var link = OmniRailBlock.findLateralLink(cart.getWorld(), contact.pos(), state);
		if (link == null) {
			return null;
		}
		Vec3d from = RailPhysics.surfacePoint(contact.pos(), contact.face());
		Vec3d to = RailPhysics.surfacePoint(link.pos(), link.face());
		double fromDistance = cart.getPos().distanceTo(from);
		double toDistance = cart.getPos().distanceTo(to);
		float progress = (float) (fromDistance / Math.max(1.0E-5D, fromDistance + toDistance));
		progress = progress * progress * (3.0F - 2.0F * progress);
		Quaternionf start = faceRotation(contact.face());
		Quaternionf end = faceRotation(link.face());
		if (start.dot(end) < 0.0F) {
			end.set(-end.x, -end.y, -end.z, -end.w);
		}
		return start.slerp(end, progress).normalize();
	}

	public static float floorSlopePitchDegrees(AbstractMinecartEntity cart) {
		return RailPhysics.findContact(cart.getWorld(), cart)
				.filter(contact -> contact.face() == Direction.UP)
				.map(contact -> slopePitchDegrees(cart, contact.state()))
				.orElse(0.0F);
	}

	private static float slopePitchDegrees(AbstractMinecartEntity cart, BlockState state) {
		if (!state.contains(OmniRailBlock.SHAPE)) {
			return 0.0F;
		}
		Direction ascending = switch (state.get(OmniRailBlock.SHAPE)) {
			case ASCENDING_NORTH -> Direction.NORTH;
			case ASCENDING_SOUTH -> Direction.SOUTH;
			case ASCENDING_EAST -> Direction.EAST;
			case ASCENDING_WEST -> Direction.WEST;
			default -> null;
		};
		if (ascending == null) {
			return 0.0F;
		}
		double dot = cart.getVelocity().dotProduct(Vec3d.of(ascending.getVector()));
		double sign = Math.abs(dot) < 1.0E-4D ? 1.0D : Math.signum(dot);
		return (float) (-35.0D * sign);
	}

	private static Quaternionf transitionRotation(BlockState state, Direction face) {
		if (!state.contains(OmniRailBlock.SHAPE)) {
			return faceRotation(face);
		}
		if (face == Direction.DOWN) {
			Direction ascending = RailPhysics.ascendingDirection(state);
			return ascending == null ? faceRotation(face) : ceilingSlopeRotation(ascending);
		}
		if (face.getAxis().isVertical()) {
			return faceRotation(face);
		}
		RailShape shape = state.get(OmniRailBlock.SHAPE);
		if (shape != RailShape.ASCENDING_SOUTH && shape != RailShape.ASCENDING_NORTH) {
			return faceRotation(face);
		}
		float full = switch (face) {
			case SOUTH -> 90.0F;
			case NORTH -> -90.0F;
			case EAST -> -90.0F;
			case WEST -> 90.0F;
			default -> 0.0F;
		};
		float angle = shape == RailShape.ASCENDING_SOUTH ? full * 0.5F : full * 1.5F;
		return switch (face) {
			case EAST, WEST -> RotationAxis.POSITIVE_Z.rotationDegrees(angle);
			default -> RotationAxis.POSITIVE_X.rotationDegrees(angle);
		};
	}

	private static Quaternionf ceilingSlopeRotation(Direction ascending) {
		return switch (ascending) {
			case NORTH -> RotationAxis.POSITIVE_X.rotationDegrees(-135.0F);
			case SOUTH -> RotationAxis.POSITIVE_X.rotationDegrees(135.0F);
			case EAST -> {
				Quaternionf rotation = RotationAxis.POSITIVE_Z.rotationDegrees(45.0F);
				yield rotation.mul(RotationAxis.POSITIVE_X.rotationDegrees(180.0F));
			}
			case WEST -> {
				Quaternionf rotation = RotationAxis.POSITIVE_Z.rotationDegrees(-45.0F);
				yield rotation.mul(RotationAxis.POSITIVE_X.rotationDegrees(180.0F));
			}
			default -> faceRotation(Direction.DOWN);
		};
	}

	/**
	 * 面内朝向角：让模型长轴（X）对齐面内行进方向。
	 * Ry(θ) 把模型 X 映射到 (cosθ, 0, −sinθ)，要它等于 (u,v) ⇒ θ = atan2(−v, u)。
	 * 静止时回退到脚下轨道的连接方向。
	 */
	public static float planeYawDegrees(AbstractMinecartEntity cart, Direction face) {
		Vec3d velocity = cart.getVelocity();
		double u = planeU(velocity, face);
		double v = planeV(velocity, face);
		if (u * u + v * v < 1.0E-6D) {
			Vec3d fallback = railTangentFallback(cart, face);
			u = planeU(fallback, face);
			v = planeV(fallback, face);
			if (u * u + v * v < 1.0E-6D) {
				return 0.0F;
			}
		}
		return (float) Math.toDegrees(Math.atan2(-v, u));
	}

	private static Vec3d railTangentFallback(AbstractMinecartEntity cart, Direction face) {
		return RailPhysics.findContact(cart.getWorld(), cart)
				.map(contact -> {
					List<Direction> connections = OmniRailBlock.connections(contact.state());
					Direction direction = preferredTangent(contact.state(), face, connections);
					return direction == null ? Vec3d.ZERO : Vec3d.of(direction.getVector());
				})
				.orElse(Vec3d.ZERO);
	}

	private static Direction preferredTangent(BlockState state, Direction face, List<Direction> connections) {
		if (connections.isEmpty()) {
			return null;
		}
		if ((face == Direction.UP || face == Direction.DOWN) && state.contains(OmniRailBlock.SHAPE)) {
			return switch (state.get(OmniRailBlock.SHAPE)) {
				case EAST_WEST, ASCENDING_EAST -> Direction.EAST;
				case ASCENDING_WEST -> Direction.WEST;
				case ASCENDING_NORTH -> Direction.NORTH;
				case ASCENDING_SOUTH -> Direction.SOUTH;
				case NORTH_EAST, SOUTH_EAST -> Direction.EAST;
				case NORTH_WEST, SOUTH_WEST -> Direction.WEST;
				default -> Direction.SOUTH;
			};
		}
		return connections.stream()
				.filter(connection -> connection.getAxis() != face.getAxis())
				.filter(connection -> !connection.getAxis().isVertical())
				.findFirst()
				.orElseGet(() -> connections.contains(Direction.UP) ? Direction.UP : connections.getFirst());
	}

	private static double planeU(Vec3d velocity, Direction face) {
		return switch (face) {
			case EAST -> -velocity.y;
			case WEST -> velocity.y;
			default -> velocity.x;
		};
	}

	private static double planeV(Vec3d velocity, Direction face) {
		return switch (face) {
			case SOUTH -> -velocity.y;
			case NORTH -> velocity.y;
			case EAST, WEST -> velocity.z;
			case DOWN -> -velocity.z;
			default -> velocity.z;
		};
	}

	private static float clampScale(float value) {
		return Math.max(1.0F, Math.min(8.0F, value));
	}

	public record RenderScale(float x, float y, float z, float offsetX, float offsetY, float offsetZ) {
	}

	public record RenderChest(float x, float y, float z) {
	}

	private record SyncedShape(long anchorCell, List<Long> cells, int chestCount, long expiryTick) {
	}
}
