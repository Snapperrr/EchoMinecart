package com.xc.echominecart.rail;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.AbstractRailBlock;

public final class PoweredOmniRailBlock extends OmniRailBlock {
	public static final MapCodec<PoweredOmniRailBlock> CODEC = AbstractBlock.createCodec(PoweredOmniRailBlock::new);

	public PoweredOmniRailBlock(Settings settings) {
		super(settings, true, true, false);
	}

	@Override
	protected MapCodec<? extends AbstractRailBlock> getCodec() {
		return CODEC;
	}
}
