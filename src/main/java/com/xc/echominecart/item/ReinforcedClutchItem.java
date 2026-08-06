package com.xc.echominecart.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

/** Durable ring-vehicle transmission module; item damage represents clutch wear. */
public final class ReinforcedClutchItem extends Item {
	public ReinforcedClutchItem(Settings settings) {
		super(settings);
	}

	@Override
	public void onCraftByPlayer(ItemStack stack, World world, PlayerEntity player) {
		super.onCraftByPlayer(stack, world, player);
		if (world instanceof ServerWorld serverWorld) {
			serverWorld.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 1.45F, 0.82F);
		}
	}
}
