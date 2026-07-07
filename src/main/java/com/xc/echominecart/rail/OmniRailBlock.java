package com.xc.echominecart.rail;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.RailShape;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 全向铁轨：可贴附在地面、四面墙和天花板上，在同一贴附面内允许 T 字/十字/多分叉全连接，
 * 并通过统一的拐角候选规则自动与相邻贴附面的铁轨衔接（不需要专门的过渡方块）。
 *
 * 连接关系对每个面内切线方向 d（法线 n = FACE）检查四个候选：
 *   1. 直线      (P+d,   n)
 *   2. 内角·上位  (P+n,  -d)
 *   3. 内角·下位  (P+d,  -d)
 *   4. 外角      (P+d-n, d)
 * 这四条规则两两对称，保证 A 认得 B 时 B 一定也认得 A。
 */
public class OmniRailBlock extends AbstractRailBlock {
	public static final MapCodec<OmniRailBlock> CODEC = AbstractBlock.createCodec(OmniRailBlock::new);
	public static final EnumProperty<RailShape> SHAPE = Properties.RAIL_SHAPE;
	public static final DirectionProperty FACE = DirectionProperty.of("face");
	public static final BooleanProperty POWERED = Properties.POWERED;
	public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
	public static final BooleanProperty NORTH = BooleanProperty.of("north");
	public static final BooleanProperty SOUTH = BooleanProperty.of("south");
	public static final BooleanProperty EAST = BooleanProperty.of("east");
	public static final BooleanProperty WEST = BooleanProperty.of("west");
	public static final BooleanProperty UP = BooleanProperty.of("up");
	public static final BooleanProperty DOWN = BooleanProperty.of("down");

	private static final Map<Direction, BooleanProperty> CONNECTION_PROPERTIES = new EnumMap<>(Map.of(
			Direction.NORTH, NORTH,
			Direction.SOUTH, SOUTH,
			Direction.EAST, EAST,
			Direction.WEST, WEST,
			Direction.UP, UP,
			Direction.DOWN, DOWN));

	private static final VoxelShape FLOOR_SHAPE = VoxelShapes.cuboid(0.0D, 0.0D, 0.0D, 1.0D, 0.125D, 1.0D);
	private static final VoxelShape CEILING_SHAPE = VoxelShapes.cuboid(0.0D, 0.875D, 0.0D, 1.0D, 1.0D, 1.0D);
	private static final VoxelShape NORTH_WALL_SHAPE = VoxelShapes.cuboid(0.0D, 0.0D, 0.875D, 1.0D, 1.0D, 1.0D);
	private static final VoxelShape SOUTH_WALL_SHAPE = VoxelShapes.cuboid(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 0.125D);
	private static final VoxelShape EAST_WALL_SHAPE = VoxelShapes.cuboid(0.0D, 0.0D, 0.0D, 0.125D, 1.0D, 1.0D);
	private static final VoxelShape WEST_WALL_SHAPE = VoxelShapes.cuboid(0.875D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

	private final boolean accelerates;
	private final boolean redstoneControlled;
	private final boolean activator;

	public OmniRailBlock(Settings settings) {
		this(settings, false, false, false);
	}

	protected OmniRailBlock(Settings settings, boolean accelerates, boolean redstoneControlled, boolean activator) {
		super(false, settings);
		this.accelerates = accelerates;
		this.redstoneControlled = redstoneControlled;
		this.activator = activator;
		setDefaultState(getStateManager().getDefaultState()
				.with(SHAPE, RailShape.NORTH_SOUTH)
				.with(FACE, Direction.UP)
				.with(POWERED, false)
				.with(WATERLOGGED, false)
				.with(NORTH, true)
				.with(SOUTH, true)
				.with(EAST, false)
				.with(WEST, false)
				.with(UP, false)
				.with(DOWN, false));
	}

	@Override
	protected MapCodec<? extends AbstractRailBlock> getCodec() {
		return CODEC;
	}

	@Override
	public Property<RailShape> getShapeProperty() {
		return SHAPE;
	}

	public boolean accelerates() {
		return accelerates;
	}

	public boolean activator() {
		return activator;
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext context) {
		Direction face = context.getSide();
		if (!canAttachAt(context.getWorld(), context.getBlockPos(), face)) {
			return null;
		}
		boolean isolated = true;
		for (Direction tangent : planeTangents(face)) {
			if (findLink(context.getWorld(), context.getBlockPos(), face, tangent) != null) {
				isolated = false;
				break;
			}
		}
		BlockState state = getDefaultState()
				.with(FACE, face)
				.with(POWERED, redstoneControlled && context.getWorld().isReceivingRedstonePower(context.getBlockPos()))
				.with(WATERLOGGED, context.getWorld().getFluidState(context.getBlockPos()).getFluid() == Fluids.WATER);
		state = withConnections(context.getWorld(), context.getBlockPos(), state);
		if (accelerates && isolated && face.getAxis().isHorizontal()) {
			state = orientIsolatedWallRail(state, context.getHorizontalPlayerFacing());
		}
		return state;
	}

	@Override
	protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
		return canAttachAt(world, pos, state.get(FACE));
	}

