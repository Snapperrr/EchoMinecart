package com.xc.echominecart.haul;

import com.mojang.logging.LogUtils;
import com.xc.echominecart.NestedChestMod;
import com.xc.echominecart.carriage.CarriageManager.GroupSnapshot;
import com.xc.echominecart.carriage.CarriageShape;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Moves a bounded structure with a carriage and persists every destructive transition. */
public final class HaulManager {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int MAX_HAUL_BLOCKS = 768;
	private static final int MAX_HAUL_HEIGHT = 48;
	private static final int STOP_TICKS_TO_PLACE = 40;
	private static final int MISSING_GROUP_GRACE_TICKS = 200;
	private static final double PICKUP_START_DISTANCE = 0.35D;
	private static final double VISUAL_VELOCITY_LAG = 0.35D;
	private static final String HAUL_TAG = "echominecart_haul_visual";
	private static final String STATE_ID = "echominecart_active_hauls";
	private static final Direction[] ALL_DIRECTIONS = Direction.values();
	private static final PersistentState.Type<HaulPersistentState> STATE_TYPE = new PersistentState.Type<>(
			HaulPersistentState::new,
			HaulPersistentState::fromNbt,
			null);

	private static final Map<RegistryKey<World>, HaulPersistentState> LOADED_STATES = new HashMap<>();
	private static int cleanupTick;

	private HaulManager() {
	}

	public static void bind(PlayerEntity player, GroupSnapshot snapshot) {
		if (snapshot == null || snapshot.shape() == null || snapshot.anchor() == null
				|| !(snapshot.anchor().getWorld() instanceof ServerWorld world)) {
			return;
		}
		HaulPersistentState persistent = state(world);
		BoundHaul old = persistent.hauls.remove(snapshot.groupId());
		if (old != null) {
			emergencyRelease(world, old);
			saveNow(world, persistent);
		}

		BoundHaul haul = scan(world, snapshot);
		if (haul.blocks.isEmpty() && haul.entities.isEmpty()) {
			message(player, "No movable blocks or entities were found above this carriage.");
			return;
		}
		persistent.hauls.put(snapshot.groupId(), haul);
		persistent.markDirty();
		message(player, "Bound " + haul.blocks.size() + " blocks and " + haul.entities.size()
				+ " entities. The structure will be picked up after the carriage starts moving.");
	}

	public static void tick(ServerWorld world, List<GroupSnapshot> snapshots) {
		HaulPersistentState persistent = state(world);
		Map<UUID, GroupSnapshot> byGroup = new HashMap<>();
		for (GroupSnapshot snapshot : snapshots) {
			byGroup.put(snapshot.groupId(), snapshot);
		}

		for (BoundHaul haul : List.copyOf(persistent.hauls.values())) {
			if (!haul.worldKey.equals(world.getRegistryKey())) {
				continue;
			}
			GroupSnapshot snapshot = byGroup.get(haul.groupId);
			if (snapshot == null || snapshot.anchor() == null || snapshot.anchor().getWorld() != world) {
				haul.missingGroupTicks++;
				if (haul.missingGroupTicks >= MISSING_GROUP_GRACE_TICKS) {
					emergencyRelease(world, haul);
					persistent.hauls.remove(haul.groupId);
					saveNow(world, persistent);
				}
				continue;
			}
			haul.missingGroupTicks = 0;

			if (haul.destinationAnchorBlock != null) {
				if (recoverPendingPlacement(world, haul)) {
					persistent.hauls.remove(haul.groupId);
					saveNow(world, persistent);
					continue;
				}
				saveNow(world, persistent);
			}

			AbstractMinecartEntity anchor = snapshot.anchor();
			if (!haul.pickedUp) {
				if (anchor.getPos().squaredDistanceTo(haul.sourceAnchorPos)
						< PICKUP_START_DISTANCE * PICKUP_START_DISTANCE) {
					continue;
				}
				pickup(world, haul, persistent);
			} else if (!haul.recoveryPrepared) {
				resumePickedUpHaul(world, haul, persistent);
			}

			move(world, haul, anchor, persistent);
			if (snapshot.stillTicks() >= STOP_TICKS_TO_PLACE && tryPlace(world, anchor, haul, persistent)) {
				persistent.hauls.remove(haul.groupId);
				saveNow(world, persistent);
			}
		}

		if (++cleanupTick % 200 == 0) {
			cleanupOrphanDisplays(world, persistent);
		}
	}

