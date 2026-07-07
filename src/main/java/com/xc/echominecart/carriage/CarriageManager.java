package com.xc.echominecart.carriage;

import com.xc.echominecart.haul.HaulManager;
import com.xc.echominecart.mixin.DisplayEntityAccessor;
import com.xc.echominecart.network.CarriageSyncPayload;
import com.xc.echominecart.rail.OmniRailBlock;
import com.xc.echominecart.rail.RailPhysics;
import com.xc.echominecart.trip.TripManager;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.RailShape;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 扩充车厢：用拆分/合并工具连接的矿车组成一节车厢（并查集），
 * 经过多分叉铁轨时按分支拉伸车体，座位数随覆盖格数变化，
 * 移动中撞到的生物自动入座、缩短时甩出超员乘客。
 *
 * 连接关系只有两类：
 * - MANUAL：拆分/合并工具建立的链接（随矿车 NBT 持久化）；
 * - RUNNER：分支探针车与母车的隐式链接。
 * 矿车不再因为靠近而自动合并。
 */
public final class CarriageManager {
	private static final double MANUAL_LINK_RANGE = 4.5D;
	private static final double CART_STOP_SPEED = 0.03D;
	private static final double PICKUP_MIN_SPEED = 0.06D;
	private static final int MAX_CARRIAGE_SEATS = 96;
	private static final int JUNCTION_STRETCH_TICKS = 10;
	public static final String CHEST_VISUAL_TAG = "echominecart_chest_visual";
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	private static final Map<UUID, UUID> PLAYER_SELECTIONS = new HashMap<>();
	private static final Set<CartPair> MANUAL_LINKS = new HashSet<>();
	private static final Map<UUID, UUID> CART_TO_GROUP = new HashMap<>();
	private static final Map<UUID, CartGroup> GROUPS = new HashMap<>();
	private static final Map<UUID, List<UUID>> CHEST_DISPLAYS = new HashMap<>();
	private static int serverTick;

	private CarriageManager() {
	}

	// ---------------------------------------------------------------- tick

	public static void serverTick(MinecraftServer server) {
		serverTick++;
		CART_TO_GROUP.clear();
		Set<UUID> liveCartIds = new HashSet<>();
		for (ServerWorld world : server.getWorlds()) {
			List<AbstractMinecartEntity> carts = collectCarts(world);
			Map<UUID, AbstractMinecartEntity> byId = new HashMap<>();
			for (AbstractMinecartEntity cart : carts) {
				byId.put(cart.getUuid(), cart);
			}
			liveCartIds.addAll(byId.keySet());
			Map<UUID, CartGroup> rebuilt = rebuildGroups(world, carts, byId);
			discardStaleGroups(world, rebuilt.keySet());
			GROUPS.putAll(rebuilt);
			TripManager.tick(world, serverTick);
			if (serverTick % 100 == 0) {
				JunctionSplitter.purgeOrphans(world);
				purgeOrphanChestDisplays(world);
			}
			List<GroupSnapshot> snapshots = new ArrayList<>();
			for (CartGroup group : rebuilt.values()) {
				tickGroup(world, group, byId);
				snapshots.add(snapshotOf(group, byId));
			}
			HaulManager.tick(world, snapshots);
		}
		// 跨维度统一清理，避免逐世界 retainAll 误删其他维度矿车的状态。
		RailPhysics.forgetCarts(liveCartIds);
		JunctionSplitter.forgetCarts(liveCartIds);
		if (serverTick % 100 == 0) {
			for (UUID anchorId : List.copyOf(CHEST_DISPLAYS.keySet())) {
				if (!liveCartIds.contains(anchorId)) {
					for (ServerWorld world : server.getWorlds()) {
						discardChestDisplays(world, anchorId);
					}
				}
			}
		}
	}

	private static void tickGroup(ServerWorld world, CartGroup group, Map<UUID, AbstractMinecartEntity> byId) {
		List<AbstractMinecartEntity> carts = cartsOf(group, byId);
		if (carts.isEmpty() || group.shape == null) {
			return;
		}
		updateJunctionStretch(world, carts);
		syncExpandedMotion(group, carts);
		updateModules(group, carts);
		JunctionSplitter.tickGroup(world, group.carts, carts, serverTick);
		syncShapeToClients(group, carts);
		updateChestDisplays(world, group, carts);
		consolidatePassengers(group, carts);
		pickupCollidingEntities(world, group, carts);
		ejectOverflowPassengers(group);
		double maxSpeed = 0.0D;
		for (AbstractMinecartEntity cart : carts) {
			maxSpeed = Math.max(maxSpeed, cart.getVelocity().length());
		}
		group.stillTicks = maxSpeed < CART_STOP_SPEED ? group.stillTicks + 1 : 0;
	}

	// ------------------------------------------------------------- queries

	public static boolean isSuppressedModule(AbstractMinecartEntity cart) {
		CartAttachment attachment = CartAttachment.peek(cart.getUuid());
		return attachment != null && attachment.suppressedAsModule;
	}

	/** 幽灵车体：刚体模块和分支探针都不参与碰撞、不可点击、不可推动。 */
	public static boolean isPhantomBody(AbstractMinecartEntity cart) {
		return isSuppressedModule(cart) || JunctionSplitter.isRunner(cart.getUuid());
	}

	public static Optional<Boolean> passengerAdmission(AbstractMinecartEntity cart) {
		CartGroup group = groupOf(cart);
		if (group == null || group.shape == null || !isExpanded(group)) {
			return Optional.empty();
		}
		if (!cart.getUuid().equals(group.shape.anchorCart())) {
			return Optional.of(false);
		}
		return Optional.of(passengerCount(group) < Math.max(1, passengerCapacity(group)));
	}

