package com.xc.echominecart.ringvehicle;

import com.xc.echominecart.EchoMinecartRegistry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Places a persisted ring-vehicle entity and exposes installed modules in the item tooltip. */
public final class RingVehicleItem extends Item {
	private final RingVehicleVariant variant;
	private final boolean lavaProof;

	public RingVehicleItem(RingVehicleVariant variant, boolean lavaProof, Settings settings) {
		super(settings);
		this.variant = variant;
		this.lavaProof = lavaProof;
	}

	public RingVehicleVariant variant() {
		return variant;
	}

	public boolean lavaProof() {
		return lavaProof;
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		BlockPos base = context.getBlockPos().offset(context.getSide());
		Vec3d spawn = new Vec3d(base.getX() + 0.5D, base.getY(), base.getZ() + 0.5D);
		float yaw = context.getPlayerYaw();
		Vec3d forward = new Vec3d(-Math.sin(Math.toRadians(yaw)), 0.0D, Math.cos(Math.toRadians(yaw)));
		int ringLevel = ringLevel(context.getStack());
		boolean discMode = discMode(context.getStack());
		boolean spiderMode = spiderMode(context.getStack());
		Box box = discMode
				? RingVehicleEntity.discCollisionBounds(spawn, ringLevel)
				: spiderMode
						? RingVehicleEntity.spiderCollisionBounds(spawn, ringLevel)
				: RingVehicleEntity.collisionBounds(
						spawn, Vec3d.of(net.minecraft.util.math.Direction.UP.getVector()), forward, ringLevel);
		if (!context.getWorld().isSpaceEmpty(null, box)) {
			if (context.getPlayer() != null) {
				context.getPlayer().sendMessage(Text.translatable("message.echominecart.ring_vehicle_no_space"), true);
			}
			return ActionResult.FAIL;
		}
		if (context.getWorld() instanceof ServerWorld serverWorld) {
			RingVehicleEntity vehicle = EchoMinecartRegistry.RING_VEHICLE_ENTITY.create(
					serverWorld, entity -> {
						entity.setVariant(variant);
						entity.setLavaProof(lavaProof);
						NbtComponent component = context.getStack().get(DataComponentTypes.CUSTOM_DATA);
						if (component != null) {
							entity.readItemData(component.copyNbt());
						}
						entity.setVariant(variant);
						entity.setLavaProof(lavaProof);
					}, base, SpawnReason.TRIGGERED, false, false);
			if (vehicle == null) {
				return ActionResult.FAIL;
			}
			vehicle.refreshPositionAndAngles(spawn.x, spawn.y, spawn.z, context.getPlayerYaw(), 0.0F);
			vehicle.prepareSpiderPlacement();
			serverWorld.spawnEntity(vehicle);
			if (context.getPlayer() == null || !context.getPlayer().getAbilities().creativeMode) {
				context.getStack().decrement(1);
			}
		}
		return ActionResult.success(context.getWorld().isClient());
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return variant.powered() || lavaProof;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, net.minecraft.item.tooltip.TooltipType type) {
		super.appendTooltip(stack, context, tooltip, type);
		NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
		DefaultedList<ItemStack> installed = DefaultedList.ofSize(RingVehicleInventory.SIZE, ItemStack.EMPTY);
		if (component != null) {
			try {
				installed = RingVehicleInventory.readStacks(component.copyNbt(), context.getRegistryLookup());
			} catch (RuntimeException ignored) {
				installed.clear();
			}
		}
		boolean chestAttached = installed.get(RingVehicleInventory.ABILITY_CHEST).isOf(net.minecraft.item.Items.CHEST)
				|| component != null && component.copyNbt().getBoolean("ChestAttached");
		if (chestAttached) {
			tooltip.add(Text.translatable("tooltip.echominecart.ring_vehicle_storage").formatted(Formatting.GOLD));
		}
		ItemStack jump = installed.get(RingVehicleInventory.ABILITY_JUMP);
		if (jump.isOf(net.minecraft.item.Items.RABBIT_FOOT)) {
			tooltip.add(Text.translatable("tooltip.echominecart.ring_vehicle_jump", jump.getCount()).formatted(Formatting.GREEN));
		}
		ItemStack dash = installed.get(RingVehicleInventory.ABILITY_DASH);
		if (dash.isOf(net.minecraft.item.Items.SUGAR)) {
			tooltip.add(Text.translatable("tooltip.echominecart.ring_vehicle_dash", dash.getCount()).formatted(Formatting.AQUA));
		}
		ItemStack smash = installed.get(RingVehicleInventory.ABILITY_SMASH);
		if (smash.isOf(net.minecraft.item.Items.HEAVY_CORE) || smash.isOf(net.minecraft.item.Items.MACE)) {
			tooltip.add(Text.translatable("tooltip.echominecart.ring_vehicle_smash_attack",
					smash.isOf(net.minecraft.item.Items.MACE)
							? Text.translatable("item.minecraft.mace")
							: Text.translatable("item.minecraft.heavy_core")).formatted(Formatting.RED));
		}
		ItemStack ammunition = installed.get(RingVehicleInventory.ABILITY_AMMO);
		if (SpiderAmmoType.isAmmo(ammunition)) {
			tooltip.add(Text.translatable("tooltip.echominecart.ring_vehicle_spider_ammo",
					ammunition.getName(), ammunition.getCount()).formatted(Formatting.AQUA));
		}
		if (variant.powered()) {
			boolean clutchInstalled = installed.get(RingVehicleInventory.CLUTCH_SLOT)
					.isOf(EchoMinecartRegistry.REINFORCED_CLUTCH);
			tooltip.add(Text.translatable(clutchInstalled
					? "tooltip.echominecart.ring_vehicle_clutch_installed"
					: "tooltip.echominecart.ring_vehicle_clutch_missing"));
		} else {
			tooltip.add(Text.translatable("tooltip.echominecart.ring_vehicle_clutch_unsupported"));
		}
		if (ringLevel(stack) > 0) {
			tooltip.add(Text.translatable("tooltip.echominecart.ring_vehicle_expanded_ring").formatted(Formatting.LIGHT_PURPLE));
		}
		if (component != null && component.copyNbt().getBoolean("DiscMode")) {
			int minecartCount = 1 + Math.max(0, Math.min(RingVehicleEntity.MAX_DISC_EXTRA_MINECARTS,
					component.copyNbt().getInt("DiscExtraMinecarts")));
			tooltip.add(Text.translatable("tooltip.echominecart.ring_vehicle_disc_mode", minecartCount)
					.formatted(Formatting.AQUA));
		}
		if (spiderMode(stack)) {
			tooltip.add(Text.translatable("tooltip.echominecart.ring_vehicle_spider_mode").formatted(Formatting.DARK_GREEN));
			tooltip.add(Text.translatable("tooltip.echominecart.ring_vehicle_spider_legs",
					Integer.bitCount(spiderLegMask(stack)), RingVehicleEntity.SPIDER_LEG_COUNT)
					.formatted(Formatting.GRAY));
			if (spiderPendingLeg(stack) >= 0) {
				tooltip.add(Text.translatable("tooltip.echominecart.ring_vehicle_spider_leg_pending")
						.formatted(Formatting.YELLOW));
			}
		}
		if (lavaProof) {
			tooltip.add(Text.translatable("tooltip.echominecart.ring_vehicle_lava_proof"));
		}
	}