	public static void releaseGroup(ServerWorld world, UUID groupId) {
		HaulPersistentState persistent = state(world);
		BoundHaul haul = persistent.hauls.remove(groupId);
		if (haul != null && haul.worldKey.equals(world.getRegistryKey())) {
			emergencyRelease(world, haul);
			saveNow(world, persistent);
		}
	}

	public static void onServerStopping(MinecraftServer server) {
		for (ServerWorld world : server.getWorlds()) {
			HaulPersistentState persistent = LOADED_STATES.get(world.getRegistryKey());
			if (persistent != null) {
				persistent.markDirty();
				world.getPersistentStateManager().save();
			}
		}
		LOADED_STATES.clear();
		cleanupTick = 0;
	}

	private static BoundHaul scan(ServerWorld world, GroupSnapshot snapshot) {
		CarriageShape shape = snapshot.shape();
		BlockPos anchorBlock = shape.anchorBlock();
		Vec3d anchorPos = shape.anchorPos();
		List<CarriedBlock> blocks = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		for (BlockPos seat : shape.seats()) {
			int emptyRun = 0;
			for (int y = 1; y <= MAX_HAUL_HEIGHT && blocks.size() < MAX_HAUL_BLOCKS; y++) {
				BlockPos pos = seat.up(y);
				if (!seen.add(pos)) {
					continue;
				}
				BlockState state = world.getBlockState(pos);
				if (state.isAir()) {
					if (!blocks.isEmpty() && ++emptyRun >= 3) {
						break;
					}
					continue;
				}
				emptyRun = 0;
				blocks.add(new CarriedBlock(
						pos.subtract(anchorBlock),
						new Vec3d(pos.getX(), pos.getY(), pos.getZ()).subtract(anchorPos),
						state));
			}
		}

		Box entityBox = shape.bounds().expand(0.75D, MAX_HAUL_HEIGHT, 0.75D)
				.offset(0.0D, MAX_HAUL_HEIGHT / 2.0D, 0.0D);
		List<CarriedEntity> entities = new ArrayList<>();
		for (Entity entity : world.getOtherEntities(null, entityBox, entity ->
				entity != null
						&& !(entity instanceof AbstractMinecartEntity)
						&& !(entity instanceof PlayerEntity)
						&& !entity.hasVehicle()
						&& !entity.getCommandTags().contains(HAUL_TAG)
						&& !entity.getCommandTags().contains(com.xc.echominecart.carriage.CarriageManager.CHEST_VISUAL_TAG))) {
			entities.add(new CarriedEntity(entity.getUuid(), entity.getPos().subtract(anchorPos), entity.hasNoGravity()));
		}
		return new BoundHaul(
				snapshot.groupId(),
				world.getRegistryKey(),
				anchorBlock,
				snapshot.anchor().getPos(),
				blocks,
				entities);
	}

	private static void pickup(ServerWorld world, BoundHaul haul, HaulPersistentState persistent) {
		// Phase one: capture everything and save it before a single source block is removed.
		for (CarriedBlock block : haul.blocks) {
			BlockPos source = haul.sourceAnchorBlock.add(block.relativePos);
			BlockState current = world.getBlockState(source);
			if (current.isAir()) {
				block.missing = true;
				continue;
			}
			block.state = current;
			BlockEntity blockEntity = world.getBlockEntity(source);
			block.nbt = blockEntity == null
					? null
					: blockEntity.createNbtWithIdentifyingData(world.getRegistryManager());
		}
		haul.blocks.removeIf(block -> block.missing);
		haul.pickedUp = true;
		haul.recoveryPrepared = true;
		saveNow(world, persistent);

		// Phase two: remove the captured blocks without allowing dependent thin blocks to drop mid-batch.
		for (CarriedBlock block : haul.blocks) {
			BlockPos source = haul.sourceAnchorBlock.add(block.relativePos);
			createDisplay(world, block, new Vec3d(source.getX(), source.getY(), source.getZ()));
			removeCapturedBlock(world, source);
		}
		for (CarriedEntity carried : haul.entities) {
			Entity entity = world.getEntity(carried.entityId());
			if (entity != null) {
				entity.setNoGravity(true);
			}
		}
		saveNow(world, persistent);
	}

