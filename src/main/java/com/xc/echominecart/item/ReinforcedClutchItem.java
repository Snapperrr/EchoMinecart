package com.xc.echominecart.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Durable ring-vehicle transmission module; item damage represents clutch wear. */
public final class ReinforcedClutchItem extends Item {
	private static final int[] STRIKE_TICKS = {0, 4, 8};
	private static final float[] STRIKE_PITCHES = {0.68F, 0.61F, 0.55F};
	private static final Map<UUID, Integer> CRAFT_SOUND_SEQUENCES = new HashMap<>();

	public ReinforcedClutchItem(Settings settings) {
		super(settings);
	}

	@Override
	public void onCraftByPlayer(ItemStack stack, World world, PlayerEntity player) {
		super.onCraftByPlayer(stack, world, player);
		if (world instanceof ServerWorld) {
			CRAFT_SOUND_SEQUENCES.put(player.getUuid(), 0);
		}
	}

	public static void serverTick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, Integer>> iterator = CRAFT_SOUND_SEQUENCES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Integer> entry = iterator.next();
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
			if (player == null) {
				iterator.remove();
				continue;
			}
			int tick = entry.getValue();
			for (int strike = 0; strike < STRIKE_TICKS.length; strike++) {
				if (tick == STRIKE_TICKS[strike]) {
					player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
							SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS,
							0.78F + strike * 0.08F, STRIKE_PITCHES[strike]);
				}
			}
			if (tick >= STRIKE_TICKS[STRIKE_TICKS.length - 1]) {
				iterator.remove();
			} else {
				entry.setValue(tick + 1);
			}
		}
	}
}
