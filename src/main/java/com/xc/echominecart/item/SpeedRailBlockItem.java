package com.xc.echominecart.item;

import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public final class SpeedRailBlockItem extends BlockItem {
	public SpeedRailBlockItem(Block block, Item.Settings settings) {
		super(block, settings);
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return true;
	}
}