	private static void resumePickedUpHaul(ServerWorld world, BoundHaul haul, HaulPersistentState persistent) {
		boolean changed = false;
		for (CarriedBlock block : haul.blocks) {
			if (block.missing || block.state == null || block.state.isAir()) {
				continue;
			}
			BlockPos source = haul.sourceAnchorBlock.add(block.relativePos);
			BlockState current = world.getBlockState(source);
			if (!current.isAir()) {
				if (current.equals(block.state)) {
					removeCapturedBlock(world, source);
				} else {
					block.missing = true;
				}
				changed = true;
			}
		}
		for (CarriedEntity carried : haul.entities) {
			Entity entity = world.getEntity(carried.entityId());
			if (entity != null) {
				entity.setNoGravity(true);
			}
		}
		haul.recoveryPrepared = true;
		if (changed) {
			saveNow(world, persistent);
		}
	}

	private static void move(ServerWorld world, BoundHaul haul, AbstractMinecartEntity anchor, HaulPersistentState persistent) {
		Vec3d visualAnchorPos = anchor.getPos().subtract(anchor.getVelocity().multiply(VISUAL_VELOCITY_LAG));
		boolean displayIdsChanged = false;
		for (CarriedBlock block : haul.blocks) {
			if (block.missing || block.state == null || block.state.isAir()) {
				continue;
			}
			Vec3d target = visualAnchorPos.add(block.relativeDisplayPos);
			Entity display = block.displayId == null ? null : world.getEntity(block.displayId);
			if (!(display instanceof DisplayEntity.BlockDisplayEntity) || display.isRemoved()) {
				displayIdsChanged |= createDisplay(world, block, target);
				display = block.displayId == null ? null : world.getEntity(block.displayId);
			}
			if (display != null) {
				display.refreshPositionAfterTeleport(target.x, target.y, target.z);
			}
		}
		if (displayIdsChanged) {
			persistent.markDirty();
		}

		for (CarriedEntity carried : haul.entities) {
			Entity entity = world.getEntity(carried.entityId());
			if (entity == null || entity.isRemoved()) {
				continue;
			}
			Vec3d target = visualAnchorPos.add(carried.relativePos());
			entity.refreshPositionAndAngles(target.x, target.y, target.z, entity.getYaw(), entity.getPitch());
			entity.setVelocity(anchor.getVelocity());
			entity.velocityModified = true;
		}
	}

	private static boolean tryPlace(ServerWorld world, AbstractMinecartEntity anchor, BoundHaul haul, HaulPersistentState persistent) {
		BlockPos anchorBlock = anchor.getBlockPos();
		if (!canRestoreAll(world, anchorBlock, haul.blocks)) {
			return false;
		}

		haul.destinationAnchorBlock = anchorBlock.toImmutable();
		saveNow(world, persistent);
		if (!restoreCarriedBlocks(world, anchorBlock, haul, false)) {
			rollbackPlacedBlocks(world, anchorBlock, haul);
			haul.destinationAnchorBlock = null;
			saveNow(world, persistent);
			return false;
		}

		discardDisplays(world, haul);
		restoreEntityGravity(world, haul);
		return true;
	}

