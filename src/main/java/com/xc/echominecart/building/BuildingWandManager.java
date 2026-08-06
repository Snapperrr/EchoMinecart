package com.xc.echominecart.building;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Owns per-player selections and spreads large building-wand edits across server ticks. */
public final class BuildingWandManager {
	private static final int MAX_REGION_EDGE = 256;
	private static final long MAX_REGION_BLOCKS = 262_144L;
	private static final int BLOCKS_PER_TICK = 4_096;
	private static final DustParticleEffect SELECTION_PARTICLE = new DustParticleEffect(
			new Vector3f(0.08F, 0.9F, 1.0F), 1.15F);
	private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
	private static final Map<UUID, EditTask> TASKS = new HashMap<>();

	private BuildingWandManager() {
	}

	public static void initialize() {
		PlayerBlockBreakEvents.AFTER.register(BuildingWandManager::afterBlockBreak);
		ServerTickEvents.END_WORLD_TICK.register(BuildingWandManager::tickWorld);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			SELECTIONS.clear();
			TASKS.clear();
		});
	}

	public static void selectCorner(ServerPlayerEntity player, BlockPos pos) {
		if (TASKS.containsKey(player.getUuid())) {
			player.sendMessage(Text.translatable("message.echominecart.building_wand.busy"), true);
			return;
		}
		Selection current = SELECTIONS.get(player.getUuid());
		if (current == null || current.isComplete()
				|| !current.worldKey.equals(player.getWorld().getRegistryKey())) {
			Selection selection = new Selection(player.getWorld().getRegistryKey(), pos.toImmutable());
			SELECTIONS.put(player.getUuid(), selection);
			showPoint(player.getServerWorld(), pos);
			player.sendMessage(Text.translatable("message.echominecart.building_wand.corner_one",
					pos.getX(), pos.getY(), pos.getZ()), true);
			player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.7F, 0.9F);
			return;
		}

		Region region = Region.between(current.cornerOne, pos);
		if (region.sizeX() > MAX_REGION_EDGE || region.sizeY() > MAX_REGION_EDGE
				|| region.sizeZ() > MAX_REGION_EDGE || region.volume() > MAX_REGION_BLOCKS) {
			player.sendMessage(Text.translatable("message.echominecart.building_wand.too_large",
					MAX_REGION_EDGE, MAX_REGION_BLOCKS), true);
			return;
		}
		current.region = region;
		current.confirmation = null;
		showPoint(player.getServerWorld(), pos);
		player.sendMessage(Text.translatable("message.echominecart.building_wand.selected",
				region.volume()), true);
		player.playSound(SoundEvents.BLOCK_BEACON_ACTIVATE, 0.75F, 1.3F);
	}

	public static void clearSelection(ServerPlayerEntity player) {
		boolean removed = SELECTIONS.remove(player.getUuid()) != null;
		player.sendMessage(Text.translatable(removed
				? "message.echominecart.building_wand.selection_cleared"
				: "message.echominecart.building_wand.no_selection"), true);
		player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.65F, 0.75F);
	}

	public static void onBlockPlaced(ServerPlayerEntity player, BlockPos pos, BlockState placedState) {
		Selection selection = validSelection(player, pos);
		if (selection == null || TASKS.containsKey(player.getUuid())) {
			return;
		}
		BlockState fillState = placedState.getBlock().getDefaultState();
		if (selection.confirmation instanceof FillConfirmation fill
				&& fill.block == fillState.getBlock()) {
			startTask(player, selection, fillState, false);
			return;
		}
		selection.confirmation = new FillConfirmation(fillState.getBlock());
		player.sendMessage(Text.translatable("message.echominecart.building_wand.confirm_fill",
				selection.region.volume(), fillState.getBlock().getName()), false);
		player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.7F, 1.2F);
	}

	private static void afterBlockBreak(World world, PlayerEntity player, BlockPos pos,
			BlockState state, BlockEntity blockEntity) {
		if (!(world instanceof ServerWorld) || !(player instanceof ServerPlayerEntity serverPlayer)) {
			return;
		}
		Selection selection = validSelection(serverPlayer, pos);
		if (selection == null || TASKS.containsKey(player.getUuid())) {
			return;
		}
		if (selection.confirmation == ClearConfirmation.INSTANCE) {
			startTask(serverPlayer, selection, Blocks.AIR.getDefaultState(), true);
			return;
		}
		selection.confirmation = ClearConfirmation.INSTANCE;
		player.sendMessage(Text.translatable("message.echominecart.building_wand.confirm_clear",
				selection.region.volume()), false);
		player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.75F, 0.9F);
	}

	private static Selection validSelection(ServerPlayerEntity player, BlockPos pos) {
		Selection selection = SELECTIONS.get(player.getUuid());
		return selection != null && selection.isComplete()
				&& selection.worldKey.equals(player.getWorld().getRegistryKey())
				&& selection.region.contains(pos) ? selection : null;
	}

	private static void startTask(ServerPlayerEntity player, Selection selection,
			BlockState state, boolean clear) {
		selection.confirmation = null;
		TASKS.put(player.getUuid(), new EditTask(player.getUuid(), selection.worldKey,
				selection.region, state, clear));
		player.sendMessage(Text.translatable(clear
				? "message.echominecart.building_wand.clearing"
				: "message.echominecart.building_wand.filling", selection.region.volume()), false);
		player.playSound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 0.8F, clear ? 0.72F : 1.25F);
	}

	private static void tickWorld(ServerWorld world) {
		for (Map.Entry<UUID, EditTask> entry : new ArrayList<>(TASKS.entrySet())) {
			EditTask task = entry.getValue();
			if (!task.worldKey.equals(world.getRegistryKey())) {
				continue;
			}
			int processed = 0;
			while (processed < BLOCKS_PER_TICK && task.positions.hasNext()) {
				BlockPos pos = task.positions.next();
				processed++;
				if (!world.isChunkLoaded(ChunkPos.toLong(pos))) {
					task.skipped++;
					continue;
				}
				BlockState current = world.getBlockState(pos);
				if (current.equals(task.state)) {
					continue;
				}
				if (world.setBlockState(pos, task.state, Block.NOTIFY_ALL)) {
					task.changed++;
				}
			}
			if (!task.positions.hasNext()) {
				TASKS.remove(entry.getKey());
				finishTask(world, task);
			}
		}
		showSelections(world);
	}

	private static void finishTask(ServerWorld world, EditTask task) {
		ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(task.playerId);
		if (player == null) {
			return;
		}
		player.sendMessage(Text.translatable("message.echominecart.building_wand.complete",
				task.changed, task.skipped), false);
		player.playSound(task.clear ? SoundEvents.BLOCK_BEACON_DEACTIVATE
				: SoundEvents.BLOCK_BEACON_ACTIVATE, 0.85F, task.clear ? 0.82F : 1.18F);
	}

	private static void showSelections(ServerWorld world) {
		if (world.getTime() % 10L != 0L) {
			return;
		}
		for (Map.Entry<UUID, Selection> entry : SELECTIONS.entrySet()) {
			Selection selection = entry.getValue();
			if (!selection.worldKey.equals(world.getRegistryKey())) {
				continue;
			}
			ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(entry.getKey());
			if (player == null) {
				continue;
			}
			showPoint(player, selection.cornerOne);
			if (selection.region != null) {
				for (BlockPos corner : selection.region.corners()) {
					showPoint(player, corner);
				}
			}
		}
	}

	private static void showPoint(ServerWorld world, BlockPos pos) {
		world.spawnParticles(SELECTION_PARTICLE, pos.getX() + 0.5D, pos.getY() + 1.05D,
				pos.getZ() + 0.5D, 18, 0.28D, 0.28D, 0.28D, 0.01D);
	}

	private static void showPoint(ServerPlayerEntity player, BlockPos pos) {
		player.getServerWorld().spawnParticles(player, SELECTION_PARTICLE, false,
				pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D,
				1, 0.02D, 0.02D, 0.02D, 0.0D);
	}

	private static final class Selection {
		private final RegistryKey<World> worldKey;
		private final BlockPos cornerOne;
		private Region region;
		private Confirmation confirmation;

		private Selection(RegistryKey<World> worldKey, BlockPos cornerOne) {
			this.worldKey = worldKey;
			this.cornerOne = cornerOne;
		}

		private boolean isComplete() {
			return region != null;
		}
	}

	private sealed interface Confirmation permits FillConfirmation, ClearConfirmation {
	}

	private record FillConfirmation(Block block) implements Confirmation {
	}

	private enum ClearConfirmation implements Confirmation {
		INSTANCE
	}

	private record Region(BlockPos min, BlockPos max) {
		private static Region between(BlockPos first, BlockPos second) {
			return new Region(new BlockPos(Math.min(first.getX(), second.getX()),
					Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ())),
					new BlockPos(Math.max(first.getX(), second.getX()),
							Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ())));
		}

		private int sizeX() {
			return max.getX() - min.getX() + 1;
		}

		private int sizeY() {
			return max.getY() - min.getY() + 1;
		}

		private int sizeZ() {
			return max.getZ() - min.getZ() + 1;
		}

		private long volume() {
			return (long) sizeX() * sizeY() * sizeZ();
		}

		private boolean contains(BlockPos pos) {
			return pos.getX() >= min.getX() && pos.getX() <= max.getX()
					&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
					&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
		}

		private BlockPos[] corners() {
			return new BlockPos[] {
					new BlockPos(min.getX(), min.getY(), min.getZ()),
					new BlockPos(max.getX(), min.getY(), min.getZ()),
					new BlockPos(min.getX(), max.getY(), min.getZ()),
					new BlockPos(max.getX(), max.getY(), min.getZ()),
					new BlockPos(min.getX(), min.getY(), max.getZ()),
					new BlockPos(max.getX(), min.getY(), max.getZ()),
					new BlockPos(min.getX(), max.getY(), max.getZ()),
					new BlockPos(max.getX(), max.getY(), max.getZ())
			};
		}
	}

	private static final class EditTask {
		private final UUID playerId;
		private final RegistryKey<World> worldKey;
		private final Iterator<BlockPos> positions;
		private final BlockState state;
		private final boolean clear;
		private int changed;
		private int skipped;

		private EditTask(UUID playerId, RegistryKey<World> worldKey,
				Region region, BlockState state, boolean clear) {
			this.playerId = playerId;
			this.worldKey = worldKey;
			this.positions = BlockPos.iterate(region.min, region.max).iterator();
			this.state = state;
			this.clear = clear;
		}
	}
}
