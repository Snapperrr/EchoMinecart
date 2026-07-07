package com.xc.echominecart.haul;

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
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 搬运绑定：把车厢组每个座位格正上方的方块列和范围内实体绑定成一票货物。
 * 车厢真正开动后才拾取（方块换成随车移动的 BlockDisplay，原方块暂时移除），
 * 停稳一段时间后按相对位置放回；只要有一个目标位置被占用或放置条件不满足，
 * 整批货物都拒绝放下，继续跟车等待下一次停稳。
 */
public final class HaulManager {
	private static final int MAX_HAUL_BLOCKS = 768;
	private static final int MAX_HAUL_HEIGHT = 48;
	private static final int STOP_TICKS_TO_PLACE = 40;
	private static final double PICKUP_START_DISTANCE = 0.35D;
	private static final double VISUAL_VELOCITY_LAG = 0.35D;
	private static final String HAUL_TAG = "echominecart_haul_visual";
	private static final Direction[] ALL_DIRECTIONS = Direction.values();

	private static final Map<UUID, BoundHaul> HAULS = new HashMap<>();
	private static int cleanupTick;

	private HaulManager() {
	}

	public static void bind(PlayerEntity player, GroupSnapshot snapshot) {
		if (snapshot == null || snapshot.shape() == null || snapshot.anchor() == null
				|| !(snapshot.anchor().getWorld() instanceof ServerWorld world)) {
			return;
		}
		BoundHaul haul = scan(world, snapshot);
		if (haul.blocks.isEmpty() && haul.entities.isEmpty()) {
			message(player, "扩充车厢上方没有找到可搬运的方块或实体。");
			return;
		}
		BoundHaul old = HAULS.put(snapshot.groupId(), haul);
		if (old != null) {
			discardDisplays(world, old);
		}
		message(player, "已绑定 " + haul.blocks.size() + " 个方块和 " + haul.entities.size() + " 个实体。车厢开始移动时才会拾取建筑。");
	}

	public static void tick(ServerWorld world, List<GroupSnapshot> snapshots) {
		Map<UUID, GroupSnapshot> byGroup = new HashMap<>();
		for (GroupSnapshot snapshot : snapshots) {
			byGroup.put(snapshot.groupId(), snapshot);
		}
		for (BoundHaul haul : List.copyOf(HAULS.values())) {
			if (!haul.worldKey.equals(world.getRegistryKey())) {
				continue;
			}
			GroupSnapshot snapshot = byGroup.get(haul.groupId);
			if (snapshot == null || snapshot.anchor() == null || snapshot.anchor().getWorld() != world) {
				emergencyRelease(world, haul);
				HAULS.remove(haul.groupId);
				continue;
			}
			AbstractMinecartEntity anchor = snapshot.anchor();
			if (!haul.pickedUp) {
				if (anchor.getPos().squaredDistanceTo(haul.sourceAnchorPos) < PICKUP_START_DISTANCE * PICKUP_START_DISTANCE) {
					continue;
				}
				pickup(world, haul);
			}
			move(world, haul, anchor);
			if (snapshot.stillTicks() >= STOP_TICKS_TO_PLACE && tryPlace(world, anchor, haul)) {
				HAULS.remove(haul.groupId);
			}
		}
		if (++cleanupTick % 200 == 0) {
			cleanupOrphanDisplays(world);
		}
	}