	private static boolean recoverPendingPlacement(ServerWorld world, BoundHaul haul) {
		BlockPos destination = haul.destinationAnchorBlock;
		if (destination == null) {
			return false;
		}
		if (allBlocksPresent(world, destination, haul.blocks)) {
			restoreAllBlockEntities(world, destination, haul.blocks);
			discardDisplays(world, haul);
			restoreEntityGravity(world, haul);
			return true;
		}
		rollbackPlacedBlocks(world, destination, haul);
		haul.destinationAnchorBlock = null;
		haul.recoveryPrepared = false;
		return false;
	}

	private static void emergencyRelease(ServerWorld world, BoundHaul haul) {
		if (haul.destinationAnchorBlock != null) {
			if (allBlocksPresent(world, haul.destinationAnchorBlock, haul.blocks)) {
				restoreAllBlockEntities(world, haul.destinationAnchorBlock, haul.blocks);
				discardDisplays(world, haul);
				restoreEntityGravity(world, haul);
				return;
			}
			rollbackPlacedBlocks(world, haul.destinationAnchorBlock, haul);
			haul.destinationAnchorBlock = null;
		}

		discardDisplays(world, haul);
		for (CarriedBlock block : haul.blocks) {
			if (!haul.pickedUp || block.state == null || block.state.isAir()) {
				block.missing = true;
			}
		}
		if (haul.pickedUp) {
			restoreCarriedBlocks(world, haul.sourceAnchorBlock, haul, true);
		}
		restoreEntityGravity(world, haul);
	}

	private static boolean canRestoreAll(ServerWorld world, BlockPos anchorBlock, List<CarriedBlock> blocks) {
		Set<BlockPos> targets = carriedTargets(anchorBlock, blocks);
		for (CarriedBlock block : blocks) {
			if (block.missing || block.state == null || block.state.isAir()) {
				continue;
			}
			BlockPos target = anchorBlock.add(block.relativePos);
			BlockState existing = world.getBlockState(target);
			if (!existing.isAir() && !existing.isReplaceable()) {
				return false;
			}
			if (!canRestore(world, block.state, target, targets)) {
				return false;
			}
		}
		return true;
	}

	private static boolean restoreCarriedBlocks(ServerWorld world, BlockPos anchorBlock, BoundHaul haul, boolean dropOnFailure) {
		List<CarriedBlock> remaining = new ArrayList<>();
		for (CarriedBlock block : haul.blocks) {
			if (!block.missing && block.state != null && !block.state.isAir()) {
				remaining.add(block);
			}
		}
		while (!remaining.isEmpty()) {
			boolean progressed = false;
			for (int i = 0; i < remaining.size(); i++) {
				CarriedBlock block = remaining.get(i);
				BlockPos target = anchorBlock.add(block.relativePos);
				if (block.state.canPlaceAt(world, target)) {
					placeCarriedBlock(world, target, block);
					remaining.remove(i--);
					progressed = true;
				}
			}
			if (!progressed) {
				break;
			}
		}
		if (remaining.isEmpty()) {
			return true;
		}
		if (!dropOnFailure) {
			return false;
		}
		for (CarriedBlock block : remaining) {
			BlockPos target = anchorBlock.add(block.relativePos);
			if (world.getBlockState(target).isAir() || world.getBlockState(target).isReplaceable()) {
				world.setBlockState(target, block.state, Block.NOTIFY_ALL | Block.FORCE_STATE);
				restoreBlockEntity(world, target, block);
			} else {
				Block.dropStacks(block.state, world, target);
			}
		}
		return true;
	}

	private static void placeCarriedBlock(ServerWorld world, BlockPos target, CarriedBlock block) {
		world.setBlockState(target, block.state, Block.NOTIFY_ALL);
		restoreBlockEntity(world, target, block);
	}

	private static void restoreBlockEntity(ServerWorld world, BlockPos target, CarriedBlock block) {
		if (block.nbt == null) {
			return;
		}
		BlockEntity blockEntity = world.getBlockEntity(target);
		if (blockEntity != null) {
			NbtCompound copy = block.nbt.copy();
			copy.putInt("x", target.getX());
			copy.putInt("y", target.getY());
			copy.putInt("z", target.getZ());
			blockEntity.read(copy, world.getRegistryManager());
			blockEntity.markDirty();
		}
	}