	@Override
	protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState, WorldAccess world, BlockPos pos, BlockPos neighborPos) {
		BlockState updated = withConnections(world, pos, withWorldPower(world, pos, state));
		return canPlaceAt(updated, world, pos) ? updated : Blocks.AIR.getDefaultState();
	}

	@Override
	protected void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
		if (!canPlaceAt(state, world, pos)) {
			world.breakBlock(pos, true);
			return;
		}
		BlockState updated = withConnections(world, pos, withWorldPower(world, pos, state));
		if (updated != state) {
			world.setBlockState(pos, updated, Block.NOTIFY_ALL);
		}
	}

	@Override
	protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
		// 不调用 super：AbstractRailBlock 的 onBlockAdded 会跑原版 RailPlacementHelper，
		// 用原版邻接逻辑改写 SHAPE，破坏墙面/天花板轨的连接状态。
		refreshDiagonalPartners(world, pos, state);
	}

	@Override
	protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock())) {
			refreshDiagonalPartners(world, pos, state);
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return switch (state.get(FACE)) {
			case DOWN -> CEILING_SHAPE;
			case NORTH -> NORTH_WALL_SHAPE;
			case SOUTH -> SOUTH_WALL_SHAPE;
			case EAST -> EAST_WALL_SHAPE;
			case WEST -> WEST_WALL_SHAPE;
			default -> FLOOR_SHAPE;
		};
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(SHAPE, FACE, POWERED, WATERLOGGED, NORTH, SOUTH, EAST, WEST, UP, DOWN);
	}

	public static boolean isOmniRail(BlockState state) {
		return state.getBlock() instanceof OmniRailBlock;
	}

	public static boolean isAccelerating(BlockState state) {
		return state.getBlock() instanceof OmniRailBlock rail && rail.accelerates && state.get(POWERED);
	}

	public static boolean isPoweredRail(BlockState state) {
		return state.getBlock() instanceof OmniRailBlock rail && rail.accelerates;
	}

	public static boolean isActivatorRail(BlockState state) {
		return state.getBlock() instanceof OmniRailBlock rail && rail.activator;
	}

	public static Direction face(BlockState state) {
		return isOmniRail(state) ? state.get(FACE) : Direction.UP;
	}

	public static boolean canAttachAt(WorldView world, BlockPos pos, Direction face) {
		BlockPos supportPos = pos.offset(face.getOpposite());
		BlockState support = world.getBlockState(supportPos);
		return !support.isAir()
				&& !AbstractRailBlock.isRail(support)
				&& support.isSideSolidFullSquare(world, supportPos, face);
	}

	/** 面内切线方向：地面/天花板是四个水平方向；墙面是上、下加两个沿墙水平方向。 */
	public static List<Direction> planeTangents(Direction face) {
		if (face.getAxis().isVertical()) {
			return List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
		}
		List<Direction> tangents = new ArrayList<>(4);
		tangents.add(Direction.UP);
		tangents.add(Direction.DOWN);
		for (Direction direction : Direction.Type.HORIZONTAL) {
			if (direction.getAxis() != face.getAxis()) {
				tangents.add(direction);
			}
		}
		return tangents;
	}

	/** 当前状态下已连接的切线方向。 */
	public static List<Direction> connections(BlockState state) {
		List<Direction> directions = new ArrayList<>(4);
		if (!isOmniRail(state)) {
			return directions;
		}
		for (Map.Entry<Direction, BooleanProperty> entry : CONNECTION_PROPERTIES.entrySet()) {
			if (state.get(entry.getValue())) {
				directions.add(entry.getKey());
			}
		}
		return directions;
	}

	/** 切线方向 d 上的五个连接候选（位置 + 对方需要的贴附面）。 */
	public static List<RailLink> linkCandidates(BlockPos pos, Direction face, Direction tangent) {
		return List.of(
				new RailLink(pos.offset(tangent), face, LinkKind.STRAIGHT),
				new RailLink(pos.offset(face), tangent.getOpposite(), LinkKind.INNER_CORNER),
				new RailLink(pos.offset(tangent), tangent.getOpposite(), LinkKind.INNER_CORNER),
				// 对角内角 (P+n+d, -d)：覆盖"墙顶与天花板齐平相接"这类隔一个对角空格的几何，
				// 从对方视角代回公式同样成立（自对称）。
				new RailLink(pos.offset(face).offset(tangent), tangent.getOpposite(), LinkKind.INNER_CORNER),
				new RailLink(pos.offset(tangent).offset(face.getOpposite()), tangent, LinkKind.OUTER_CORNER));
	}

	/** 查找切线方向 d 上实际连通的铁轨；找不到返回 null。 */
	public static RailLink findLink(WorldView world, BlockPos pos, Direction face, Direction tangent) {
		for (RailLink candidate : linkCandidates(pos, face, tangent)) {
			BlockState target = world.getBlockState(candidate.pos());
			if (isOmniRail(target) && face(target) == candidate.face()) {
				return candidate;
			}
		}
		return null;
	}

	public static BlockState withConnections(WorldView world, BlockPos pos, BlockState state) {
		if (!isOmniRail(state)) {
			return state;
		}
		Direction face = state.get(FACE);
		Map<Direction, Boolean> connected = new EnumMap<>(Direction.class);
		for (Direction direction : Direction.values()) {
			connected.put(direction, false);
		}
		int count = 0;
		for (Direction tangent : planeTangents(face)) {
			RailLink link = findLink(world, pos, face, tangent);
			boolean linked = link != null;
			connected.put(tangent, linked);
			if (linked) {
				count++;
			}
		}
		if (face == Direction.UP) {
			for (Direction direction : Direction.Type.HORIZONTAL) {
				if (isRaisedFloorRail(world, pos, direction) || isLowerFloorRail(world, pos, direction)) {
					connected.put(direction, true);
					count++;
				}
			}
		}
		if (count == 0) {
			// 孤立铁轨给一条默认直线，保证外观和物理始终有切线可用。
			List<Direction> tangents = planeTangents(face);
			connected.put(tangents.get(0), true);
			connected.put(tangents.get(1), true);
		}
		BlockState updated = state;
		for (Map.Entry<Direction, BooleanProperty> entry : CONNECTION_PROPERTIES.entrySet()) {
			updated = updated.with(entry.getValue(), connected.get(entry.getKey()));
		}
		RailShape shape = shapeFromConnections(
				connected.get(Direction.NORTH),
				connected.get(Direction.SOUTH),
				connected.get(Direction.EAST),
				connected.get(Direction.WEST));
		if (face == Direction.UP) {
			RailShape ascending = ascendingTowardRaisedRail(world, pos);
			if (ascending != null) {
				shape = ascending;
			}
		}
		if (face != Direction.UP) {
			RailShape edgeShape = wallEdgeShape(world, pos, face);
			if (edgeShape != null) {
				shape = edgeShape;
			}
		}
		return updated.with(SHAPE, shape);
	}

	private static RailShape ascendingTowardRaisedRail(WorldView world, BlockPos pos) {
		for (Direction direction : Direction.Type.HORIZONTAL) {
			if (isRaisedFloorRail(world, pos, direction)) {
				return switch (direction) {
					case NORTH -> RailShape.ASCENDING_NORTH;
					case SOUTH -> RailShape.ASCENDING_SOUTH;
					case EAST -> RailShape.ASCENDING_EAST;
					default -> RailShape.ASCENDING_WEST;
				};
			}
		}
		return null;
	}

	private static boolean isRaisedFloorRail(WorldView world, BlockPos pos, Direction direction) {
		BlockState target = world.getBlockState(pos.offset(direction).up());
		return isOmniRail(target) && face(target) == Direction.UP;
	}

	private static boolean isLowerFloorRail(WorldView world, BlockPos pos, Direction direction) {
		BlockState target = world.getBlockState(pos.offset(direction).down());
		return isOmniRail(target) && face(target) == Direction.UP;
	}

	private static RailShape wallEdgeShape(WorldView world, BlockPos pos, Direction face) {
		if (face.getAxis().isVertical()) {
			return null;
		}
		RailLink ceiling = findLink(world, pos, face, Direction.UP);
		if (ceiling != null && ceiling.kind() != LinkKind.STRAIGHT && ceiling.face() == Direction.DOWN) {
			return RailShape.ASCENDING_NORTH;
		}
		RailLink floor = findLink(world, pos, face, Direction.DOWN);
		if (floor != null && floor.kind() != LinkKind.STRAIGHT && floor.face() == Direction.UP) {
			return RailShape.ASCENDING_SOUTH;
		}
		return null;
	}

	public static BlockState orientIsolatedWallRail(BlockState state, Direction playerFacing) {
		if (!isOmniRail(state) || state.get(FACE).getAxis().isVertical()) {
			return state;
		}
		Direction face = state.get(FACE);
		Direction horizontal = playerFacing.getAxis() == face.getAxis() ? playerFacing.rotateYClockwise() : playerFacing;
		if (horizontal.getAxis().isVertical() || horizontal.getAxis() == face.getAxis()) {
			horizontal = face.getAxis() == Direction.Axis.X ? Direction.NORTH : Direction.EAST;
		}
		return state
				.with(UP, false)
				.with(DOWN, false)
				.with(NORTH, horizontal == Direction.NORTH || horizontal.getOpposite() == Direction.NORTH)
				.with(SOUTH, horizontal == Direction.SOUTH || horizontal.getOpposite() == Direction.SOUTH)
				.with(EAST, horizontal == Direction.EAST || horizontal.getOpposite() == Direction.EAST)
				.with(WEST, horizontal == Direction.WEST || horizontal.getOpposite() == Direction.WEST)
				.with(SHAPE, horizontal.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);
	}

	/**
	 * 拐角候选是斜向关系，原版邻居更新只覆盖直接相邻的六格，
	 * 所以放置/拆除时手动刷新所有可能把本轨当作拐角搭档的铁轨。
	 */
	private static void refreshDiagonalPartners(World world, BlockPos pos, BlockState state) {
		if (world.isClient() || !isOmniRail(state)) {
			return;
		}
		Direction face = state.get(FACE);
		for (Direction tangent : planeTangents(face)) {
			for (RailLink candidate : linkCandidates(pos, face, tangent)) {
				BlockState partner = world.getBlockState(candidate.pos());
				if (isOmniRail(partner)) {
					BlockState refreshed = withConnections(world, candidate.pos(), partner);
					if (refreshed != partner) {
						world.setBlockState(candidate.pos(), refreshed, Block.NOTIFY_ALL);
					}
				}
			}
		}
		if (face == Direction.UP) {
			for (Direction direction : Direction.Type.HORIZONTAL) {
				refreshRailAt(world, pos.offset(direction).down());
				refreshRailAt(world, pos.offset(direction).up());
				refreshRailAt(world, pos.offset(direction.getOpposite()).down());
				refreshRailAt(world, pos.offset(direction.getOpposite()).up());
			}
		}
	}

	private static void refreshRailAt(World world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (!isOmniRail(state)) {
			return;
		}
		BlockState refreshed = withConnections(world, pos, state);
		if (refreshed != state) {
			world.setBlockState(pos, refreshed, Block.NOTIFY_ALL);
		}
	}

	private static RailShape shapeFromConnections(boolean north, boolean south, boolean east, boolean west) {
		if ((east || west) && !(north || south)) {
			return RailShape.EAST_WEST;
		}
		if (north && east && !south && !west) {
			return RailShape.NORTH_EAST;
		}
		if (north && west && !south && !east) {
			return RailShape.NORTH_WEST;
		}
		if (south && east && !north && !west) {
			return RailShape.SOUTH_EAST;
		}
		if (south && west && !north && !east) {
			return RailShape.SOUTH_WEST;
		}
		return RailShape.NORTH_SOUTH;
	}

	private BlockState withWorldPower(WorldAccess world, BlockPos pos, BlockState state) {
		if (!redstoneControlled) {
			return state;
		}
		return state.with(POWERED, receivesChainedPower(world, pos, state, 0));
	}

	private boolean receivesChainedPower(WorldAccess world, BlockPos pos, BlockState state, int depth) {
		if (world.isReceivingRedstonePower(pos)) {
			return true;
		}
		if (!accelerates || depth >= 8) {
			return false;
		}
		// 充能铁轨沿已连接的同类轨道传播动力，最多 8 格，和原版手感接近。
		for (Direction tangent : connections(state)) {
			RailLink link = findLink(world, pos, state.get(FACE), tangent);
			if (link != null && receivesRailPowerFrom(world, link.pos(), depth)) {
				return true;
			}
			if (state.get(FACE) == Direction.UP && tangent.getAxis().isHorizontal()) {
				if (receivesRailPowerFrom(world, pos.offset(tangent).up(), depth)
						|| receivesRailPowerFrom(world, pos.offset(tangent).down(), depth)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean receivesRailPowerFrom(WorldAccess world, BlockPos pos, int depth) {
		BlockState neighbor = world.getBlockState(pos);
		return neighbor.getBlock() instanceof OmniRailBlock rail
				&& rail.accelerates
				&& (world.isReceivingRedstonePower(pos) || rail.receivesChainedPower(world, pos, neighbor, depth + 1));
	}

	public enum LinkKind {
		STRAIGHT,
		INNER_CORNER,
		OUTER_CORNER
	}

	public record RailLink(BlockPos pos, Direction face, LinkKind kind) {
	}
}