	public static void releaseGroup(ServerWorld world, UUID groupId) {
		BoundHaul haul = HAULS.remove(groupId);
		if (haul != null && haul.worldKey.equals(world.getRegistryKey())) {
			emergencyRelease(world, haul);
		}
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
				blocks.add(new CarriedBlock(pos.subtract(anchorBlock), new Vec3d(pos.getX(), pos.getY(), pos.getZ()).subtract(anchorPos), state));
			}
		}
		Box entityBox = shape.bounds().expand(0.75D, MAX_HAUL_HEIGHT, 0.75D).offset(0.0D, MAX_HAUL_HEIGHT / 2.0D, 0.0D);
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
		return new BoundHaul(snapshot.groupId(), world.getRegistryKey(), anchorBlock, snapshot.anchor().getPos(), blocks, entities);
	}

	private static void pickup(ServerWorld world, BoundHaul haul) {
		for (CarriedBlock block : haul.blocks) {
			BlockPos source = haul.sourceAnchorBlock.add(block.relativePos);
			BlockState current = world.getBlockState(source);
			if (current.isAir()) {
				block.missing = true;
				continue;
			}
			block.state = current;
			BlockEntity blockEntity = world.getBlockEntity(source);
			block.nbt = blockEntity == null ? null : blockEntity.createNbtWithIdentifyingData(world.getRegistryManager());
			if (blockEntity instanceof Inventory inventory) {
				inventory.clear();
				blockEntity.markDirty();
			}
			DisplayEntity.BlockDisplayEntity display = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);
			display.addCommandTag(HAUL_TAG);
			display.setNoGravity(true);
			display.setInvulnerable(true);
			display.setBlockState(current);
			display.refreshPositionAfterTeleport(source.getX(), source.getY(), source.getZ());
			display.setDisplayWidth(1.1F);
			display.setDisplayHeight(1.1F);
			world.spawnEntity(display);
			block.displayId = display.getUuid();
			NestedChestMod.beginSuppressChestDrops(world, source);
			try {
				world.setBlockState(source, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
			} finally {
				NestedChestMod.endSuppressChestDrops(world, source);
			}
		}
		for (CarriedEntity carried : haul.entities) {
			Entity entity = world.getEntity(carried.entityId());
			if (entity != null) {
				entity.setNoGravity(true);
			}
		}
		haul.blocks.removeIf(block -> block.missing);
		haul.pickedUp = true;
	}

	private static void move(ServerWorld world, BoundHaul haul, AbstractMinecartEntity anchor) {
		BlockPos anchorBlock = anchor.getBlockPos();
		Vec3d anchorPos = anchor.getPos();
		Vec3d visualAnchorPos = anchorPos.subtract(anchor.getVelocity().multiply(VISUAL_VELOCITY_LAG));
		for (CarriedBlock block : haul.blocks) {
			Entity display = block.displayId == null ? null : world.getEntity(block.displayId);
			if (display != null) {
				Vec3d target = visualAnchorPos.add(block.relativeDisplayPos);
				display.refreshPositionAfterTeleport(target.x, target.y, target.z);
			}
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

	private static boolean tryPlace(ServerWorld world, AbstractMinecartEntity anchor, BoundHaul haul) {
		BlockPos anchorBlock = anchor.getBlockPos();
		if (!canRestoreAll(world, anchorBlock, haul.blocks) || !restoreCarriedBlocks(world, anchorBlock, haul, false)) {
			return false;
		}
		discardDisplays(world, haul);
		for (CarriedEntity carried : haul.entities) {
			Entity entity = world.getEntity(carried.entityId());
			if (entity != null) {
				entity.setNoGravity(carried.hadNoGravity());
			}
		}
		return true;
	}

	private static void emergencyRelease(ServerWorld world, BoundHaul haul) {
		discardDisplays(world, haul);
		for (CarriedBlock block : haul.blocks) {
			if (!haul.pickedUp || block.state == null || block.state.isAir()) {
				block.missing = true;
			}
		}
		if (haul.pickedUp) {
			restoreCarriedBlocks(world, haul.sourceAnchorBlock, haul, true);
		}
		for (CarriedEntity carried : haul.entities) {
			Entity entity = world.getEntity(carried.entityId());
			if (entity != null) {
				entity.setNoGravity(carried.hadNoGravity());
			}
		}
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
		// 依赖支撑的方块（火把、门等）如果邻格也是本批货物，放置后互为支撑。
		for (Direction direction : ALL_DIRECTIONS) {
			if (carriedTargets.contains(target.offset(direction))) {
				return true;
			}
		}
		return false;
	}

	private static void discardDisplays(ServerWorld world, BoundHaul haul) {
		for (CarriedBlock block : haul.blocks) {
			Entity display = block.displayId == null ? null : world.getEntity(block.displayId);
			if (display != null) {
				display.discard();
			}
		}
	}

	private static void cleanupOrphanDisplays(ServerWorld world) {
		Set<UUID> live = new HashSet<>();
		for (BoundHaul haul : HAULS.values()) {
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

	private static void message(PlayerEntity player, String message) {
		if (player instanceof ServerPlayerEntity serverPlayer) {
			serverPlayer.sendMessage(Text.literal(message), true);
		}
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
	}

	private record CarriedEntity(UUID entityId, Vec3d relativePos, boolean hadNoGravity) {
	}

	private static final class BoundHaul {
		private final UUID groupId;
		private final RegistryKey<World> worldKey;
		private final BlockPos sourceAnchorBlock;
		private final Vec3d sourceAnchorPos;
		private final List<CarriedBlock> blocks;
		private final List<CarriedEntity> entities;
		private boolean pickedUp;

		private BoundHaul(UUID groupId, RegistryKey<World> worldKey, BlockPos sourceAnchorBlock, Vec3d sourceAnchorPos, List<CarriedBlock> blocks, List<CarriedEntity> entities) {
			this.groupId = groupId;
			this.worldKey = worldKey;
			this.sourceAnchorBlock = sourceAnchorBlock;
			this.sourceAnchorPos = sourceAnchorPos;
			this.blocks = blocks;
			this.entities = entities;
		}
	}
}