	private static Set<BlockPos> carriedTargets(BlockPos anchorBlock, List<CarriedBlock> blocks) {
		Set<BlockPos> targets = new HashSet<>();
		for (CarriedBlock block : blocks) {
			if (!block.missing && block.state != null && !block.state.isAir()) {
				targets.add(anchorBlock.add(block.relativePos));
			}
		}
		return targets;
	}

	private static boolean canRestore(ServerWorld world, BlockState state, BlockPos target, Set<BlockPos> carriedTargets) {
		if (state.isAir() || state.canPlaceAt(world, target)) {
			return true;
		}
		for (Direction direction : ALL_DIRECTIONS) {
			if (carriedTargets.contains(target.offset(direction))) {
				return true;
			}
		}
		return false;
	}

	private static boolean allBlocksPresent(ServerWorld world, BlockPos anchorBlock, List<CarriedBlock> blocks) {
		for (CarriedBlock block : blocks) {
			if (block.missing || block.state == null || block.state.isAir()) {
				continue;
			}
			if (!world.getBlockState(anchorBlock.add(block.relativePos)).equals(block.state)) {
				return false;
			}
		}
		return true;
	}

	private static void restoreAllBlockEntities(ServerWorld world, BlockPos anchorBlock, List<CarriedBlock> blocks) {
		for (CarriedBlock block : blocks) {
			if (!block.missing && block.nbt != null && block.state != null && !block.state.isAir()) {
				restoreBlockEntity(world, anchorBlock.add(block.relativePos), block);
			}
		}
	}

	private static void rollbackPlacedBlocks(ServerWorld world, BlockPos anchorBlock, BoundHaul haul) {
		for (CarriedBlock block : haul.blocks) {
			if (block.missing || block.state == null || block.state.isAir()) {
				continue;
			}
			BlockPos target = anchorBlock.add(block.relativePos);
			if (world.getBlockState(target).equals(block.state)) {
				removeCapturedBlock(world, target);
			}
		}
	}

	private static void removeCapturedBlock(ServerWorld world, BlockPos pos) {
		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (blockEntity instanceof Inventory inventory) {
			inventory.clear();
			blockEntity.markDirty();
		}
		NestedChestMod.beginSuppressChestDrops(world, pos);
		try {
			world.setBlockState(pos, Blocks.AIR.getDefaultState(),
					Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
		} finally {
			NestedChestMod.endSuppressChestDrops(world, pos);
		}
	}

	private static boolean createDisplay(ServerWorld world, CarriedBlock block, Vec3d position) {
		DisplayEntity.BlockDisplayEntity display = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);
		display.addCommandTag(HAUL_TAG);
		display.setNoGravity(true);
		display.setInvulnerable(true);
		display.setBlockState(block.state);
		display.refreshPositionAfterTeleport(position.x, position.y, position.z);
		display.setDisplayWidth(1.1F);
		display.setDisplayHeight(1.1F);
		if (!world.spawnEntity(display)) {
			return false;
		}
		block.displayId = display.getUuid();
		return true;
	}

	private static void discardDisplays(ServerWorld world, BoundHaul haul) {
		for (CarriedBlock block : haul.blocks) {
			Entity display = block.displayId == null ? null : world.getEntity(block.displayId);
			if (display != null) {
				display.discard();
			}
			block.displayId = null;
		}
	}

	private static void restoreEntityGravity(ServerWorld world, BoundHaul haul) {
		for (CarriedEntity carried : haul.entities) {
			Entity entity = world.getEntity(carried.entityId());
			if (entity != null) {
				entity.setNoGravity(carried.hadNoGravity());
			}
		}
	}

	private static void cleanupOrphanDisplays(ServerWorld world, HaulPersistentState persistent) {
		Set<UUID> live = new HashSet<>();
		for (BoundHaul haul : persistent.hauls.values()) {
			for (CarriedBlock block : haul.blocks) {
				if (block.displayId != null) {
					live.add(block.displayId);
				}
			}
		}
		for (Entity entity : world.iterateEntities()) {
			if (entity != null && entity.getCommandTags().contains(HAUL_TAG) && !live.contains(entity.getUuid())) {
				entity.discard();
			}
		}
	}

