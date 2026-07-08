package com.xc.echominecart.rail;

import com.xc.echominecart.rail.OmniRailBlock.LinkKind;
import com.xc.echominecart.rail.OmniRailBlock.RailLink;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RailPhysics {
	public static final double MAX_ATTACHED_SPEED = 0.65D;

	private static final double CONTACT_RANGE_SQ = 2.25D;
	private static final double POWERED_BOOST = 0.09D;
	private static final double FLAT_POWERED_BOOST = 0.06D;
	private static final double POWERED_SLOPE_BOOST = 0.075D;
	private static final double MIN_UPHILL_SPEED = 0.055D;
	private static final double MIN_POWERED_UPHILL_SPEED = 0.095D;
	private static final double MIN_TRAVEL_SPEED = 0.01D;
	private static final double CORNER_EXIT_SPEED = 0.34D;
	private static final double ENTRY_EDGE_OFFSET = 0.45D;
	private static final double WALL_TO_FLOOR_EDGE = 0.36D;
	private static final long TRANSITION_LOCK_TICKS = 10L;
	private static final double CEILING_RIDE_Y = 0.38D;
	private static final double CEILING_SLOPE_BODY_OFFSET = 0.52D;
	private static final double WALL_HORIZONTAL_RIDE_Y = 0.38D;
	private static final double WALL_LOW = 0.30D;
	private static final double WALL_HIGH = 0.70D;
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	private static final Set<UUID> CONTROLLED = new HashSet<>();
	private static final Map<UUID, ForcedContact> TRANSITION_LOCKS = new HashMap<>();
	private static final Map<UUID, Long> DETACHED_UNTIL = new HashMap<>();

	private RailPhysics() {
	}

	public static boolean isControlled(AbstractMinecartEntity cart) {
		return CONTROLLED.contains(cart.getUuid());
	}

	public static void forgetCarts(Set<UUID> liveCartIds) {
		CONTROLLED.retainAll(liveCartIds);
		TRANSITION_LOCKS.keySet().retainAll(liveCartIds);
		DETACHED_UNTIL.keySet().retainAll(liveCartIds);
	}

	public static void beforeCartTick(AbstractMinecartEntity cart) {
		if (!(cart.getWorld() instanceof ServerWorld world)) {
			return;
		}
		Optional<RailContact> forced = forcedContact(world, cart);
		boolean detached = isDetached(world, cart);
		Optional<RailContact> found = detached
				? forced.or(() -> findContact(world, cart, Direction.UP))
				: forced.or(() -> findContact(world, cart));
		if (found.isEmpty()) {
			CONTROLLED.remove(cart.getUuid());
			TRANSITION_LOCKS.remove(cart.getUuid());
			cart.setNoGravity(false);
			return;
		}

		RailContact contact = healContact(world, found.get());
		triggerActivator(cart, contact);
		boolean forcedFloor = forced.isPresent() && forced.get().face() == Direction.UP;

		if (tryCornerTransition(world, cart, contact)) {
			return;
		}

		if (contact.face() == Direction.UP) {
			if (forcedFloor) {
				tickForcedFloorTransition(cart, contact);
				return;
			}
			Direction ascending = ascendingDirection(contact.state());
			if (ascending != null) {
				assistGroundAscending(cart, contact, ascending);
				return;
			}
			if (OmniRailBlock.connections(contact.state()).size() > 2) {
				tickFlatJunction(cart, contact);
				return;
			}
			// 行进方向通向拐角（如正对实心墙基座）：原版物理会让矿车提前
			// 撞墙把速度清零，永远到不了拐角阈值。接近段改为自管积分。
			if (approachingCorner(world, cart, contact)) {
				tickCornerApproach(cart, contact);
				return;
			}
			CONTROLLED.remove(cart.getUuid());
			cart.setNoGravity(false);
			cart.setVelocity(applyFlatPoweredBehavior(contact, cart.getVelocity()));
			cart.velocityModified = true;
			return;
		}

		tickAttached(cart, contact);
	}

	private static boolean approachingCorner(ServerWorld world, AbstractMinecartEntity cart, RailContact contact) {
		Direction travel = travelDirection(contact.state(), cart.getVelocity());
		if (travel == null || travel.getAxis().isVertical()) {
			return false;
		}
		RailLink link = OmniRailBlock.findLink(world, contact.pos(), contact.face(), travel);
		return link != null && link.kind() != LinkKind.STRAIGHT && link.face() != Direction.UP;
	}

	private static void assistGroundAscending(AbstractMinecartEntity cart, RailContact contact, Direction ascending) {
		CONTROLLED.remove(cart.getUuid());
		cart.setNoGravity(false);
		Vec3d velocity = applySlopePoweredBehavior(contact, cart.getVelocity(), ascending);
		Vec3d uphill = Vec3d.of(ascending.getVector());
		double climb = new Vec3d(velocity.x, 0.0D, velocity.z).dotProduct(uphill);
		double minimum = OmniRailBlock.isAccelerating(contact.state()) ? MIN_POWERED_UPHILL_SPEED : MIN_UPHILL_SPEED;
		if (climb > 0.0D && climb < minimum) {
			velocity = velocity.add(uphill.multiply(minimum - climb));
		}
		velocity = new Vec3d(velocity.x, 0.0D, velocity.z);
		cart.setVelocity(clamp(velocity, MAX_ATTACHED_SPEED));
		cart.velocityModified = true;
	}

	/** 拐角接近段：无碰撞的平地积分，保住速度直到 tryCornerTransition 触发。 */
	private static void tickCornerApproach(AbstractMinecartEntity cart, RailContact contact) {
		CONTROLLED.add(cart.getUuid());
		cart.setNoGravity(true);
		Vec3d velocity = new Vec3d(cart.getVelocity().x, 0.0D, cart.getVelocity().z);
		velocity = applyFlatPoweredBehavior(contact, velocity);
		velocity = applyPassengerInput(cart, velocity, Direction.UP);
		velocity = clamp(new Vec3d(velocity.x, 0.0D, velocity.z), MAX_ATTACHED_SPEED);
		Vec3d surface = surfacePoint(contact.pos(), Direction.UP);
		Vec3d next = cart.getPos().add(velocity);
		cart.setPosition(next.x, surface.y, next.z);
		cart.setVelocity(velocity);
		cart.velocityModified = true;
	}

	private static RailContact healContact(ServerWorld world, RailContact contact) {
		BlockState healed = OmniRailBlock.withConnections(world, contact.pos(), contact.state());
		if (healed == contact.state()) {
			return contact;
		}
		world.setBlockState(contact.pos(), healed, Block.NOTIFY_ALL);
		return new RailContact(contact.pos(), healed, contact.face());
	}

	public static Direction ascendingDirection(BlockState state) {
		if (!OmniRailBlock.isOmniRail(state)) {
			return null;
		}
		return switch (state.get(OmniRailBlock.SHAPE)) {
			case ASCENDING_NORTH -> Direction.NORTH;
			case ASCENDING_SOUTH -> Direction.SOUTH;
			case ASCENDING_EAST -> Direction.EAST;
			case ASCENDING_WEST -> Direction.WEST;
			default -> null;
		};
	}

	private static void tickForcedFloorTransition(AbstractMinecartEntity cart, RailContact contact) {
		CONTROLLED.add(cart.getUuid());
		cart.setNoGravity(true);
		Vec3d velocity = projectOntoPlane(cart.getVelocity(), Direction.UP);
		Vec3d tangent = travelTangent(contact.state(), velocity);
		velocity = tangent.multiply(velocity.dotProduct(tangent));
		if (velocity.lengthSquared() < 0.0025D) {
			velocity = tangent.multiply(CORNER_EXIT_SPEED * 0.65D);
		}
		velocity = applyFlatPoweredBehavior(contact, velocity);
		velocity = clamp(new Vec3d(velocity.x, 0.0D, velocity.z), MAX_ATTACHED_SPEED);
		Vec3d surface = surfacePoint(contact.pos(), Direction.UP);
		Vec3d next = cart.getPos().add(velocity);
		cart.setPosition(next.x, surface.y + 0.16D, next.z);
		cart.setVelocity(velocity);
		cart.velocityModified = true;
	}

	private static void tickFlatJunction(AbstractMinecartEntity cart, RailContact contact) {
		CONTROLLED.add(cart.getUuid());
		cart.setNoGravity(true);
		Vec3d velocity = steerThroughJunction(contact.state(), cart.getVelocity());
		velocity = applyFlatPoweredBehavior(contact, velocity);
		velocity = new Vec3d(velocity.x, 0.0D, velocity.z);
		Vec3d next = cart.getPos().add(velocity);
		cart.setPosition(next.x, surfacePoint(contact.pos(), Direction.UP).y, next.z);
		cart.setVelocity(velocity);
		cart.velocityModified = true;
	}

	private static void tickAttached(AbstractMinecartEntity cart, RailContact contact) {
		CONTROLLED.add(cart.getUuid());
		cart.setNoGravity(true);

		Direction face = contact.face();
		boolean ceilingSlope = isCeilingSlope(contact);
		Vec3d velocity = ceilingSlope ? projectOntoPlane(cart.getVelocity(), Direction.DOWN) : projectOntoPlane(cart.getVelocity(), face);
		velocity = steerInPlaneCorner(cart, contact, velocity);
		if (velocity.lengthSquared() < 0.0025D) {
			Direction rescue = cornerRescueDirection(cart, contact);
			if (rescue != null) {
				velocity = Vec3d.of(rescue.getVector()).multiply(CORNER_EXIT_SPEED * 0.75D);
			}
		}

		Vec3d tangent = travelTangent(contact, velocity);
		velocity = projectOntoRailTangent(contact, velocity, tangent);

		if (OmniRailBlock.isAccelerating(contact.state())) {
			if (velocity.lengthSquared() > 1.0E-4D) {
				Vec3d push = velocity.dotProduct(tangent) >= 0.0D ? tangent : tangent.multiply(-1.0D);
				velocity = velocity.add(push.multiply(POWERED_BOOST));
			} else {
				Direction start = poweredStartDirection(cart, contact, tangent);
				if (start != null) {
					velocity = Vec3d.of(start.getVector()).multiply(POWERED_BOOST);
				}
			}
		} else if (OmniRailBlock.isPoweredRail(contact.state())) {
			velocity = velocity.multiply(0.45D);
		}

		velocity = applyPassengerInput(cart, velocity, face);
		tangent = travelTangent(contact, velocity);
		velocity = projectOntoRailTangent(contact, velocity, tangent);
		if (detachAtAttachedEnd(cart, contact, velocity)) {
			return;
		}
		velocity = stopAtDeadAttachedEnd(cart, contact, velocity);
		if (velocity.lengthSquared() < 0.0025D) {
			Direction rescueTravel = velocity.dotProduct(tangent) < 0.0D
					? Direction.getFacing(-tangent.x, -tangent.y, -tangent.z)
					: Direction.getFacing(tangent.x, tangent.y, tangent.z);
			if (nearRailEdge(cart, contact, rescueTravel)) {
				velocity = Vec3d.of(rescueTravel.getVector()).multiply(CORNER_EXIT_SPEED * 0.55D);
			}
		}
		velocity = clamp(velocity, MAX_ATTACHED_SPEED);

		Vec3d surface = surfacePoint(contact.pos(), face);
		Vec3d next = cart.getPos().add(velocity);
		Direction.Axis travelAxis = dominantAxis(tangent);
		double x;
		double y;
		double z;
		if (ceilingSlope) {
			surface = surfacePoint(contact.pos(), contact.state(), face, next);
			x = surface.x;
			y = surface.y;
			z = surface.z;
		} else {
			x = travelAxis == Direction.Axis.X ? next.x : surface.x;
			y = travelAxis == Direction.Axis.Y ? next.y : attachedSurfaceY(contact, travelAxis, surface);
			z = travelAxis == Direction.Axis.Z ? next.z : surface.z;
		}
		cart.setPosition(x, y, z);
		cart.setVelocity(velocity);
		cart.velocityModified = true;
	}

	private static boolean isDetached(ServerWorld world, AbstractMinecartEntity cart) {
		Long until = DETACHED_UNTIL.get(cart.getUuid());
		if (until == null) {
			return false;
		}
		if (world.getTime() <= until) {
			return true;
		}
		DETACHED_UNTIL.remove(cart.getUuid());
		return false;
	}

	private static boolean detachAtAttachedEnd(AbstractMinecartEntity cart, RailContact contact, Vec3d velocity) {
		if (contact.face() == Direction.UP) {
			return false;
		}
		Direction travel = deadEndTravel(cart, contact, velocity);
		if (travel == null) {
			travel = stalledDeadEndTravel(cart, contact, velocity);
		}
		if (travel == null) {
			return false;
		}
		Vec3d normal = Vec3d.of(contact.face().getVector());
		cart.setPosition(cart.getPos().add(normal.multiply(0.18D)));
		cart.setVelocity(velocity.add(normal.multiply(0.08D)));
		cart.velocityModified = true;
		cart.setNoGravity(false);
		CONTROLLED.remove(cart.getUuid());
		TRANSITION_LOCKS.remove(cart.getUuid());
		if (cart.getWorld() instanceof ServerWorld world) {
			DETACHED_UNTIL.put(cart.getUuid(), world.getTime() + 12L);
		}
		return true;
	}

	private static Direction deadEndTravel(AbstractMinecartEntity cart, RailContact contact, Vec3d velocity) {
		if (velocity.lengthSquared() < 0.0025D) {
			return null;
		}
		Direction travel = edgeTravelDirection(contact, velocity);
		if (travel == null) {
			return null;
		}
		if (velocity.dotProduct(Vec3d.of(travel.getVector())) > 0.035D
				&& isPastUnlinkedEdge(cart, contact, travel, velocity)) {
			return travel;
		}
		return null;
	}

	private static Direction stalledDeadEndTravel(AbstractMinecartEntity cart, RailContact contact, Vec3d velocity) {
		if (contact.face() == Direction.UP) {
			return null;
		}
		Vec3d surface = surfacePoint(contact, cart.getPos());
		Direction best = null;
		double bestAlong = contact.face() == Direction.DOWN ? 0.66D : 0.84D;
		for (Direction travel : OmniRailBlock.planeTangents(contact.face())) {
			if (OmniRailBlock.findLink(cart.getWorld(), contact.pos(), contact.face(), travel) != null) {
				continue;
			}
			Vec3d direction = Vec3d.of(travel.getVector());
			double predictedAlong = cart.getPos().subtract(surface).dotProduct(direction)
					+ Math.max(0.0D, velocity.dotProduct(direction));
			if (predictedAlong > bestAlong) {
				bestAlong = predictedAlong;
				best = travel;
			}
		}
		return best;
	}

	private static boolean isPastUnlinkedEdge(AbstractMinecartEntity cart, RailContact contact, Direction travel, Vec3d velocity) {
		if (!OmniRailBlock.planeTangents(contact.face()).contains(travel)) {
			return false;
		}
		if (OmniRailBlock.findLink(cart.getWorld(), contact.pos(), contact.face(), travel) != null) {
			return false;
		}
		Vec3d direction = Vec3d.of(travel.getVector());
		Vec3d surface = surfacePoint(contact, cart.getPos());
		double along = cart.getPos().subtract(surface).dotProduct(direction);
		double predictedAlong = along + Math.max(0.0D, velocity.dotProduct(direction));
		double edge = contact.face() == Direction.DOWN ? 0.72D : 0.86D;
		return predictedAlong > edge;
	}

	private static Direction poweredStartDirection(AbstractMinecartEntity cart, RailContact contact, Vec3d tangent) {
		Direction facing = Direction.getFacing(tangent.x, tangent.y, tangent.z);
		if (contact.face().getAxis().isHorizontal() || contact.face() == Direction.DOWN) {
			List<Direction> linked = OmniRailBlock.connections(contact.state()).stream()
					.filter(direction -> OmniRailBlock.findLink(cart.getWorld(), contact.pos(), contact.face(), direction) != null)
					.toList();
			if (linked.size() <= 1) {
				return null;
			}
			if (linked.contains(facing)) {
				return facing;
			}
			if (linked.contains(facing.getOpposite())) {
				return facing.getOpposite();
			}
			return linked.getFirst();
		}
		return facing;
	}

	private static Vec3d stopAtDeadAttachedEnd(AbstractMinecartEntity cart, RailContact contact, Vec3d velocity) {
		if (velocity.lengthSquared() < 1.0E-5D || contact.face() == Direction.UP) {
			return velocity;
		}
		Direction travel = edgeTravelDirection(contact, velocity);
		if (travel == null) {
			return velocity;
		}
		if (!OmniRailBlock.planeTangents(contact.face()).contains(travel)) {
			return velocity;
		}
		if (OmniRailBlock.findLink(cart.getWorld(), contact.pos(), contact.face(), travel) != null) {
			return velocity;
		}
		Vec3d surface = surfacePoint(contact, cart.getPos());
		double along = cart.getPos().subtract(surface).dotProduct(Vec3d.of(travel.getVector()));
		if (along > 0.90D) {
			return Vec3d.ZERO;
		}
		return velocity;
	}

	private static Direction edgeTravelDirection(RailContact contact, Vec3d velocity) {
		if (isCeilingSlope(contact)) {
			return travelDirection(contact.state(), velocity);
		}
		return Direction.getFacing(velocity.x, velocity.y, velocity.z);
	}

	private static Vec3d steerInPlaneCorner(AbstractMinecartEntity cart, RailContact contact, Vec3d velocity) {
		double speed = velocity.length();
		if (speed < MIN_TRAVEL_SPEED) {
			return velocity;
		}
		Direction travel = Direction.getFacing(velocity.x, velocity.y, velocity.z);
		List<Direction> connections = OmniRailBlock.connections(contact.state());
		if (connections.contains(travel)) {
			return velocity;
		}
		Vec3d surface = surfacePoint(contact, cart.getPos());
		double along = cart.getPos().subtract(surface).dotProduct(Vec3d.of(travel.getVector()));
		if (along < 0.0D) {
			return velocity;
		}
		Direction best = null;
		double bestScore = -10.0D;
		for (Direction connection : connections) {
			if (connection.getAxis() != travel.getAxis() && connection.getAxis() != contact.face().getAxis()) {
				double score = 1.0D;
				if (connection.getAxis().isVertical()) {
					score += Math.signum(cart.getVelocity().y) * axisSign(connection) * 0.5D;
					if (connection == Direction.UP) {
						score += 0.15D;
					}
				}
				if (OmniRailBlock.findLink(cart.getWorld(), contact.pos(), contact.face(), connection) != null) {
					score += 0.25D;
				}
				if (score > bestScore) {
					bestScore = score;
					best = connection;
				}
			}
		}
		if (best != null) {
			return Vec3d.of(best.getVector()).multiply(Math.max(speed, CORNER_EXIT_SPEED * 0.65D));
		}
		return along >= 0.45D ? Vec3d.ZERO : velocity;
	}

	private static Direction cornerRescueDirection(AbstractMinecartEntity cart, RailContact contact) {
		Vec3d surface = surfacePoint(contact, cart.getPos());
		Direction best = null;
		double bestAlong = 0.24D;
		for (Direction connection : OmniRailBlock.connections(contact.state())) {
			RailLink link = OmniRailBlock.findLink(cart.getWorld(), contact.pos(), contact.face(), connection);
			if (link == null || link.kind() == LinkKind.STRAIGHT) {
				continue;
			}
			double along = cart.getPos().subtract(surface).dotProduct(Vec3d.of(connection.getVector()));
			if (along > bestAlong) {
				bestAlong = along;
				best = connection;
			}
		}
		return best;
	}

	private static boolean nearRailEdge(AbstractMinecartEntity cart, RailContact contact, Direction travel) {
		if (OmniRailBlock.findLink(cart.getWorld(), contact.pos(), contact.face(), travel) == null) {
			return false;
		}
		Vec3d surface = surfacePoint(contact, cart.getPos());
		double along = cart.getPos().subtract(surface).dotProduct(Vec3d.of(travel.getVector()));
		return along >= 0.35D;
	}

	private static boolean tryCornerTransition(ServerWorld world, AbstractMinecartEntity cart, RailContact contact) {
		Vec3d velocity = cart.getVelocity();
		Direction travel = travelDirection(contact.state(), velocity);
		if (travel == null) {
			return false;
		}

		RailLink link = OmniRailBlock.findLink(world, contact.pos(), contact.face(), travel);
		if (contact.face().getAxis().isHorizontal() && travel == Direction.DOWN) {
			RailLink floorLink = link != null && link.face() == Direction.UP
					? link
					: findWallToFloorLink(world, contact.pos(), contact.face());
			if (floorLink != null && reachedWallToFloorEdge(cart, contact, velocity)) {
				performWallToFloor(world, cart, contact, floorLink, velocity.length());
				return true;
			}
		}
		if (link == null) {
			return false;
		}

		Vec3d surface = surfacePoint(contact, cart.getPos());
		double along = cart.getPos().subtract(surface).dotProduct(Vec3d.of(travel.getVector()));
		double predictedAlong = along + Math.max(0.0D, velocity.dotProduct(Vec3d.of(travel.getVector())));
		RailLink cornerLink = findCornerLink(world, contact.pos(), contact.face(), travel);
		if (cornerLink != null && reachedCornerThreshold(contact, travel, cornerLink, predictedAlong)) {
			link = cornerLink;
		}
		if (link.kind() == LinkKind.STRAIGHT || !reachedCornerThreshold(contact, travel, link, predictedAlong)) {
			return false;
		}

		double speed = Math.max(velocity.length(), CORNER_EXIT_SPEED);
		performCorner(world, cart, contact, link, travel, speed);
		return true;
	}

	private static RailLink findCornerLink(ServerWorld world, BlockPos pos, Direction face, Direction travel) {
		for (RailLink candidate : OmniRailBlock.linkCandidates(pos, face, travel)) {
			if (candidate.kind() == LinkKind.STRAIGHT) {
				continue;
			}
			BlockState target = world.getBlockState(candidate.pos());
			if (OmniRailBlock.isOmniRail(target) && OmniRailBlock.face(target) == candidate.face()) {
				return candidate;
			}
		}
		return null;
	}

	private static boolean reachedCornerThreshold(RailContact contact, Direction travel, RailLink link, double along) {
		if (link.kind() == LinkKind.OUTER_CORNER) {
			return along >= 0.40D;
		}
		if (link.kind() == LinkKind.INNER_CORNER) {
			// 地面轨的内角早一点触发：接近段虽然无碰撞，但入口点在半格处衔接更顺。
			return along >= (contact.face() == Direction.UP ? 0.30D : 0.35D);
		}
		if (contact.face().getAxis().isHorizontal() && travel.getAxis().isVertical()) {
			return along >= 0.40D;
		}
		return along >= 0.0D;
	}

	private static void performCorner(ServerWorld world, AbstractMinecartEntity cart, RailContact contact, RailLink link, Direction travel, double speed) {
		Direction newFace = link.face();
		Direction newTravel = cornerExitDirection(world, contact, link, travel);
		BlockState target = world.getBlockState(link.pos());
		Vec3d entry = cornerEntryPoint(link.pos(), target, newFace, newTravel);
		Vec3d center = Vec3d.ofCenter(link.pos());
		double edge = axisValue(center, newTravel.getAxis()) - entryEdgeOffset(contact.face(), newFace, newTravel) * axisSign(newTravel);
		entry = withAxisValue(entry, newTravel.getAxis(), edge);
		if (newFace == Direction.UP && contact.face().getAxis().isHorizontal() && travel == Direction.DOWN) {
			entry = new Vec3d(entry.x, surfacePoint(link.pos(), Direction.UP).y + 0.22D, entry.z);
		}

		cart.refreshPositionAfterTeleport(entry);
		cart.setVelocity(Vec3d.of(newTravel.getVector()).multiply(speed));
		cart.velocityModified = true;
		TRANSITION_LOCKS.put(cart.getUuid(), new ForcedContact(link.pos(), newFace, world.getTime() + TRANSITION_LOCK_TICKS));
		CONTROLLED.add(cart.getUuid());
		cart.setNoGravity(true);
	}

	private static RailLink findWallToFloorLink(ServerWorld world, BlockPos pos, Direction face) {
		for (RailLink candidate : OmniRailBlock.linkCandidates(pos, face, Direction.DOWN)) {
			if (candidate.face() != Direction.UP) {
				continue;
			}
			BlockState target = world.getBlockState(candidate.pos());
			if (OmniRailBlock.isOmniRail(target) && OmniRailBlock.face(target) == Direction.UP) {
				return candidate;
			}
		}
		for (BlockPos candidate : List.of(
				pos.down(),
				pos.down().offset(face),
				pos.offset(face).down(),
				pos.offset(face),
				pos.down().offset(face.getOpposite()))) {
			BlockState target = world.getBlockState(candidate);
			if (OmniRailBlock.isOmniRail(target) && OmniRailBlock.face(target) == Direction.UP) {
				return new RailLink(candidate, Direction.UP, LinkKind.INNER_CORNER);
			}
		}
		return null;
	}

	private static boolean reachedWallToFloorEdge(AbstractMinecartEntity cart, RailContact contact, Vec3d velocity) {
		Vec3d down = Vec3d.of(Direction.DOWN.getVector());
		Vec3d surface = surfacePoint(contact.pos(), contact.face());
		double along = cart.getPos().subtract(surface).dotProduct(down);
		double predictedAlong = along + Math.max(0.0D, velocity.dotProduct(down));
		return predictedAlong >= WALL_TO_FLOOR_EDGE;
	}

	private static void performWallToFloor(ServerWorld world, AbstractMinecartEntity cart, RailContact contact, RailLink floorLink, double speed) {
		BlockState target = refreshTarget(world, floorLink);
		Direction exit = floorExitDirection(target, contact.face(), cart.getVelocity());
		Vec3d surface = surfacePoint(floorLink.pos(), Direction.UP);
		Vec3d center = Vec3d.ofCenter(floorLink.pos());
		Vec3d entry = withAxisValue(surface, exit.getAxis(), axisValue(center, exit.getAxis()) - 0.38D * axisSign(exit));
		entry = new Vec3d(entry.x, surface.y + 0.22D, entry.z);
		double exitSpeed = Math.max(Math.min(speed, MAX_ATTACHED_SPEED), CORNER_EXIT_SPEED * 0.85D);
		cart.refreshPositionAfterTeleport(entry);
		cart.setVelocity(Vec3d.of(exit.getVector()).multiply(exitSpeed));
		cart.velocityModified = true;
		TRANSITION_LOCKS.put(cart.getUuid(), new ForcedContact(floorLink.pos(), Direction.UP, world.getTime() + TRANSITION_LOCK_TICKS));
		CONTROLLED.add(cart.getUuid());
		cart.setNoGravity(true);
	}

	private static Direction floorExitDirection(BlockState floorState, Direction wallFace, Vec3d oldVelocity) {
		List<Direction> connections = OmniRailBlock.connections(floorState).stream()
				.filter(direction -> !direction.getAxis().isVertical())
				.toList();
		if (connections.isEmpty()) {
			return wallFace;
		}
		Direction preferred = connections.contains(wallFace)
				? wallFace
				: (connections.contains(wallFace.getOpposite()) ? wallFace.getOpposite() : null);
		if (preferred != null) {
			return preferred;
		}
		Vec3d horizontal = new Vec3d(oldVelocity.x, 0.0D, oldVelocity.z);
		Direction best = connections.getFirst();
		double bestDot = -2.0D;
		if (horizontal.lengthSquared() > 1.0E-4D) {
			Vec3d normalized = horizontal.normalize();
			for (Direction connection : connections) {
				double dot = normalized.dotProduct(Vec3d.of(connection.getVector()));
				if (dot > bestDot) {
					bestDot = dot;
					best = connection;
				}
			}
		}
		return best;
	}

	private static double entryEdgeOffset(Direction oldFace, Direction newFace, Direction newTravel) {
		if (oldFace == Direction.DOWN && newFace.getAxis().isHorizontal() && newTravel.getAxis().isVertical()) {
			return 0.30D;
		}
		return ENTRY_EDGE_OFFSET;
	}

	private static Vec3d cornerEntryPoint(BlockPos pos, BlockState target, Direction newFace, Direction newTravel) {
		Vec3d entry = surfacePoint(pos, newFace);
		Direction ascending = ascendingDirection(target);
		if (newFace == Direction.UP && ascending != null) {
			Vec3d center = Vec3d.ofCenter(pos);
			double edge = axisValue(center, newTravel.getAxis()) - ENTRY_EDGE_OFFSET * axisSign(newTravel);
			Vec3d edgePoint = withAxisValue(entry, newTravel.getAxis(), edge);
			double along = edgePoint.subtract(surfacePoint(pos, Direction.UP)).dotProduct(Vec3d.of(ascending.getVector()));
			double lift = Math.max(0.0D, Math.min(0.95D, along + 0.5D));
			return new Vec3d(edgePoint.x, surfacePoint(pos, Direction.UP).y + lift, edgePoint.z);
		}
		if (newFace == Direction.DOWN && ascending != null && newTravel.getAxis().isHorizontal()) {
			Vec3d center = Vec3d.ofCenter(pos);
			double edge = axisValue(center, newTravel.getAxis()) - ENTRY_EDGE_OFFSET * axisSign(newTravel);
			Vec3d edgePoint = withAxisValue(entry, newTravel.getAxis(), edge);
			return surfacePoint(pos, target, Direction.DOWN, edgePoint);
		}
		return entry;
	}

	private static Direction cornerExitDirection(ServerWorld world, RailContact contact, RailLink link, Direction travel) {
		Direction fallback = link.kind() == LinkKind.INNER_CORNER
				? contact.face()
				: contact.face().getOpposite();
		BlockState target = refreshTarget(world, link);
		List<Direction> targetConnections = OmniRailBlock.connections(target);
		if (targetConnections.isEmpty()) {
			return fallback;
		}
		Direction entrance = entranceDirection(world, contact, link);
		Vec3d desired = projectOntoPlane(Vec3d.of(fallback.getVector()), link.face());
		if (desired.lengthSquared() < 1.0E-4D) {
			desired = projectOntoPlane(Vec3d.of(travel.getVector()), link.face());
		}
		Direction best = null;
		double bestDot = -2.0D;
		for (Direction connection : targetConnections) {
			if (connection == entrance && targetConnections.size() > 1) {
				continue;
			}
			double dot = desired.lengthSquared() < 1.0E-4D ? 0.0D : desired.normalize().dotProduct(Vec3d.of(connection.getVector()));
			if (connection == travel.getOpposite()) {
				dot -= 0.25D;
			}
			if (connection == fallback) {
				dot += 0.35D;
			}
			if (dot > bestDot) {
				bestDot = dot;
				best = connection;
			}
		}
		if (best != null) {
			return best;
		}
		return entrance == null ? targetConnections.getFirst() : entrance;
	}

	private static BlockState refreshTarget(ServerWorld world, RailLink link) {
		BlockState target = world.getBlockState(link.pos());
		if (!OmniRailBlock.isOmniRail(target)) {
			return target;
		}
		BlockState refreshed = OmniRailBlock.withConnections(world, link.pos(), target);
		if (refreshed != target) {
			world.setBlockState(link.pos(), refreshed, Block.NOTIFY_ALL);
			return refreshed;
		}
		return target;
	}

	private static Direction entranceDirection(ServerWorld world, RailContact from, RailLink targetLink) {
		for (Direction connection : OmniRailBlock.planeTangents(targetLink.face())) {
			RailLink reverse = OmniRailBlock.findLink(world, targetLink.pos(), targetLink.face(), connection);
			if (reverse != null && reverse.pos().equals(from.pos()) && reverse.face() == from.face()) {
				return connection;
			}
		}
		return null;
	}

	private static Optional<RailContact> forcedContact(ServerWorld world, AbstractMinecartEntity cart) {
		ForcedContact forced = TRANSITION_LOCKS.get(cart.getUuid());
		if (forced == null) {
			return Optional.empty();
		}
		if (world.getTime() > forced.untilTick()) {
			TRANSITION_LOCKS.remove(cart.getUuid());
			return Optional.empty();
		}
		BlockState state = world.getBlockState(forced.pos());
		if (!OmniRailBlock.isOmniRail(state) || OmniRailBlock.face(state) != forced.face()) {
			TRANSITION_LOCKS.remove(cart.getUuid());
			return Optional.empty();
		}
		Optional<RailContact> current = findContact(world, cart, forced.face());
		if (current.isPresent()) {
			return current;
		}
		double distance = surfacePoint(forced.pos(), state, forced.face(), cart.getPos()).squaredDistanceTo(cart.getPos());
		if (distance <= CONTACT_RANGE_SQ * 1.5D) {
			return Optional.of(new RailContact(forced.pos(), state, forced.face()));
		}
		TRANSITION_LOCKS.remove(cart.getUuid());
		return Optional.empty();
	}

	private static Vec3d applyFlatPoweredBehavior(RailContact contact, Vec3d velocity) {
		if (!OmniRailBlock.isPoweredRail(contact.state())) {
			return velocity;
		}
		if (OmniRailBlock.isAccelerating(contact.state())) {
			Vec3d horizontal = new Vec3d(velocity.x, 0.0D, velocity.z);
			if (horizontal.lengthSquared() > 1.0E-4D) {
				velocity = velocity.add(horizontal.normalize().multiply(FLAT_POWERED_BOOST));
			}
		} else {
			velocity = new Vec3d(velocity.x * 0.45D, velocity.y, velocity.z * 0.45D);
			if (velocity.horizontalLengthSquared() < 4.0E-4D) {
				velocity = new Vec3d(0.0D, velocity.y, 0.0D);
			}
		}
		return clamp(velocity, MAX_ATTACHED_SPEED);
	}

	private static Vec3d applySlopePoweredBehavior(RailContact contact, Vec3d velocity, Direction ascending) {
		if (!OmniRailBlock.isPoweredRail(contact.state())) {
			return velocity;
		}
		Vec3d uphill = Vec3d.of(ascending.getVector());
		Vec3d horizontal = new Vec3d(velocity.x, 0.0D, velocity.z);
		double climb = horizontal.dotProduct(uphill);
		if (OmniRailBlock.isAccelerating(contact.state())) {
			if (Math.abs(climb) > 1.0E-4D) {
				velocity = velocity.add(uphill.multiply(Math.signum(climb) * POWERED_SLOPE_BOOST));
			}
		} else {
			velocity = new Vec3d(velocity.x * 0.45D, velocity.y, velocity.z * 0.45D);
		}
		return clamp(velocity, MAX_ATTACHED_SPEED);
	}

	private static Vec3d steerThroughJunction(BlockState state, Vec3d velocity) {
		List<Direction> connections = OmniRailBlock.connections(state);
		if (connections.size() <= 2 || velocity.horizontalLengthSquared() < 1.0E-4D) {
			return velocity;
		}
		Vec3d horizontal = new Vec3d(velocity.x, 0.0D, velocity.z);
		Vec3d best = null;
		double bestDot = 0.0D;
		for (Direction connection : connections) {
			if (connection.getAxis().isVertical()) {
				continue;
			}
			Vec3d candidate = Vec3d.of(connection.getVector());
			double dot = horizontal.normalize().dotProduct(candidate);
			if (dot > bestDot) {
				bestDot = dot;
				best = candidate;
			}
		}
		if (best == null) {
			return velocity;
		}
		return best.multiply(horizontal.length()).add(0.0D, velocity.y, 0.0D);
	}

	private static Direction travelDirection(BlockState state, Vec3d velocity) {
		Direction best = null;
		double bestDot = MIN_TRAVEL_SPEED;
		for (Direction connection : OmniRailBlock.connections(state)) {
			double dot = velocity.dotProduct(Vec3d.of(connection.getVector()));
			if (dot > bestDot) {
				bestDot = dot;
				best = connection;
			}
		}
		return best;
	}

	private static Vec3d travelTangent(BlockState state, Vec3d velocity) {
		List<Direction> connections = OmniRailBlock.connections(state);
		if (connections.isEmpty()) {
			return new Vec3d(0.0D, 1.0D, 0.0D);
		}
		Direction best = connections.getFirst();
		double bestDot = -1.0D;
		for (Direction connection : connections) {
			double dot = Math.abs(velocity.dotProduct(Vec3d.of(connection.getVector())));
			if (dot > bestDot) {
				bestDot = dot;
				best = connection;
			}
		}
		return Vec3d.of(best.getVector());
	}

	private static Vec3d travelTangent(RailContact contact, Vec3d velocity) {
		Direction ascending = ceilingSlopeDirection(contact);
		if (ascending == null) {
			return travelTangent(contact.state(), velocity);
		}
		Vec3d uphill = Vec3d.of(ascending.getVector()).add(0.0D, 1.0D, 0.0D).normalize();
		Vec3d horizontal = new Vec3d(velocity.x, 0.0D, velocity.z);
		if (horizontal.lengthSquared() < 1.0E-5D) {
			return velocity.dotProduct(uphill) >= 0.0D ? uphill : uphill.multiply(-1.0D);
		}
		return horizontal.dotProduct(Vec3d.of(ascending.getVector())) >= 0.0D ? uphill : uphill.multiply(-1.0D);
	}

	private static Vec3d projectOntoRailTangent(RailContact contact, Vec3d velocity, Vec3d tangent) {
		Direction ascending = ceilingSlopeDirection(contact);
		if (ascending == null) {
			return tangent.multiply(velocity.dotProduct(tangent));
		}
		Vec3d horizontalDirection = new Vec3d(tangent.x, 0.0D, tangent.z);
		if (horizontalDirection.lengthSquared() < 1.0E-5D) {
			return Vec3d.ZERO;
		}
		horizontalDirection = horizontalDirection.normalize();
		Vec3d horizontal = new Vec3d(velocity.x, 0.0D, velocity.z);
		double horizontalSpeed = horizontal.dotProduct(horizontalDirection);
		if (Math.abs(horizontalSpeed) < 1.0E-5D) {
			horizontalSpeed = velocity.dotProduct(tangent);
		}
		return tangent.multiply(horizontalSpeed * Math.sqrt(2.0D));
	}

	private static Direction ceilingSlopeDirection(RailContact contact) {
		return isCeilingSlope(contact) ? ascendingDirection(contact.state()) : null;
	}

	private static boolean isCeilingSlope(RailContact contact) {
		return contact.face() == Direction.DOWN && ascendingDirection(contact.state()) != null;
	}

	private static Vec3d applyPassengerInput(AbstractMinecartEntity cart, Vec3d velocity, Direction face) {
		for (Entity passenger : cart.getPassengerList()) {
			if (!(passenger instanceof PlayerEntity player) || Math.abs(player.forwardSpeed) < 0.01F) {
				continue;
			}
			Vec3d look = projectOntoPlane(player.getRotationVec(1.0F), face);
			if (look.lengthSquared() < 1.0E-4D) {
				continue;
			}
			return velocity.add(look.normalize().multiply(0.045D * Math.signum(player.forwardSpeed)));
		}
		return velocity;
	}

	private static void triggerActivator(AbstractMinecartEntity cart, RailContact contact) {
		if (OmniRailBlock.isActivatorRail(contact.state())) {
			BlockPos pos = contact.pos();
			cart.onActivatorRail(pos.getX(), pos.getY(), pos.getZ(), contact.state().get(OmniRailBlock.POWERED));
		}
	}

	public static Vec3d surfacePoint(BlockPos pos, Direction face) {
		return switch (face) {
			case DOWN -> new Vec3d(pos.getX() + 0.5D, pos.getY() + CEILING_RIDE_Y, pos.getZ() + 0.5D);
			case NORTH -> new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + WALL_HIGH);
			case SOUTH -> new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + WALL_LOW);
			case EAST -> new Vec3d(pos.getX() + WALL_LOW, pos.getY() + 0.5D, pos.getZ() + 0.5D);
			case WEST -> new Vec3d(pos.getX() + WALL_HIGH, pos.getY() + 0.5D, pos.getZ() + 0.5D);
			default -> new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.0625D, pos.getZ() + 0.5D);
		};
	}

	public static Vec3d surfacePoint(BlockPos pos, BlockState state, Direction face, Vec3d near) {
		Direction ascending = face == Direction.DOWN ? ascendingDirection(state) : null;
		if (ascending == null) {
			return surfacePoint(pos, face);
		}
		Vec3d base = surfacePoint(pos, Direction.DOWN);
		Vec3d uphill = Vec3d.of(ascending.getVector());
		Vec3d rise = uphill.add(0.0D, 1.0D, 0.0D);
		Vec3d currentToRaised = projectOntoSegment(near, base, base.add(rise));
		Vec3d lowerToCurrent = projectOntoSegment(near, base.subtract(rise), base);
		Vec3d railCenter = near.squaredDistanceTo(currentToRaised) <= near.squaredDistanceTo(lowerToCurrent)
				? currentToRaised
				: lowerToCurrent;
		return railCenter.add(ceilingSlopeBodyNormal(ascending).multiply(CEILING_SLOPE_BODY_OFFSET));
	}

	private static Vec3d surfacePoint(RailContact contact, Vec3d near) {
		return surfacePoint(contact.pos(), contact.state(), contact.face(), near);
	}

	private static double attachedSurfaceY(RailContact contact, Direction.Axis travelAxis, Vec3d surface) {
		if (contact.face().getAxis().isHorizontal() && travelAxis != Direction.Axis.Y) {
			return contact.pos().getY() + WALL_HORIZONTAL_RIDE_Y;
		}
		return surface.y;
	}

	public static Optional<RailContact> findContact(World world, AbstractMinecartEntity cart) {
		return findContact(world, cart, null);
	}

	private static Optional<RailContact> findContact(World world, AbstractMinecartEntity cart, Direction requiredFace) {
		BlockPos base = cart.getBlockPos();
		List<BlockPos> candidates = new ArrayList<>();
		candidates.add(base);
		candidates.add(base.down());
		candidates.add(base.up());
		for (Direction direction : HORIZONTAL) {
			BlockPos side = base.offset(direction);
			candidates.add(side);
			candidates.add(side.up());
			candidates.add(side.down());
		}

		RailContact best = null;
		double bestScore = CONTACT_RANGE_SQ;
		for (BlockPos pos : candidates) {
			BlockState state = world.getBlockState(pos);
			if (!OmniRailBlock.isOmniRail(state)) {
				continue;
			}
			Direction face = OmniRailBlock.face(state);
			if (requiredFace != null && face != requiredFace) {
				continue;
			}
			double distance = surfacePoint(pos, state, face, cart.getPos()).squaredDistanceTo(cart.getPos());
			double score = contactScore(cart, pos, state, face, distance);
			if (score < bestScore) {
				bestScore = score;
				best = new RailContact(pos, state, face);
			}
		}
		return Optional.ofNullable(best);
	}

	private static double contactScore(AbstractMinecartEntity cart, BlockPos pos, BlockState state, Direction face, double distance) {
		double score = distance;
		Direction ascending = ascendingDirection(state);
		if (face == Direction.UP && ascending != null) {
			double along = cart.getPos().subtract(surfacePoint(pos, Direction.UP)).dotProduct(Vec3d.of(ascending.getVector()));
			if (along > -0.55D && along < 0.62D) {
				score -= 0.22D;
			}
		}
		if (face == Direction.DOWN && ascending != null) {
			double along = cart.getPos().subtract(surfacePoint(pos, state, face, cart.getPos())).dotProduct(Vec3d.of(ascending.getVector()));
			if (along > -0.62D && along < 0.62D) {
				score -= 0.28D;
			}
		}
		Vec3d velocity = cart.getVelocity();
		if (velocity.lengthSquared() > 1.0E-4D) {
			Vec3d tangent = face == Direction.DOWN && ascending != null
					? travelTangent(new RailContact(pos, state, face), velocity)
					: travelTangent(state, velocity);
			if (velocity.dotProduct(tangent) > 0.0D) {
				score -= 0.04D;
			}
		}
		return score;
	}

	public static Optional<Vec3d> passengerAttachmentOffset(AbstractMinecartEntity cart) {
		Optional<RailContact> contact = findContact(cart.getWorld(), cart);
		if (contact.isEmpty() || contact.get().face() == Direction.UP) {
			return Optional.empty();
		}
		return Optional.of(switch (contact.get().face()) {
			case DOWN -> new Vec3d(0.0D, -0.44D, 0.0D);
			case NORTH -> new Vec3d(0.0D, 0.28D, 0.42D);
			case SOUTH -> new Vec3d(0.0D, 0.28D, -0.42D);
			case EAST -> new Vec3d(-0.42D, 0.28D, 0.0D);
			case WEST -> new Vec3d(0.42D, 0.28D, 0.0D);
			default -> Vec3d.ZERO;
		});
	}

	private static Vec3d projectOntoPlane(Vec3d vector, Direction face) {
		Vec3d normal = Vec3d.of(face.getVector());
		return vector.subtract(normal.multiply(vector.dotProduct(normal)));
	}

	private static Direction.Axis dominantAxis(Vec3d vector) {
		double ax = Math.abs(vector.x);
		double ay = Math.abs(vector.y);
		double az = Math.abs(vector.z);
		if (ax >= ay && ax >= az) {
			return Direction.Axis.X;
		}
		return ay >= az ? Direction.Axis.Y : Direction.Axis.Z;
	}

	private static double axisValue(Vec3d vector, Direction.Axis axis) {
		return switch (axis) {
			case X -> vector.x;
			case Y -> vector.y;
			case Z -> vector.z;
		};
	}

	private static Vec3d withAxisValue(Vec3d vector, Direction.Axis axis, double value) {
		return switch (axis) {
			case X -> new Vec3d(value, vector.y, vector.z);
			case Y -> new Vec3d(vector.x, value, vector.z);
			case Z -> new Vec3d(vector.x, vector.y, value);
		};
	}

	private static double axisSign(Direction direction) {
		return direction.getDirection() == Direction.AxisDirection.POSITIVE ? 1.0D : -1.0D;
	}

	private static Vec3d clamp(Vec3d velocity, double max) {
		return velocity.length() <= max ? velocity : velocity.normalize().multiply(max);
	}

	private static Vec3d projectOntoSegment(Vec3d point, Vec3d start, Vec3d end) {
		Vec3d segment = end.subtract(start);
		double lengthSq = segment.lengthSquared();
		if (lengthSq < 1.0E-6D) {
			return start;
		}
		double t = clampScalar(point.subtract(start).dotProduct(segment) / lengthSq, 0.0D, 1.0D);
		return start.add(segment.multiply(t));
	}

	private static Vec3d ceilingSlopeBodyNormal(Direction ascending) {
		return Vec3d.of(ascending.getVector()).add(0.0D, -1.0D, 0.0D).normalize();
	}

	private static double clampScalar(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	public record RailContact(BlockPos pos, BlockState state, Direction face) {
	}

	private record ForcedContact(BlockPos pos, Direction face, long untilTick) {
	}
}