	public static Optional<Vec3d> passengerOffset(AbstractMinecartEntity cart, Entity passenger) {
		CartGroup group = groupOf(cart);
		if (group == null || group.shape == null || group.shape.seats().isEmpty()) {
			return Optional.empty();
		}
		if (isExpanded(group) && !cart.getUuid().equals(group.shape.anchorCart())) {
			return Optional.empty();
		}
		// 乘客从头部座位（锚点格）坐起，箱子占尾部座位。
		int passengerIndex = Math.max(0, passengerIndex(group, passenger));
		int seatIndex = Math.min(group.shape.seats().size() - 1, passengerIndex);
		// 座位偏移必须是"相对矿车"的整格向量：用 seatCenter − cart.getPos() 会把乘客
		// 钉在世界格子中心，矿车连续移动时乘客逐格弹跳（骑乘卡顿的根源）。
		BlockPos seatCell = group.shape.seats().get(seatIndex);
		BlockPos anchorCell = group.shape.anchorBlock();
		Vec3d relative = new Vec3d(
				seatCell.getX() - anchorCell.getX(),
				seatCell.getY() - anchorCell.getY(),
				seatCell.getZ() - anchorCell.getZ());
		return Optional.of(relative.add(seatLift(cart)));
	}

	// ------------------------------------------------------- chest carriage

	public static boolean attachChest(PlayerEntity player, AbstractMinecartEntity minecart) {
		CartGroup group = ensureGroup(minecart);
		if (group == null) {
			return false;
		}
		int freeSlots = Math.max(0, totalSeatSlots(group) - group.attachedChests - passengerCount(group));
		if (freeSlots <= 0) {
			message(player, "扩充车厢已经没有空位可以加装箱子。");
			return false;
		}
		AbstractMinecartEntity target = cartsOf(group, collectCartMap(group.world)).stream()
				.min(Comparator.comparingInt(cart -> CartAttachment.of(cart).attachedChests))
				.orElse(minecart);
		CartAttachment.of(target).addChestPage();
		group.attachedChests++;
		message(player, "已在扩充车厢上加装箱子；潜行空手右键任意车体可打开连接箱页面。");
		return true;
	}

	public static boolean openGroupChest(PlayerEntity player, AbstractMinecartEntity minecart) {
		if (!(minecart.getWorld() instanceof ServerWorld)) {
			return false;
		}
		CartGroup group = ensureGroup(minecart);
		if (group == null) {
			return false;
		}
		List<AbstractMinecartEntity> chestCarts = cartsOf(group, collectCartMap(group.world)).stream()
				.filter(cart -> CartAttachment.of(cart).attachedChests > 0)
				.sorted(Comparator.comparing(Entity::getUuid))
				.toList();
		int chestCount = 0;
		for (AbstractMinecartEntity cart : chestCarts) {
			CartAttachment attachment = CartAttachment.of(cart);
			attachment.normalize();
			chestCount += attachment.attachedChests;
		}
		if (chestCount <= 0) {
			return false;
		}
		player.openHandledScreen(new CartChestScreenHandlerFactory(new CartGroupChestInventory(chestCarts), chestCount));
		return true;
	}

	public static void dropAttachedChests(AbstractMinecartEntity cart) {
		if (!(cart.getWorld() instanceof ServerWorld world)) {
			return;
		}
		// 分支探针被吸收是常态操作，绝不能触发整组的搬运释放/箱子显示重建。
		// 用命令标签判断而不是注册表：absorb 的移除顺序可能先于本回调。
		if (cart.getCommandTags().contains(JunctionSplitter.RUNNER_TAG)) {
			CartAttachment.remove(cart.getUuid());
			return;
		}
		CartGroup group = groupOf(cart);
		if (group != null) {
			HaulManager.releaseGroup(world, group.id);
			if (group.shape != null) {
				discardChestDisplays(world, group.shape.anchorCart());
			}
		}
		discardChestDisplays(world, cart.getUuid());
		MANUAL_LINKS.removeIf(pair -> pair.left().equals(cart.getUuid()) || pair.right().equals(cart.getUuid()));
		CartAttachment attachment = CartAttachment.peek(cart.getUuid());
		if (attachment == null || attachment.attachedChests <= 0) {
			CartAttachment.remove(cart.getUuid());
			return;
		}
		for (ItemStack stack : attachment.chestStacks) {
			if (!stack.isEmpty()) {
				ItemScatterer.spawn(world, cart.getX(), cart.getY(), cart.getZ(), stack);
			}
		}
		for (int i = 0; i < attachment.attachedChests; i++) {
			ItemScatterer.spawn(world, cart.getX(), cart.getY(), cart.getZ(), new ItemStack(Items.CHEST));
		}
		attachment.clearChests();
		CartAttachment.remove(cart.getUuid());
	}

	// ------------------------------------------------------------ link tool