	private static HaulPersistentState state(ServerWorld world) {
		return LOADED_STATES.computeIfAbsent(world.getRegistryKey(), ignored ->
				world.getPersistentStateManager().getOrCreate(STATE_TYPE, STATE_ID));
	}

	private static void saveNow(ServerWorld world, HaulPersistentState persistent) {
		persistent.markDirty();
		world.getPersistentStateManager().save();
	}

	private static void message(PlayerEntity player, String message) {
		if (player instanceof ServerPlayerEntity serverPlayer) {
			serverPlayer.sendMessage(Text.literal(message), true);
		}
	}

	private static NbtCompound writeVec(Vec3d vec) {
		NbtCompound nbt = new NbtCompound();
		nbt.putDouble("X", vec.x);
		nbt.putDouble("Y", vec.y);
		nbt.putDouble("Z", vec.z);
		return nbt;
	}

	private static Vec3d readVec(NbtCompound nbt) {
		return new Vec3d(nbt.getDouble("X"), nbt.getDouble("Y"), nbt.getDouble("Z"));
	}

	private static final class CarriedBlock {
		private final BlockPos relativePos;
		private final Vec3d relativeDisplayPos;
		private BlockState state;
		private NbtCompound nbt;
		private UUID displayId;
		private boolean missing;

		private CarriedBlock(BlockPos relativePos, Vec3d relativeDisplayPos, BlockState state) {
			this.relativePos = relativePos;
			this.relativeDisplayPos = relativeDisplayPos;
			this.state = state;
		}

		private NbtCompound writeNbt() {
			NbtCompound data = new NbtCompound();
			data.putLong("RelativePos", relativePos.asLong());
			data.put("RelativeDisplayPos", writeVec(relativeDisplayPos));
			if (state != null) {
				data.put("State", NbtHelper.fromBlockState(state));
			}
			if (nbt != null) {
				data.put("BlockEntity", nbt.copy());
			}
			if (displayId != null) {
				data.putUuid("Display", displayId);
			}
			data.putBoolean("Missing", missing);
			return data;
		}

		private static CarriedBlock fromNbt(NbtCompound data, RegistryWrapper.WrapperLookup registries) {
			BlockState state = data.contains("State", NbtElement.COMPOUND_TYPE)
					? NbtHelper.toBlockState(registries.getWrapperOrThrow(RegistryKeys.BLOCK), data.getCompound("State"))
					: Blocks.AIR.getDefaultState();
			CarriedBlock block = new CarriedBlock(
					BlockPos.fromLong(data.getLong("RelativePos")),
					readVec(data.getCompound("RelativeDisplayPos")),
					state);
			block.nbt = data.contains("BlockEntity", NbtElement.COMPOUND_TYPE)
					? data.getCompound("BlockEntity").copy()
					: null;
			block.displayId = data.containsUuid("Display") ? data.getUuid("Display") : null;
			block.missing = data.getBoolean("Missing");
			return block;
		}
	}

	private record CarriedEntity(UUID entityId, Vec3d relativePos, boolean hadNoGravity) {
		private NbtCompound writeNbt() {
			NbtCompound data = new NbtCompound();
			data.putUuid("Entity", entityId);
			data.put("RelativePos", writeVec(relativePos));
			data.putBoolean("HadNoGravity", hadNoGravity);
			return data;
		}

		private static CarriedEntity fromNbt(NbtCompound data) {
			return new CarriedEntity(
					data.getUuid("Entity"),
					readVec(data.getCompound("RelativePos")),
					data.getBoolean("HadNoGravity"));
		}
	}

