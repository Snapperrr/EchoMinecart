package com.xc.echominecart.item;

import com.xc.echominecart.rail.OmniRailBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.RailShape;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 手动修正单节 EchoMinecart 铁轨状态。
 * 普通右键循环并锁定形态；潜行右键解除锁定，让自动连接重新接管。
 */
public final class RailRepairToolItem extends Item {
	public RailRepairToolItem(Settings settings) {
		super(settings);
	}

	public ActionResult useOnRail(PlayerEntity player, World world, BlockHitResult hitResult) {
		BlockPos pos = targetRailPos(world, hitResult);
		if (pos == null) {
			return ActionResult.PASS;
		}
		BlockState state = world.getBlockState(pos);
		if (!OmniRailBlock.isOmniRail(state)) {
			return ActionResult.PASS;
		}
		if (world.isClient()) {
			return ActionResult.SUCCESS;
		}
		if (player.isSneaking()) {
			BlockState refreshed = OmniRailBlock.autoRefreshState(world, pos, state);
			world.setBlockState(pos, refreshed, Block.NOTIFY_ALL);
			player.sendMessage(Text.literal("已解除该铁轨的手动锁定，恢复自动连接。"), true);
			return ActionResult.SUCCESS;
		}

		List<RailPreset> presets = presetsFor(state);
		RailPreset next = nextPreset(state, presets);
		BlockState updated = applyPreset(state, next);
		world.setBlockState(pos, updated, Block.NOTIFY_ALL);
		player.sendMessage(Text.literal("已锁定铁轨状态：" + describe(next)), true);
		return ActionResult.SUCCESS;
	}

	private static BlockPos targetRailPos(World world, BlockHitResult hitResult) {
		BlockPos clicked = hitResult.getBlockPos();
		if (OmniRailBlock.isOmniRail(world.getBlockState(clicked))) {
			return clicked;
		}
		Direction side = hitResult.getSide();
		BlockPos[] candidates = new BlockPos[]{
				clicked.offset(side), clicked.offset(side.getOpposite()),
				clicked.down(), clicked.up(),
				clicked.north(), clicked.south(), clicked.east(), clicked.west()
		};
		for (BlockPos candidate : candidates) {
			if (OmniRailBlock.isOmniRail(world.getBlockState(candidate))) {
				return candidate;
			}
		}
		return null;
	}

	private static List<RailPreset> presetsFor(BlockState state) {
		Direction face = state.get(OmniRailBlock.FACE);
		if (face.getAxis().isVertical()) {
			return List.of(
					new RailPreset(RailShape.NORTH_SOUTH, Direction.NORTH, Direction.SOUTH),
					new RailPreset(RailShape.EAST_WEST, Direction.EAST, Direction.WEST),
					new RailPreset(RailShape.NORTH_EAST, Direction.NORTH, Direction.EAST),
					new RailPreset(RailShape.NORTH_WEST, Direction.NORTH, Direction.WEST),
					new RailPreset(RailShape.SOUTH_EAST, Direction.SOUTH, Direction.EAST),
					new RailPreset(RailShape.SOUTH_WEST, Direction.SOUTH, Direction.WEST),
					new RailPreset(RailShape.ASCENDING_NORTH, Direction.NORTH, Direction.SOUTH),
					new RailPreset(RailShape.ASCENDING_SOUTH, Direction.NORTH, Direction.SOUTH),
					new RailPreset(RailShape.ASCENDING_EAST, Direction.EAST, Direction.WEST),
					new RailPreset(RailShape.ASCENDING_WEST, Direction.EAST, Direction.WEST)
			);
		}

		Direction horizontal = face.getAxis() == Direction.Axis.X ? Direction.NORTH : Direction.EAST;
		RailShape horizontalShape = horizontal.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
		List<RailPreset> presets = new ArrayList<>(4);
		presets.add(new RailPreset(RailShape.NORTH_SOUTH, Direction.UP, Direction.DOWN));
		presets.add(new RailPreset(horizontalShape, horizontal, horizontal.getOpposite()));
		presets.add(new RailPreset(RailShape.ASCENDING_NORTH, Direction.UP));
		presets.add(new RailPreset(RailShape.ASCENDING_SOUTH, Direction.DOWN));
		return presets;
	}

	private static RailPreset nextPreset(BlockState state, List<RailPreset> presets) {
		RailPreset current = new RailPreset(state.get(OmniRailBlock.SHAPE), currentConnections(state));
		if (isAscending(current.shape())) {
			RailPreset flattened = flatten(current);
			if (flattened != null && presets.contains(flattened)) {
				return flattened;
			}
		}
		int index = presets.indexOf(current);
		return presets.get(index < 0 ? 0 : (index + 1) % presets.size());
	}

	private static RailPreset flatten(RailPreset preset) {
		return switch (preset.shape()) {
			case ASCENDING_NORTH, ASCENDING_SOUTH -> new RailPreset(RailShape.NORTH_SOUTH, Direction.NORTH, Direction.SOUTH);
			case ASCENDING_EAST, ASCENDING_WEST -> new RailPreset(RailShape.EAST_WEST, Direction.EAST, Direction.WEST);
			default -> null;
		};
	}

	private static boolean isAscending(RailShape shape) {
		return shape == RailShape.ASCENDING_NORTH
				|| shape == RailShape.ASCENDING_SOUTH
				|| shape == RailShape.ASCENDING_EAST
				|| shape == RailShape.ASCENDING_WEST;
	}

	private static List<Direction> currentConnections(BlockState state) {
		List<Direction> connections = new ArrayList<>(6);
		if (state.get(OmniRailBlock.NORTH)) {
			connections.add(Direction.NORTH);
		}
		if (state.get(OmniRailBlock.SOUTH)) {
			connections.add(Direction.SOUTH);
		}
		if (state.get(OmniRailBlock.EAST)) {
			connections.add(Direction.EAST);
		}
		if (state.get(OmniRailBlock.WEST)) {
			connections.add(Direction.WEST);
		}
		if (state.get(OmniRailBlock.UP)) {
			connections.add(Direction.UP);
		}
		if (state.get(OmniRailBlock.DOWN)) {
			connections.add(Direction.DOWN);
		}
		return connections;
	}

	private static BlockState applyPreset(BlockState state, RailPreset preset) {
		BlockState updated = state
				.with(OmniRailBlock.MANUAL, true)
				.with(OmniRailBlock.SHAPE, preset.shape())
				.with(OmniRailBlock.NORTH, false)
				.with(OmniRailBlock.SOUTH, false)
				.with(OmniRailBlock.EAST, false)
				.with(OmniRailBlock.WEST, false)
				.with(OmniRailBlock.UP, false)
				.with(OmniRailBlock.DOWN, false);
		for (Direction direction : preset.connections()) {
			updated = setConnection(updated, direction, true);
		}
		return updated;
	}

	private static BlockState setConnection(BlockState state, Direction direction, boolean value) {
		return switch (direction) {
			case NORTH -> state.with(OmniRailBlock.NORTH, value);
			case SOUTH -> state.with(OmniRailBlock.SOUTH, value);
			case EAST -> state.with(OmniRailBlock.EAST, value);
			case WEST -> state.with(OmniRailBlock.WEST, value);
			case UP -> state.with(OmniRailBlock.UP, value);
			case DOWN -> state.with(OmniRailBlock.DOWN, value);
		};
	}

	private static String describe(RailPreset preset) {
		return preset.shape().asString() + " " + preset.connections();
	}

	private record RailPreset(RailShape shape, List<Direction> connections) {
		private RailPreset(RailShape shape, Direction... connections) {
			this(shape, List.of(connections));
		}
	}
}
