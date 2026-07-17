package com.xc.echominecart.rail;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.RailBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class SpeedRailBlock extends RailBlock {
	public SpeedRailBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!newState.isOf(this) && world instanceof ServerWorld serverWorld) {
			SpeedRailStorage.remove(serverWorld, pos);
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}
}