	private static final class BoundHaul {
		private final UUID groupId;
		private final RegistryKey<World> worldKey;
		private final BlockPos sourceAnchorBlock;
		private final Vec3d sourceAnchorPos;
		private final List<CarriedBlock> blocks;
		private final List<CarriedEntity> entities;
		private boolean pickedUp;
		private BlockPos destinationAnchorBlock;
		private transient boolean recoveryPrepared;
		private transient int missingGroupTicks;

		private BoundHaul(
				UUID groupId,
				RegistryKey<World> worldKey,
				BlockPos sourceAnchorBlock,
				Vec3d sourceAnchorPos,
				List<CarriedBlock> blocks,
				List<CarriedEntity> entities) {
			this.groupId = groupId;
			this.worldKey = worldKey;
			this.sourceAnchorBlock = sourceAnchorBlock;
			this.sourceAnchorPos = sourceAnchorPos;
			this.blocks = blocks;
			this.entities = entities;
		}

		private NbtCompound writeNbt() {
			NbtCompound data = new NbtCompound();
			data.putUuid("Group", groupId);
			data.putString("World", worldKey.getValue().toString());
			data.putLong("SourceAnchorBlock", sourceAnchorBlock.asLong());
			data.put("SourceAnchorPos", writeVec(sourceAnchorPos));
			data.putBoolean("PickedUp", pickedUp);
			if (destinationAnchorBlock != null) {
				data.putLong("DestinationAnchorBlock", destinationAnchorBlock.asLong());
			}

			NbtList blockList = new NbtList();
			for (CarriedBlock block : blocks) {
				blockList.add(block.writeNbt());
			}
			data.put("Blocks", blockList);

			NbtList entityList = new NbtList();
			for (CarriedEntity entity : entities) {
				entityList.add(entity.writeNbt());
			}
			data.put("Entities", entityList);
			return data;
		}

		private static BoundHaul fromNbt(NbtCompound data, RegistryWrapper.WrapperLookup registries) {
			Identifier worldId = Identifier.tryParse(data.getString("World"));
			if (worldId == null || !data.containsUuid("Group")) {
				return null;
			}
			List<CarriedBlock> blocks = new ArrayList<>();
			NbtList blockList = data.getList("Blocks", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < blockList.size(); i++) {
				blocks.add(CarriedBlock.fromNbt(blockList.getCompound(i), registries));
			}
			List<CarriedEntity> entities = new ArrayList<>();
			NbtList entityList = data.getList("Entities", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < entityList.size(); i++) {
				NbtCompound entityData = entityList.getCompound(i);
				if (entityData.containsUuid("Entity")) {
					entities.add(CarriedEntity.fromNbt(entityData));
				}
			}
			BoundHaul haul = new BoundHaul(
					data.getUuid("Group"),
					RegistryKey.of(RegistryKeys.WORLD, worldId),
					BlockPos.fromLong(data.getLong("SourceAnchorBlock")),
					readVec(data.getCompound("SourceAnchorPos")),
					blocks,
					entities);
			haul.pickedUp = data.getBoolean("PickedUp");
			haul.destinationAnchorBlock = data.contains("DestinationAnchorBlock", NbtElement.LONG_TYPE)
					? BlockPos.fromLong(data.getLong("DestinationAnchorBlock"))
					: null;
			return haul;
		}
	}

	private static final class HaulPersistentState extends PersistentState {
		private final Map<UUID, BoundHaul> hauls = new HashMap<>();

		private static HaulPersistentState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
			HaulPersistentState state = new HaulPersistentState();
			NbtList list = nbt.getList("Hauls", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < list.size(); i++) {
				try {
					BoundHaul haul = BoundHaul.fromNbt(list.getCompound(i), registries);
					if (haul != null) {
						state.hauls.put(haul.groupId, haul);
					}
				} catch (RuntimeException exception) {
					LOGGER.error("Failed to load a persisted EchoMinecart haul", exception);
				}
			}
			return state;
		}

		@Override
		public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
			NbtList list = new NbtList();
			for (BoundHaul haul : hauls.values()) {
				list.add(haul.writeNbt());
			}
			nbt.put("Hauls", list);
			return nbt;
		}
	}
}
