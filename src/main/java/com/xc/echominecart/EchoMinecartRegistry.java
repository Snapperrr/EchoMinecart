package com.xc.echominecart;

import com.xc.echominecart.carriage.CarriageManager;
import com.xc.echominecart.item.BoosterRodItem;
import com.xc.echominecart.item.MinecartLinkToolItem;
import com.xc.echominecart.item.RailRepairToolItem;
import com.xc.echominecart.item.ReinforcedClutchItem;
import com.xc.echominecart.item.SpeedRailBlockItem;
import com.xc.echominecart.item.TransportBinderItem;
import com.xc.echominecart.network.SpeedRailOpenPayload;
import com.xc.echominecart.ringvehicle.LavaProofRingVehicleRecipe;
import com.xc.echominecart.ringvehicle.RingVehicleEntity;
import com.xc.echominecart.ringvehicle.RingVehicleExpansionRecipe;
import com.xc.echominecart.ringvehicle.RingVehicleItem;
import com.xc.echominecart.ringvehicle.RingVehicleVariant;
import com.xc.echominecart.trip.TripManager;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import com.xc.echominecart.rail.OmniRailBlock;
import com.xc.echominecart.rail.PoweredOmniRailBlock;
import com.xc.echominecart.rail.RailPhysics;
import com.xc.echominecart.rail.SpeedRailBlock;
import com.xc.echominecart.rail.SpeedRailStorage;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.registry.LandPathNodeTypesRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.SpecialRecipeSerializer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;

/**
 * EchoMinecart 的注册中心：全向铁轨方块/物品、调试工具、
 * 原版铁轨与矿车放置的接管，以及服务器 tick 驱动。
 */