	public static void useLinkTool(PlayerEntity player, AbstractMinecartEntity minecart) {
		UUID playerId = player.getUuid();
		UUID current = minecart.getUuid();
		UUID selected = PLAYER_SELECTIONS.remove(playerId);
		if (selected == null || selected.equals(current)) {
			PLAYER_SELECTIONS.put(playerId, current);
			message(player, "已选中第一个矿车轮组。");
			return;
		}
		CartPair pair = CartPair.of(selected, current);
		CartGroup selectedGroup = groupOf(selected);
		CartGroup currentGroup = groupOf(current);
		if (selectedGroup != null && selectedGroup == currentGroup) {
			MANUAL_LINKS.remove(pair);
			splitChestLoad(selected, current);
			message(player, "已切开这两个轮组之间的车厢连接。");
			return;
		}
		Entity other = minecart.getWorld() instanceof ServerWorld world ? world.getEntity(selected) : null;
		if (other == null || other.squaredDistanceTo(minecart) > MANUAL_LINK_RANGE * MANUAL_LINK_RANGE) {
			message(player, "两个轮组距离太远，靠近到 4 格以内再合并。");
			return;
		}
		MANUAL_LINKS.add(pair);
		message(player, "这两个轮组已合并为同一节车厢。");
	}

	private static void splitChestLoad(UUID left, UUID right) {
		CartAttachment leftData = CartAttachment.of(left);
		CartAttachment rightData = CartAttachment.of(right);
		int total = leftData.attachedChests + rightData.attachedChests;
		if (total <= 1 || (leftData.attachedChests > 0 && rightData.attachedChests > 0)) {
			return;
		}
		// 箱子全在一侧时对半分，内容按页跟随，切开后两段各自保留自己的物品。
		CartAttachment source = leftData.attachedChests > 0 ? leftData : rightData;
		CartAttachment sink = source == leftData ? rightData : leftData;
		source.movePagesTo(sink, total / 2);
	}

	// ------------------------------------------------------------ transport

	public static GroupSnapshot snapshotFor(AbstractMinecartEntity minecart) {
		CartGroup group = ensureGroup(minecart);
		if (group == null) {
			return null;
		}
		return snapshotOf(group, collectCartMap(group.world));
	}

	// ---------------------------------------------------------- persistence

	public static void writeCartNbt(AbstractMinecartEntity cart, NbtCompound nbt) {
		CartAttachment attachment = CartAttachment.peek(cart.getUuid());
		if (attachment != null) {
			attachment.writeNbt(nbt, cart.getRegistryManager());
		}
		NbtList linkedPartners = new NbtList();
		for (CartPair pair : MANUAL_LINKS) {
			if (pair.left().equals(cart.getUuid())) {
				linkedPartners.add(NbtHelper.fromUuid(pair.right()));
			} else if (pair.right().equals(cart.getUuid())) {
				linkedPartners.add(NbtHelper.fromUuid(pair.left()));
			}
		}
		if (!linkedPartners.isEmpty()) {
			nbt.put("EchoManualLinks", linkedPartners);
		}
	}

	public static void readCartNbt(AbstractMinecartEntity cart, NbtCompound nbt) {
		CartAttachment.of(cart).readNbt(nbt, cart.getRegistryManager());
		if (nbt.contains("EchoManualLinks", NbtElement.LIST_TYPE)) {
			for (NbtElement element : nbt.getList("EchoManualLinks", NbtElement.INT_ARRAY_TYPE)) {
				MANUAL_LINKS.add(CartPair.of(cart.getUuid(), NbtHelper.toUuid(element)));
			}
		}
	}

	// -------------------------------------------------------- group building

	private static List<AbstractMinecartEntity> collectCarts(ServerWorld world) {
		List<AbstractMinecartEntity> carts = new ArrayList<>();
		for (Entity entity : world.iterateEntities()) {
			if (entity == null) {
				continue;
			}
			if (entity instanceof AbstractMinecartEntity minecart && !minecart.isRemoved()) {
				carts.add(minecart);
				CartAttachment.of(minecart);
			}
		}
		return carts;
	}

	private static Map<UUID, AbstractMinecartEntity> collectCartMap(ServerWorld world) {
		Map<UUID, AbstractMinecartEntity> map = new HashMap<>();
		for (AbstractMinecartEntity cart : collectCarts(world)) {
			map.put(cart.getUuid(), cart);
		}
		return map;
	}

	private static Map<UUID, CartGroup> rebuildGroups(ServerWorld world, List<AbstractMinecartEntity> carts, Map<UUID, AbstractMinecartEntity> byId) {
		Map<UUID, UUID> parent = new HashMap<>();
		for (AbstractMinecartEntity cart : carts) {
			parent.put(cart.getUuid(), cart.getUuid());
		}
		// 分支探针始终归属母车所在的组。
		JunctionSplitter.links().forEach((runner, source) -> {
			if (parent.containsKey(runner) && parent.containsKey(source)) {
				union(parent, source, runner);
			}
		});
		// 只认工具建立的手动链接，矿车不再因靠近自动合并。
		for (CartPair pair : MANUAL_LINKS) {
			if (parent.containsKey(pair.left()) && parent.containsKey(pair.right())) {
				union(parent, pair.left(), pair.right());
			}
		}

		Map<UUID, CartGroup> rebuilt = new HashMap<>();
		for (AbstractMinecartEntity cart : carts) {
			UUID root = find(parent, cart.getUuid());
			CartGroup group = rebuilt.computeIfAbsent(root, id -> new CartGroup(id, world));
			group.carts.add(cart.getUuid());
			group.attachedChests += CartAttachment.of(cart).attachedChests;
			CART_TO_GROUP.put(cart.getUuid(), root);
		}
		for (CartGroup group : rebuilt.values()) {
			CartGroup previous = GROUPS.get(group.id);
			if (previous != null) {
				group.stillTicks = previous.stillTicks;
			}
			group.shape = buildShape(group, cartsOf(group, byId));
		}
		return rebuilt;
	}

