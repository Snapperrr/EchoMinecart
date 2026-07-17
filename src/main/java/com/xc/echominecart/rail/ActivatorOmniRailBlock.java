package com.xc.echominecart.rail;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.AbstractRailBlock;

public final class ActivatorOmniRailBlock extends OmniRailBlock {
	public static final MapCodec<ActivatorOmniRailBlock> CODEC = AbstractBlock.createCodec(ActivatorOmniRailBlock::new);

	public ActivatorOmniRailBlock(Settings settings) {
		super(settings, false, true, true, false);
	}

	@Override
	protected MapCodec<? extends AbstractRailBlock> getCodec() {
		return CODEC;
	}
}