/** Owns registration of blocks, items, entities, recipes, and their shared lookup helpers. */
public final class EchoMinecartRegistry {
	public static final Block ECHO_RAIL = registerBlock("echo_rail", new OmniRailBlock(AbstractBlock.Settings.copy(Blocks.RAIL)));
	public static final Block ECHO_POWERED_RAIL = registerBlock("echo_powered_rail", new PoweredOmniRailBlock(AbstractBlock.Settings.copy(Blocks.POWERED_RAIL)));
	public static final SpeedRailBlock SPEED_RAIL = (SpeedRailBlock) registerBlock(
			"speed_rail", new SpeedRailBlock(AbstractBlock.Settings.copy(Blocks.DETECTOR_RAIL)));
	public static final Item ECHO_RAIL_ITEM = registerItem("echo_rail", new BlockItem(ECHO_RAIL, new Item.Settings()));
	public static final Item ECHO_POWERED_RAIL_ITEM = registerItem("echo_powered_rail", new BlockItem(ECHO_POWERED_RAIL, new Item.Settings()));
	public static final Item SPEED_RAIL_ITEM = registerItem("speed_rail", new SpeedRailBlockItem(SPEED_RAIL, new Item.Settings()));
	public static final MinecartLinkToolItem MINECART_LINK_TOOL = registerItem("minecart_link_tool", new MinecartLinkToolItem(new Item.Settings().maxCount(1)));
	public static final TransportBinderItem TRANSPORT_BINDER = registerItem("transport_binder", new TransportBinderItem(new Item.Settings().maxCount(1)));
	public static final BoosterRodItem BOOSTER_ROD = registerItem("booster_rod", new BoosterRodItem(new Item.Settings().maxCount(1)));
	public static final RailRepairToolItem RAIL_REPAIR_TOOL = registerItem("rail_repair_tool", new RailRepairToolItem(new Item.Settings().maxCount(1)));
	public static final Item REINFORCED_CLUTCH = registerItem("reinforced_clutch",
			new ReinforcedClutchItem(new Item.Settings().maxDamage(360)));
	public static final EntityType<RingVehicleEntity> RING_VEHICLE_ENTITY = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(NestedChestMod.MOD_ID, "ring_vehicle"),
			EntityType.Builder.<RingVehicleEntity>create(RingVehicleEntity::new, SpawnGroup.MISC)
					.dimensions(RingVehicleEntity.SIZE, RingVehicleEntity.SIZE)
					.eyeHeight(0.85F)
					.maxTrackingRange(12)
					.trackingTickInterval(1)
					.alwaysUpdateVelocity(true)
					.build());
	public static final RingVehicleItem RING_RAIL_VEHICLE = registerItem("ring_rail_vehicle",
			new RingVehicleItem(RingVehicleVariant.RAIL, false, new Item.Settings().maxCount(1).fireproof()));
	public static final RingVehicleItem RING_POWERED_RAIL_VEHICLE = registerItem("ring_powered_rail_vehicle",
			new RingVehicleItem(RingVehicleVariant.POWERED, false, new Item.Settings().maxCount(1).fireproof()));
	public static final RingVehicleItem LAVA_PROOF_RING_RAIL_VEHICLE = registerItem("lava_proof_ring_rail_vehicle",
			new RingVehicleItem(RingVehicleVariant.RAIL, true, new Item.Settings().maxCount(1).fireproof()));
	public static final RingVehicleItem LAVA_PROOF_RING_POWERED_RAIL_VEHICLE = registerItem("lava_proof_ring_powered_rail_vehicle",
			new RingVehicleItem(RingVehicleVariant.POWERED, true, new Item.Settings().maxCount(1).fireproof()));
	public static final RecipeSerializer<LavaProofRingVehicleRecipe> LAVA_PROOF_RING_VEHICLE_RECIPE = Registry.register(
			Registries.RECIPE_SERIALIZER,
			Identifier.of(NestedChestMod.MOD_ID, "ring_vehicle_lava_upgrade"),
			new SpecialRecipeSerializer<>(LavaProofRingVehicleRecipe::new));
	public static final RecipeSerializer<RingVehicleExpansionRecipe> RING_VEHICLE_EXPANSION_RECIPE = Registry.register(
			Registries.RECIPE_SERIALIZER,
			Identifier.of(NestedChestMod.MOD_ID, "ring_vehicle_expansion"),
			new SpecialRecipeSerializer<>(RingVehicleExpansionRecipe::new));

	private EchoMinecartRegistry() {
	}

	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(entries -> {
			entries.add(ECHO_RAIL_ITEM);
			entries.add(ECHO_POWERED_RAIL_ITEM);
			entries.add(SPEED_RAIL_ITEM);
		});
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
			entries.add(MINECART_LINK_TOOL);
			entries.add(TRANSPORT_BINDER);
			entries.add(BOOSTER_ROD);
			entries.add(RAIL_REPAIR_TOOL);
			entries.add(REINFORCED_CLUTCH);
			entries.add(RING_RAIL_VEHICLE);
			entries.add(RING_POWERED_RAIL_VEHICLE);
			entries.add(LAVA_PROOF_RING_RAIL_VEHICLE);
			entries.add(LAVA_PROOF_RING_POWERED_RAIL_VEHICLE);
		});
		ServerTickEvents.END_SERVER_TICK.register(CarriageManager::serverTick);
		ServerTickEvents.END_SERVER_TICK.register(ReinforcedClutchItem::serverTick);
		UseBlockCallback.EVENT.register(EchoMinecartRegistry::useBlock);
		UseEntityCallback.EVENT.register(EchoMinecartRegistry::useEntity);
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
				player.getVehicle() instanceof RingVehicleEntity vehicle && vehicle.isMiningModeEnabled()
						? ActionResult.FAIL
						: ActionResult.PASS);
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
				player.getVehicle() instanceof RingVehicleEntity vehicle && vehicle.isMiningModeEnabled()
						? ActionResult.FAIL
						: ActionResult.PASS);
		// 玩家开始追踪实体时补发绊倒姿态，否则后进入范围的客户端看不到趴倒。
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> TripManager.sendStateTo(entity, player));
		registerRailPathfinding();
	}

	/**
	 * 原版寻路把铁轨标成 RAIL 节点让生物绕开；
	 * 全向铁轨要作为绊倒陷阱生效，必须让生物把它当普通可行走地面。
	 */
	private static void registerRailPathfinding() {
		LandPathNodeTypesRegistry.register(ECHO_RAIL, PathNodeType.WALKABLE, PathNodeType.WALKABLE);
		LandPathNodeTypesRegistry.register(ECHO_POWERED_RAIL, PathNodeType.WALKABLE, PathNodeType.WALKABLE);
	}

	private static Block registerBlock(String path, Block block) {
		return Registry.register(Registries.BLOCK, Identifier.of(NestedChestMod.MOD_ID, path), block);
	}

	private static <T extends Item> T registerItem(String path, T item) {
		return Registry.register(Registries.ITEM, Identifier.of(NestedChestMod.MOD_ID, path), item);
	}

	public static Item ringVehicleItem(RingVehicleVariant variant, boolean lavaProof) {
		if (variant.powered()) {
			return lavaProof ? LAVA_PROOF_RING_POWERED_RAIL_VEHICLE : RING_POWERED_RAIL_VEHICLE;
		}
		return lavaProof ? LAVA_PROOF_RING_RAIL_VEHICLE : RING_RAIL_VEHICLE;
	}

	// ------------------------------------------------------------ placement

	private static ActionResult useBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
		ItemStack stack = player.getStackInHand(hand);
		BlockState clicked = world.getBlockState(hitResult.getBlockPos());
		if (hand == Hand.MAIN_HAND && player.isSneaking() && stack.isEmpty() && clicked.isOf(SPEED_RAIL)) {
			if (world instanceof ServerWorld serverWorld && player instanceof ServerPlayerEntity serverPlayer) {
				ServerPlayNetworking.send(serverPlayer, new SpeedRailOpenPayload(
						hitResult.getBlockPos().asLong(), SpeedRailStorage.getSpeed(serverWorld, hitResult.getBlockPos())));
			}
			return ActionResult.SUCCESS;
		}
		if (stack.getItem() instanceof RailRepairToolItem tool) {
			ActionResult result = tool.useOnRail(player, world, hitResult);
			if (result != ActionResult.PASS) {
				return result;
			}
		}
		Block replacement = omniRailFor(stack);
		if (replacement == null) {
			return placeMinecartOnAttachedRail(player, world, hitResult, stack);
		}
		BlockPos placePos = clicked.isReplaceable() ? hitResult.getBlockPos() : hitResult.getBlockPos().offset(hitResult.getSide());
		if (!world.getBlockState(placePos).isReplaceable()) {
			return ActionResult.PASS;
		}
		Direction face = clicked.isReplaceable() ? Direction.UP : hitResult.getSide();
		if (!OmniRailBlock.canAttachAt(world, placePos, face)) {
			return ActionResult.FAIL;
		}
		boolean redstoneAware = replacement instanceof PoweredOmniRailBlock;
		BlockState placed = OmniRailBlock.withConnections(world, placePos, replacement.getDefaultState()
				.with(OmniRailBlock.FACE, face)
				.with(OmniRailBlock.POWERED, redstoneAware && world.isReceivingRedstonePower(placePos))
				.with(OmniRailBlock.WATERLOGGED, world.getFluidState(placePos).getFluid() == Fluids.WATER));
		placed = orientIsolatedPoweredWallRail(world, placePos, placed, player.getHorizontalFacing());
		if (!world.isClient()) {
			world.setBlockState(placePos, placed, Block.NOTIFY_ALL);
			if (!player.getAbilities().creativeMode) {
				stack.decrement(1);
			}
		}
		return ActionResult.SUCCESS;
	}

	private static BlockState orientIsolatedPoweredWallRail(World world, BlockPos pos, BlockState state, Direction playerFacing) {
		if (!(state.getBlock() instanceof PoweredOmniRailBlock) || state.get(OmniRailBlock.FACE).getAxis().isVertical()) {
			return state;
		}
		Direction face = state.get(OmniRailBlock.FACE);
		for (Direction tangent : OmniRailBlock.planeTangents(face)) {
			if (OmniRailBlock.findLink(world, pos, face, tangent) != null) {
				return state;
			}
		}
		return OmniRailBlock.orientIsolatedWallRail(state, playerFacing);
	}

	/** 原版矿车物品只认地面轨；放到墙面/天花板轨上时手动生成并吸附。 */
	private static ActionResult placeMinecartOnAttachedRail(PlayerEntity player, World world, BlockHitResult hitResult, ItemStack stack) {
		AbstractMinecartEntity.Type type = minecartTypeFor(stack);
		if (type == null) {
			return ActionResult.PASS;
		}
		Optional<BlockPos> targetRail = targetRailPos(world, hitResult);
		if (targetRail.isEmpty()) {
			return ActionResult.PASS;
		}
		BlockPos railPos = targetRail.get();
		BlockState railState = world.getBlockState(railPos);
		Direction face = OmniRailBlock.face(railState);
		if (!OmniRailBlock.isOmniRail(railState)) {
			return ActionResult.PASS;
		}
		if (!world.isClient() && world instanceof ServerWorld serverWorld) {
			Vec3d spawnPos = RailPhysics.surfacePoint(railPos, railState, face, Vec3d.ofCenter(railPos));
			AbstractMinecartEntity minecart = AbstractMinecartEntity.create(serverWorld, spawnPos.x, spawnPos.y, spawnPos.z, type, stack, player);
			minecart.refreshPositionAndAngles(spawnPos.x, spawnPos.y, spawnPos.z, yawForRail(railState), 0.0F);
			minecart.setNoGravity(face != Direction.UP);
			minecart.setVelocity(Vec3d.ZERO);
			serverWorld.spawnEntity(minecart);
			if (!player.getAbilities().creativeMode) {
				stack.decrement(1);
			}
		}
		return ActionResult.SUCCESS;
	}

	private static Optional<BlockPos> targetRailPos(World world, BlockHitResult hitResult) {
		BlockPos clicked = hitResult.getBlockPos();
		if (OmniRailBlock.isOmniRail(world.getBlockState(clicked))) {
			return Optional.of(clicked);
		}
		Direction side = hitResult.getSide();
		for (BlockPos candidate : new BlockPos[]{
				clicked.offset(side), clicked.offset(side.getOpposite()),
				clicked.down(), clicked.up(),
				clicked.north(), clicked.south(), clicked.east(), clicked.west()}) {
			if (OmniRailBlock.isOmniRail(world.getBlockState(candidate))) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	private static float yawForRail(BlockState state) {
		Direction direction = switch (state.get(OmniRailBlock.SHAPE)) {
			case EAST_WEST, ASCENDING_EAST -> Direction.EAST;
			case ASCENDING_WEST -> Direction.WEST;
			case ASCENDING_NORTH -> Direction.NORTH;
			case ASCENDING_SOUTH -> Direction.SOUTH;
			case NORTH_EAST, SOUTH_EAST -> Direction.EAST;
			case NORTH_WEST, SOUTH_WEST -> Direction.WEST;
			default -> {
				List<Direction> connections = OmniRailBlock.connections(state);
				yield connections.stream()
						.filter(connection -> !connection.getAxis().isVertical())
						.findFirst()
						.orElseGet(() -> connections.isEmpty() ? Direction.SOUTH : connections.getFirst());
			}
		};
		return switch (direction) {
			case NORTH -> 180.0F;
			case SOUTH -> 0.0F;
			case WEST -> 90.0F;
			case EAST -> -90.0F;
			default -> 0.0F;
		};
	}

	private static AbstractMinecartEntity.Type minecartTypeFor(ItemStack stack) {
		if (stack.isOf(Items.MINECART)) {
			return AbstractMinecartEntity.Type.RIDEABLE;
		}
		if (stack.isOf(Items.CHEST_MINECART)) {
			return AbstractMinecartEntity.Type.CHEST;
		}
		if (stack.isOf(Items.FURNACE_MINECART)) {
			return AbstractMinecartEntity.Type.FURNACE;
		}
		if (stack.isOf(Items.TNT_MINECART)) {
			return AbstractMinecartEntity.Type.TNT;
		}
		if (stack.isOf(Items.HOPPER_MINECART)) {
			return AbstractMinecartEntity.Type.HOPPER;
		}
		if (stack.isOf(Items.COMMAND_BLOCK_MINECART)) {
			return AbstractMinecartEntity.Type.COMMAND_BLOCK;
		}
		return null;
	}

	private static Block omniRailFor(ItemStack stack) {
		if (stack.isOf(Items.RAIL)) {
			return ECHO_RAIL;
		}
		if (stack.isOf(Items.POWERED_RAIL)) {
			return ECHO_POWERED_RAIL;
		}
		return null;
	}

	// ---------------------------------------------------------- interaction

	private static ActionResult useEntity(PlayerEntity player, World world, Hand hand, Entity entity, EntityHitResult hitResult) {
		if (!(entity instanceof AbstractMinecartEntity minecart)) {
			return ActionResult.PASS;
		}
		ItemStack stack = player.getStackInHand(hand);
		if (stack.getItem() instanceof MinecartLinkToolItem tool) {
			return tool.useOnMinecart(player, world, minecart);
		}
		if (stack.getItem() instanceof TransportBinderItem tool) {
			return tool.useOnMinecart(player, world, minecart);
		}
		if (stack.getItem() instanceof BoosterRodItem tool) {
			return tool.useOnMinecart(player, world, minecart);
		}
		if (player.isSneaking() && stack.isOf(Items.CHEST)) {
			if (!world.isClient() && CarriageManager.attachChest(player, minecart) && !player.getAbilities().creativeMode) {
				stack.decrement(1);
			}
			return ActionResult.SUCCESS;
		}
		if (player.isSneaking() && stack.isEmpty() && !world.isClient()) {
			return CarriageManager.openGroupChest(player, minecart) ? ActionResult.SUCCESS : ActionResult.PASS;
		}
		return ActionResult.PASS;
	}
}