	public static int ringLevel(ItemStack stack) {
		NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
		return component == null
				? 0
				: Math.max(0, Math.min(RingVehicleEntity.MAX_RING_LEVEL, component.copyNbt().getInt("RingLevel")));
	}

	public static boolean discMode(ItemStack stack) {
		NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
		return component != null && component.copyNbt().getBoolean("DiscMode");
	}

	public static boolean spiderMode(ItemStack stack) {
		NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
		return component != null && component.copyNbt().getBoolean("SpiderMode");
	}

	public static int spiderLegMask(ItemStack stack) {
		NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (component == null) {
			return 0;
		}
		var data = component.copyNbt();
		return data.contains("SpiderLegMask")
				? data.getInt("SpiderLegMask") & ((1 << RingVehicleEntity.SPIDER_LEG_COUNT) - 1)
				: (1 << RingVehicleEntity.SPIDER_LEG_COUNT) - 1;
	}

	public static int spiderPendingLeg(ItemStack stack) {
		NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (component == null) {
			return -1;
		}
		var data = component.copyNbt();
		if (!data.contains("SpiderPendingLeg")) {
			return -1;
		}
		int pending = data.getInt("SpiderPendingLeg");
		return pending >= 0 && pending < RingVehicleEntity.SPIDER_LEG_COUNT ? pending : -1;
	}
}