	private static void discardStaleGroups(ServerWorld world, Set<UUID> liveGroupIds) {
		GROUPS.values().removeIf(group -> {
			if (group.world != world || liveGroupIds.contains(group.id)) {
				return false;
			}
			if (group.shape != null) {
				discardChestDisplays(world, group.shape.anchorCart());
			}
			return true;
		});
	}

	private static CartGroup ensureGroup(AbstractMinecartEntity minecart) {
		CartGroup group = groupOf(minecart);
		if (group != null) {
			if (group.shape == null) {
				group.shape = buildShape(group, cartsOf(group, collectCartMap(group.world)));
			}
			return group;
		}
		if (!(minecart.getWorld() instanceof ServerWorld world)) {
			return null;
		}
		CartGroup created = new CartGroup(minecart.getUuid(), world);
		created.carts.add(minecart.getUuid());
		created.attachedChests = CartAttachment.of(minecart).attachedChests;
		created.shape = buildShape(created, List.of(minecart));
		GROUPS.put(created.id, created);
		CART_TO_GROUP.put(minecart.getUuid(), created.id);
		return created;
	}

	// -------------------------------------------------------- shape building

	private static CarriageShape buildShape(CartGroup group, List<AbstractMinecartEntity> carts) {
		AbstractMinecartEntity anchor = chooseAnchor(carts);
		if (anchor == null) {
			return null;
		}
		LinkedHashSet<BlockPos> cells = new LinkedHashSet<>();
		for (AbstractMinecartEntity cart : carts) {
			if (JunctionSplitter.isRunner(cart.getUuid())) {
				cells.add(cart.getBlockPos());
				continue;
			}
			cells.add(moduleCell(anchor, cart, carts.size() > 1, cells));
			railPosNear(group.world, cart.getBlockPos()).ifPresent(rail -> {
				if (CartAttachment.of(cart).junctionStretchTicks > 0 && isJunction(group.world, rail)) {
					cells.add(rail);
					cells.addAll(railNeighbors(group.world, rail));
				}
			});
		}
		// 分支探针的轨迹格：车体沿每条分支像树枝一样拉伸。
		cells.addAll(JunctionSplitter.trailCells(group.carts));
		if (cells.isEmpty()) {
			cells.add(anchor.getBlockPos());
		}
		int minX = cells.stream().mapToInt(BlockPos::getX).min().orElse(anchor.getBlockX());
		int minY = cells.stream().mapToInt(BlockPos::getY).min().orElse(anchor.getBlockY());
		int minZ = cells.stream().mapToInt(BlockPos::getZ).min().orElse(anchor.getBlockZ());
		int maxX = cells.stream().mapToInt(BlockPos::getX).max().orElse(anchor.getBlockX());
		int maxY = cells.stream().mapToInt(BlockPos::getY).max().orElse(anchor.getBlockY());
		int maxZ = cells.stream().mapToInt(BlockPos::getZ).max().orElse(anchor.getBlockZ());
		// 座位只放在真实覆盖格上（车体、轨道、探针轨迹），不做包围盒填充——
		// L 形合并时包围盒的空角会产生悬空座位。
		List<BlockPos> seats = new ArrayList<>(cells.stream().limit(MAX_CARRIAGE_SEATS).toList());
		Vec3d forward = forwardOf(carts);
		Vec3d anchorPos = anchor.getPos();
		// 座位排序必须与速度方向无关：按 forward 排序会在速度抖动时整表重排，
		// 箱子显示和乘客座位每 tick 换格子（乱跳的根源）。锚点格永远排第一，
		// 其余按世界坐标确定性排序。
		BlockPos anchorCell = anchor.getBlockPos();
		seats.sort(Comparator
				.comparingInt((BlockPos pos) -> pos.equals(anchorCell) ? 0 : 1)
				.thenComparingInt(BlockPos::getY)
				.thenComparingInt(BlockPos::getX)
				.thenComparingInt(BlockPos::getZ));
		List<Box> bodyBoxes = seats.stream().map(CarriageManager::bodyBoxFor).toList();
		Box bounds = unionOf(bodyBoxes)
				.orElse(new Box(minX, minY, minZ, maxX + 1.0D, maxY + 1.35D, maxZ + 1.0D))
				.expand(0.12D);
		Box impactBounds = bounds.expand(0.08D, 0.05D, 0.08D);
		return new CarriageShape(anchor.getUuid(), anchor.getBlockPos(), anchorPos, seats, bodyBoxes, bounds, impactBounds, forward);
	}

	/** 锚点选举：按已存锚点多数投票（平票取小 UUID），避免新轮组加入时锚点翻转；探针车不参选。 */
	private static AbstractMinecartEntity chooseAnchor(List<AbstractMinecartEntity> allCarts) {
		List<AbstractMinecartEntity> nonRunners = allCarts.stream()
				.filter(cart -> !JunctionSplitter.isRunner(cart.getUuid()))
				.toList();
		List<AbstractMinecartEntity> carts = nonRunners.isEmpty() ? allCarts : nonRunners;
		if (carts.isEmpty()) {
			return null;
		}
		Map<UUID, AbstractMinecartEntity> byId = new HashMap<>();
		for (AbstractMinecartEntity cart : carts) {
			byId.put(cart.getUuid(), cart);
		}
		Map<UUID, Integer> votes = new HashMap<>();
		for (AbstractMinecartEntity cart : carts) {
			UUID anchorId = CartAttachment.of(cart).anchorCart;
			if (anchorId != null && byId.containsKey(anchorId)) {
				votes.merge(anchorId, 1, Integer::sum);
			}
		}
		return votes.entrySet().stream()
				.max(Comparator.<Map.Entry<UUID, Integer>>comparingInt(Map.Entry::getValue)
						.thenComparing(entry -> entry.getKey(), Comparator.reverseOrder()))
				.map(entry -> byId.get(entry.getKey()))
				.orElseGet(() -> carts.stream().min(Comparator.comparing(Entity::getUuid)).orElse(carts.getFirst()));
	}

