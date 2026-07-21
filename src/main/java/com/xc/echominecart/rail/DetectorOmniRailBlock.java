package com.xc.echominecart.rail;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import java.util.List;

/** Omni-directional detector rail with cart sensing, redstone output, and comparator support. */
public final class DetectorOmniRailBlock extends OmniRailBlock {
	public static final MapCodec<DetectorOmniRailBlock> CODEC = AbstractBlock.createCodec(DetectorOmniRailBlock::new);
	private static final int DETECTOR_DELAY = 20;

	public DetectorOmniRailBlock(Settings settings) {
		super(settings, false, false, false, false);
	}

	@Override
	protected MapCodec<? extends AbstractRailBlock> getCodec() {
		return CODEC;
	}

	@Override
	protected boolean emitsRedstonePower(BlockState state) {
		return true;
	}

	@Override
	protected void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
		super.onEntityCollision(state, world, pos, entity);
		if (!world.isClient() && entity instanceof AbstractMinecartEntity) {
			updatePoweredStatus(world, pos, state);
		}
	}

	@Override
	protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		if (state.get(POWERED)) {
			updatePoweredStatus(world, pos, state);
		}
	}

	@Override
	protected int getWeakRedstonePower(BlockState state, BlockView world, BlockPos pos, Direction direction) {
		return state.get(POWERED) ? 15 : 0;
	}

	@Override
	protected int getStrongRedstonePower(BlockState state, BlockView world, BlockPos pos, Direction direction) {
		return state.get(POWERED) && direction == Direction.UP ? 15 : 0;
	}

	@Override
	protected boolean hasComparatorOutput(BlockState state) {
		return true;
	}

	@Override
	protected int getComparatorOutput(BlockState state, World world, BlockPos pos) {
		if (!state.get(POWERED)) {
			return 0;
		}
		for (AbstractMinecartEntity cart : cartsOnRail(world, pos)) {
			if (cart instanceof Inventory inventory) {
				return ScreenHandler.calculateComparatorOutput(inventory);
			}
		}
		return 15;
	}

	/** Recomputes detection without changing the rail's previously resolved connection topology. */
	private void updatePoweredStatus(World world, BlockPos pos, BlockState state) {
		boolean hasCart = !cartsOnRail(world, pos).isEmpty();
		boolean powered = state.get(POWERED);
		if (hasCart != powered) {
			BlockState updated = withConnections(world, pos, state.with(POWERED, hasCart));
			world.setBlockState(pos, updated, Block.NOTIFY_ALL);
			world.updateNeighborsAlways(pos, this);
			world.updateComparators(pos, this);
		}
		if (hasCart) {
			world.scheduleBlockTick(pos, this, DETECTOR_DELAY);
		}
	}

	private static List<AbstractMinecartEntity> cartsOnRail(World world, BlockPos pos) {
		Box box = new Box(pos).expand(0.20D, 0.20D, 0.20D);
		return world.getEntitiesByType(TypeFilter.instanceOf(AbstractMinecartEntity.class), box, cart -> !cart.isRemoved());
	}
}