	private static BlockPos moduleCell(AbstractMinecartEntity anchor, AbstractMinecartEntity cart, boolean expanded, LinkedHashSet<BlockPos> occupied) {
		CartAttachment attachment = CartAttachment.of(cart);
		if (!expanded || cart.getUuid().equals(anchor.getUuid())) {
			attachment.anchorCart = cart.getUuid().equals(anchor.getUuid()) ? anchor.getUuid() : cart.getUuid();
			attachment.moduleOffset = BlockPos.ORIGIN;
			return cart.getBlockPos();
		}
		if (!anchor.getUuid().equals(attachment.anchorCart) || attachment.moduleOffset == null) {
			attachment.anchorCart = anchor.getUuid();
			attachment.moduleOffset = cart.getBlockPos().subtract(anchor.getBlockPos());
		}
		BlockPos desired = anchor.getBlockPos().add(attachment.moduleOffset);
		if (!occupied.contains(desired)) {
			return desired;
		}
		BlockPos unique = findFreeCell(anchor.getBlockPos(), occupied);
		attachment.moduleOffset = unique.subtract(anchor.getBlockPos());
		return unique;
	}

	private static BlockPos findFreeCell(BlockPos anchorPos, Set<BlockPos> occupied) {
		for (int radius = 1; radius <= 8; radius++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
						continue;
					}
					BlockPos candidate = anchorPos.add(dx, 0, dz);
					if (!occupied.contains(candidate)) {
						return candidate;
					}
				}
			}
		}
		return anchorPos.add(occupied.size(), 0, 0);
	}

	private static Box bodyBoxFor(BlockPos pos) {
		return new Box(
				pos.getX() + 0.08D, pos.getY() + 0.05D, pos.getZ() + 0.08D,
				pos.getX() + 0.92D, pos.getY() + 1.05D, pos.getZ() + 0.92D);
	}

	private static Optional<Box> unionOf(Collection<Box> boxes) {
		Box union = null;
		for (Box box : boxes) {
			union = union == null ? box : union.union(box);
		}
		return Optional.ofNullable(union);
	}

	// ------------------------------------------------------- junction stretch

	private static void updateJunctionStretch(ServerWorld world, List<AbstractMinecartEntity> carts) {
		for (AbstractMinecartEntity cart : carts) {
			CartAttachment attachment = CartAttachment.of(cart);
			boolean moving = cart.getVelocity().lengthSquared() > 0.0025D;
			Optional<BlockPos> rail = railPosNear(world, cart.getBlockPos());
			if (moving && rail.isPresent() && isJunction(world, rail.get())) {
				attachment.junctionStretchTicks = JUNCTION_STRETCH_TICKS;
			} else if (attachment.junctionStretchTicks > 0) {
				attachment.junctionStretchTicks--;
			}
		}
	}

	private static boolean isJunction(ServerWorld world, BlockPos railPos) {
		BlockState state = world.getBlockState(railPos);
		if (OmniRailBlock.isOmniRail(state) && OmniRailBlock.connections(state).size() > 2) {
			return true;
		}
		return railNeighbors(world, railPos).size() > 2;
	}

	private static Set<BlockPos> railNeighbors(ServerWorld world, BlockPos pos) {
		Set<BlockPos> positions = new LinkedHashSet<>();
		BlockState state = world.getBlockState(pos);
		List<Direction> directions = OmniRailBlock.isOmniRail(state)
				? OmniRailBlock.connections(state)
				: vanillaRailDirections(state);
		if (directions.isEmpty()) {
			directions = List.of(HORIZONTAL);
		}
		for (Direction direction : directions) {
			railPosNear(world, pos.offset(direction)).ifPresent(positions::add);
		}
		return positions;
	}

	private static List<Direction> vanillaRailDirections(BlockState state) {
		if (!(state.getBlock() instanceof AbstractRailBlock rail) || !state.contains(rail.getShapeProperty())) {
			return List.of();
		}
		RailShape shape = state.get(rail.getShapeProperty());
		return switch (shape) {
			case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> List.of(Direction.EAST, Direction.WEST);
			case NORTH_EAST -> List.of(Direction.NORTH, Direction.EAST);
			case NORTH_WEST -> List.of(Direction.NORTH, Direction.WEST);
			case SOUTH_EAST -> List.of(Direction.SOUTH, Direction.EAST);
			case SOUTH_WEST -> List.of(Direction.SOUTH, Direction.WEST);
			default -> List.of(Direction.NORTH, Direction.SOUTH);
		};
	}

	private static Optional<BlockPos> railPosNear(ServerWorld world, BlockPos base) {
		for (BlockPos pos : new BlockPos[]{base, base.up(), base.down()}) {
			if (AbstractRailBlock.isRail(world.getBlockState(pos))) {
				return Optional.of(pos);
			}
		}
		return Optional.empty();
	}

	// --------------------------------------------------------- group motion

	private static void syncExpandedMotion(CartGroup group, List<AbstractMinecartEntity> carts) {
		if (carts.size() <= 1 || group.shape == null || !isExpanded(group)) {
			return;
		}
		AbstractMinecartEntity anchor = anchorOf(group, carts);
		Vec3d anchorVelocity = clamp(anchor.getVelocity(), RailPhysics.MAX_ATTACHED_SPEED);
		for (AbstractMinecartEntity cart : carts) {
			// 探针车沿自己的分支独立行驶，不与锚点同速。
			if (cart == anchor || JunctionSplitter.isRunner(cart.getUuid())) {
				continue;
			}
			cart.setVelocity(anchorVelocity);
			cart.velocityModified = true;
		}
	}

	private static void updateModules(CartGroup group, List<AbstractMinecartEntity> carts) {
		boolean expanded = isExpanded(group);
		for (AbstractMinecartEntity cart : carts) {
			boolean suppressed = expanded
					&& !cart.getUuid().equals(group.shape.anchorCart())
					&& !JunctionSplitter.isRunner(cart.getUuid());
			setSuppressed(cart, suppressed);
		}
		if (!expanded) {
			return;
		}
		AbstractMinecartEntity anchor = anchorOf(group, carts);
		Vec3d anchorVelocity = anchor.getVelocity();
		for (AbstractMinecartEntity cart : carts) {
			if (cart == anchor || JunctionSplitter.isRunner(cart.getUuid())) {
				continue;
			}
			CartAttachment attachment = CartAttachment.of(cart);
			if (attachment.moduleOffset == null) {
				attachment.anchorCart = anchor.getUuid();
				attachment.moduleOffset = cart.getBlockPos().subtract(anchor.getBlockPos());
			}
			// 与锚点保持相同高度（centerOf 会浮到格中心，比轨道面高半格）。
			Vec3d target = anchor.getPos().add(
					attachment.moduleOffset.getX(),
					attachment.moduleOffset.getY(),
					attachment.moduleOffset.getZ());
			cart.setPosition(target);
			cart.setVelocity(anchorVelocity);
			cart.setNoGravity(true);
			cart.velocityModified = true;
		}
	}

	/** 加装的箱子用 BlockDisplay 显示在对应座位格上，随车厢移动。 */
	private static void updateChestDisplays(ServerWorld world, CartGroup group, List<AbstractMinecartEntity> carts) {
		if (carts.isEmpty() || group.shape == null) {
			return;
		}
		AbstractMinecartEntity anchor = anchorOf(group, carts);
		discardChestDisplays(world, anchor.getUuid());
		if (usesClientChestRendering()) {
			return;
		}
		List<UUID> displays = CHEST_DISPLAYS.computeIfAbsent(anchor.getUuid(), id -> new ArrayList<>());
		int wanted = Math.min(group.attachedChests, group.shape.seats().size());
		while (displays.size() > wanted) {
			Entity display = world.getEntity(displays.removeLast());
			if (display != null) {
				display.discard();
			}
		}
		while (displays.size() < wanted) {
			DisplayEntity.BlockDisplayEntity display = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);
			display.addCommandTag(CHEST_VISUAL_TAG);
			display.setNoGravity(true);
			display.setInvulnerable(true);
			display.setBlockState(Blocks.CHEST.getDefaultState());
			world.spawnEntity(display);
			displays.add(display.getUuid());
		}
		List<BlockPos> seats = group.shape.seats();
		for (int i = 0; i < displays.size(); i++) {
			Entity display = world.getEntity(displays.get(i));
			if (display == null) {
				displays.remove(i--);
				continue;
			}
			// 箱子占尾部座位；yaw 固定为 0，避免箱子随锚点朝向旋转。
			BlockPos seat = seats.get(Math.max(0, seats.size() - 1 - i));
			placeChestDisplay(display, anchor, group.shape, seat);
		}
		if (displays.isEmpty()) {
			CHEST_DISPLAYS.remove(anchor.getUuid());
		}
	}

	private static void placeChestDisplay(Entity display, AbstractMinecartEntity anchor, CarriageShape shape, BlockPos seat) {
		Optional<RailPhysics.RailContact> contact = RailPhysics.findContact(anchor.getWorld(), anchor);
		Direction face = contact.map(RailPhysics.RailContact::face).orElse(Direction.UP);
		Vec3d normal = Vec3d.of(face.getVector());
		Vec3d relative = new Vec3d(
				seat.getX() - shape.anchorBlock().getX(),
				seat.getY() - shape.anchorBlock().getY(),
				seat.getZ() - shape.anchorBlock().getZ());
		Vec3d center = anchor.getPos().add(relative).add(normal.multiply(0.46D));
		Vec3d origin = center.subtract(0.5D, 0.5D, 0.5D);
		display.refreshPositionAndAngles(origin.x, origin.y, origin.z, 0.0F, 0.0F);
		if (display instanceof DisplayEntity displayEntity) {
			Matrix4f matrix = new Matrix4f()
					.translate(0.5F, 0.5F, 0.5F)
					.rotate(chestRotation(face, anchor.getYaw()))
					.translate(-0.5F, -0.5F, -0.5F);
			DisplayEntityAccessor accessor = (DisplayEntityAccessor) displayEntity;
			accessor.echominecart$setTransformation(new AffineTransformation(matrix));
			accessor.echominecart$setInterpolationDuration(1);
		}
	}

	private static Quaternionf chestRotation(Direction face, float yaw) {
		Quaternionf rotation = switch (face) {
			case DOWN -> new Quaternionf().rotateX((float) Math.PI);
			case SOUTH -> new Quaternionf().rotateX((float) Math.PI * 0.5F);
			case NORTH -> new Quaternionf().rotateX((float) -Math.PI * 0.5F);
			case EAST -> new Quaternionf().rotateZ((float) -Math.PI * 0.5F);
			case WEST -> new Quaternionf().rotateZ((float) Math.PI * 0.5F);
			default -> new Quaternionf();
		};
		return rotation.rotateY((float) Math.toRadians(-yaw));
	}

	private static boolean usesClientChestRendering() {
		return true;
	}

	private static void discardChestDisplays(ServerWorld world, UUID anchorId) {
		List<UUID> displays = CHEST_DISPLAYS.remove(anchorId);
		if (displays == null) {
			return;
		}
		for (UUID displayId : displays) {
			Entity display = world.getEntity(displayId);
			if (display != null) {
				display.discard();
			}
		}
	}

	private static void purgeOrphanChestDisplays(ServerWorld world) {
		Set<UUID> live = new HashSet<>();
		for (List<UUID> displays : CHEST_DISPLAYS.values()) {
			live.addAll(displays);
		}
		for (Entity entity : world.iterateEntities()) {
			if (entity != null && entity.getCommandTags().contains(CHEST_VISUAL_TAG) && !live.contains(entity.getUuid())) {
				entity.discard();
			}
		}
	}

	/** 每 4 tick 把扩充车厢的覆盖格发给追踪锚点的客户端；未扩充的组靠客户端过期清理。 */
	private static void syncShapeToClients(CartGroup group, List<AbstractMinecartEntity> carts) {
		if (group.shape == null || !isExpanded(group) || carts.isEmpty()) {
			return;
		}
		AbstractMinecartEntity anchor = anchorOf(group, carts);
		List<Long> cells = group.shape.seats().stream().map(BlockPos::asLong).limit(256).toList();
		CarriageSyncPayload payload = new CarriageSyncPayload(anchor.getId(), group.shape.anchorBlock().asLong(), cells, group.attachedChests);
		for (var player : PlayerLookup.tracking(anchor)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static void setSuppressed(AbstractMinecartEntity cart, boolean suppressed) {
		CartAttachment attachment = CartAttachment.of(cart);
		attachment.suppressedAsModule = suppressed;
		cart.noClip = suppressed;
		cart.setSilent(suppressed);
		if (suppressed) {
			cart.setInvisible(true);
			cart.setNoGravity(true);
		} else if (attachment.wasSuppressedAsModule) {
			cart.setInvisible(false);
			cart.setSilent(false);
			cart.noClip = false;
		}
		attachment.wasSuppressedAsModule = suppressed;
	}

	// ---------------------------------------------------- passenger handling

	private static void pickupCollidingEntities(ServerWorld world, CartGroup group, List<AbstractMinecartEntity> carts) {
		if (group.shape == null || !isExpanded(group)) {
			return;
		}
		int capacity = passengerCapacity(group);
		if (capacity <= passengerCount(group)) {
			return;
		}
		AbstractMinecartEntity anchor = anchorOf(group, carts);
		if (anchor.getVelocity().length() < PICKUP_MIN_SPEED) {
			return;
		}
		List<LivingEntity> entities = world.getEntitiesByType(
				TypeFilter.instanceOf(LivingEntity.class),
				group.shape.bounds().expand(0.15D, 0.1D, 0.15D),
				entity -> !(entity instanceof PlayerEntity)
						&& !entity.hasVehicle()
						&& entity.isAlive()
						&& entity.getBoundingBox().intersects(group.shape.impactBounds()));
		for (LivingEntity entity : entities) {
			if (passengerCount(group) >= capacity) {
				return;
			}
			if (group.shape.intersectsBody(entity.getBoundingBox())) {
				entity.startRiding(anchor, true);
			}
		}
	}

	private static void consolidatePassengers(CartGroup group, List<AbstractMinecartEntity> carts) {
		if (group.shape == null || !isExpanded(group)) {
			return;
		}
		AbstractMinecartEntity anchor = anchorOf(group, carts);
		for (AbstractMinecartEntity cart : carts) {
			if (cart == anchor) {
				continue;
			}
			for (Entity passenger : List.copyOf(cart.getPassengerList())) {
				passenger.stopRiding();
				if (passengerCount(group) < passengerCapacity(group)) {
					passenger.startRiding(anchor, true);
				} else {
					passenger.refreshPositionAfterTeleport(centerOf(group.shape.seats().getLast()).add(0.0D, 0.2D, 0.0D));
				}
			}
		}
	}

	private static void ejectOverflowPassengers(CartGroup group) {
		if (group.shape == null) {
			return;
		}
		int capacity = passengerCapacity(group);
		List<Entity> passengers = passengersOf(group);
		for (int i = capacity; i < passengers.size(); i++) {
			Entity passenger = passengers.get(i);
			passenger.stopRiding();
			passenger.refreshPositionAfterTeleport(new Vec3d(
					group.shape.bounds().maxX + 0.5D,
					group.shape.bounds().minY + 0.1D,
					group.shape.bounds().maxZ + 0.5D));
		}
	}

	private static Vec3d seatLift(AbstractMinecartEntity cart) {
		Optional<RailPhysics.RailContact> contact = RailPhysics.findContact(cart.getWorld(), cart);
		Direction face = contact.map(RailPhysics.RailContact::face).orElse(Direction.UP);
		return switch (face) {
			case DOWN -> new Vec3d(0.0D, -0.44D, 0.0D);
			case NORTH -> new Vec3d(0.0D, 0.24D, 0.45D);
			case SOUTH -> new Vec3d(0.0D, 0.24D, -0.45D);
			case EAST -> new Vec3d(-0.45D, 0.24D, 0.0D);
			case WEST -> new Vec3d(0.45D, 0.24D, 0.0D);
			default -> new Vec3d(0.0D, 0.15D, 0.0D);
		};
	}

	// ------------------------------------------------------------- accessors

	private static CartGroup groupOf(AbstractMinecartEntity cart) {
		return groupOf(cart.getUuid());
	}

	private static CartGroup groupOf(UUID cartId) {
		UUID groupId = CART_TO_GROUP.get(cartId);
		return groupId == null ? null : GROUPS.get(groupId);
	}

	private static List<AbstractMinecartEntity> cartsOf(CartGroup group, Map<UUID, AbstractMinecartEntity> byId) {
		List<AbstractMinecartEntity> carts = new ArrayList<>();
		for (UUID cartId : group.carts) {
			AbstractMinecartEntity cart = byId.get(cartId);
			if (cart != null) {
				carts.add(cart);
			}
		}
		return carts;
	}

	private static AbstractMinecartEntity anchorOf(CartGroup group, List<AbstractMinecartEntity> carts) {
		return carts.stream()
				.filter(cart -> cart.getUuid().equals(group.shape.anchorCart()))
				.findFirst()
				.orElse(carts.getFirst());
	}

	private static GroupSnapshot snapshotOf(CartGroup group, Map<UUID, AbstractMinecartEntity> byId) {
		List<AbstractMinecartEntity> carts = cartsOf(group, byId);
		AbstractMinecartEntity anchor = carts.isEmpty() || group.shape == null ? null : anchorOf(group, carts);
		return new GroupSnapshot(group.id, anchor, carts, group.shape, group.stillTicks);
	}

	private static int totalSeatSlots(CartGroup group) {
		return group.shape == null ? Math.max(1, group.carts.size()) : Math.max(1, group.shape.seats().size());
	}

	private static boolean isExpanded(CartGroup group) {
		return group.shape != null && (group.carts.size() > 1 || group.shape.seats().size() > 1 || group.attachedChests > 0);
	}

	private static int passengerCapacity(CartGroup group) {
		return Math.max(0, totalSeatSlots(group) - group.attachedChests);
	}

	private static int passengerCount(CartGroup group) {
		return passengersOf(group).size();
	}

	private static List<Entity> passengersOf(CartGroup group) {
		List<Entity> passengers = new ArrayList<>();
		for (UUID cartId : group.carts) {
			Entity cart = group.world.getEntity(cartId);
			if (cart != null) {
				passengers.addAll(cart.getPassengerList());
			}
		}
		return passengers;
	}

	private static int passengerIndex(CartGroup group, Entity passenger) {
		List<Entity> passengers = passengersOf(group);
		for (int i = 0; i < passengers.size(); i++) {
			if (passengers.get(i).getUuid().equals(passenger.getUuid())) {
				return i;
			}
		}
		return -1;
	}

	private static Vec3d forwardOf(List<AbstractMinecartEntity> carts) {
		Vec3d velocity = Vec3d.ZERO;
		for (AbstractMinecartEntity cart : carts) {
			velocity = velocity.add(cart.getVelocity());
		}
		if (velocity.horizontalLengthSquared() > 1.0E-3D) {
			return new Vec3d(velocity.x, 0.0D, velocity.z).normalize();
		}
		if (!carts.isEmpty()) {
			Direction direction = carts.getFirst().getMovementDirection();
			return new Vec3d(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
		}
		return new Vec3d(0.0D, 0.0D, 1.0D);
	}

	private static Vec3d centerOf(BlockPos pos) {
		return new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
	}

	private static Vec3d clamp(Vec3d velocity, double max) {
		return velocity.length() <= max ? velocity : velocity.normalize().multiply(max);
	}

	private static void union(Map<UUID, UUID> parent, UUID left, UUID right) {
		UUID leftRoot = find(parent, left);
		UUID rightRoot = find(parent, right);
		if (!leftRoot.equals(rightRoot)) {
			parent.put(rightRoot, leftRoot);
		}
	}

	private static UUID find(Map<UUID, UUID> parent, UUID id) {
		UUID current = parent.getOrDefault(id, id);
		if (!current.equals(id)) {
			current = find(parent, current);
			parent.put(id, current);
		}
		return current;
	}

	private static void message(PlayerEntity player, String message) {
		if (player instanceof ServerPlayerEntity serverPlayer) {
			serverPlayer.sendMessage(Text.literal(message), true);
		}
	}

	// ---------------------------------------------------------------- types

	private static final class CartGroup {
		private final UUID id;
		private final ServerWorld world;
		private final Set<UUID> carts = new HashSet<>();
		private CarriageShape shape;
		private int attachedChests;
		private int stillTicks;

		private CartGroup(UUID id, ServerWorld world) {
			this.id = id;
			this.world = world;
		}
	}

	private record CartPair(UUID left, UUID right) {
		private static CartPair of(UUID left, UUID right) {
			return left.compareTo(right) <= 0 ? new CartPair(left, right) : new CartPair(right, left);
		}
	}

	/** 供搬运绑定等外部系统使用的车厢组快照。 */
	public record GroupSnapshot(
			UUID groupId,
			AbstractMinecartEntity anchor,
			List<AbstractMinecartEntity> carts,
			CarriageShape shape,
			int stillTicks) {
	}
}
