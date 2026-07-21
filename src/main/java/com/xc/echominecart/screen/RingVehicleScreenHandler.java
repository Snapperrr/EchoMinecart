package com.xc.echominecart.screen;

import com.xc.echominecart.NestedChestMod;
import com.xc.echominecart.ringvehicle.RingVehicleEntity;
import com.xc.echominecart.ringvehicle.RingVehicleInventory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Validates tool, ability, clutch, and optional storage slots for a ring vehicle. */
public final class RingVehicleScreenHandler extends ScreenHandler {
	public static final int TOOL_SLOT_COUNT = RingVehicleInventory.TOOL_SLOTS;
	public static final int VEHICLE_SLOT_COUNT = RingVehicleInventory.SIZE;
	private final Inventory inventory;
	private final RingVehicleEntity vehicle;

	public RingVehicleScreenHandler(int syncId, PlayerInventory playerInventory, int entityId) {
		this(syncId, playerInventory, findVehicle(playerInventory, entityId));
	}

	public RingVehicleScreenHandler(int syncId, PlayerInventory playerInventory, RingVehicleEntity vehicle) {
		super(NestedChestMod.RING_VEHICLE_SCREEN_HANDLER, syncId);
		this.vehicle = vehicle;
		this.inventory = vehicle == null ? new SimpleInventory(RingVehicleInventory.SIZE) : vehicle.inventory();
		inventory.onOpen(playerInventory.player);

		for (int slot = 0; slot < TOOL_SLOT_COUNT; slot++) {
			addSlot(new ToolSlot(inventory, slot, 12 + slot * 20, 51));
		}
		for (int i = 0; i < RingVehicleInventory.ABILITY_SLOTS; i++) {
			addSlot(new AbilitySlot(inventory, RingVehicleInventory.TOOL_SLOTS + i, 101 + i * 20, 51));
		}
		addSlot(new ClutchSlot(inventory, RingVehicleInventory.CLUTCH_SLOT, 185, 51));
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				addSlot(new StorageSlot(inventory, RingVehicleInventory.STORAGE_START + row * 9 + column,
						27 + column * 18, 106 + row * 18));
			}
		}
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				addSlot(new Slot(playerInventory, column + row * 9 + 9, 27 + column * 18, 183 + row * 18));
			}
		}
		for (int column = 0; column < 9; column++) {
			addSlot(new Slot(playerInventory, column, 27 + column * 18, 239));
		}
	}

	private static RingVehicleEntity findVehicle(PlayerInventory inventory, int entityId) {
		Entity entity = inventory.player.getWorld().getEntityById(entityId);
		return entity instanceof RingVehicleEntity vehicle ? vehicle : null;
	}

	public boolean hasChest() {
		return vehicle != null && vehicle.hasChestAttached();
	}

	public boolean supportsClutch() {
		return vehicle != null && vehicle.supportsReinforcedClutch();
	}

	public boolean hasMiningTool() {
		return vehicle != null && vehicle.hasMiningTool();
	}

	public boolean isMiningModeEnabled() {
		return vehicle != null && vehicle.isMiningModeEnabled();
	}

	public int vehicleId() {
		return vehicle == null ? -1 : vehicle.getId();
	}

	@Override
	public boolean canUse(PlayerEntity player) {
		return vehicle != null && inventory.canPlayerUse(player);
	}

	@Override
	public ItemStack quickMove(PlayerEntity player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasStack()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getStack();
		ItemStack original = stack.copy();
		if (index < VEHICLE_SLOT_COUNT) {
			if (!insertItem(stack, VEHICLE_SLOT_COUNT, slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else if (isAbilityItem(stack)) {
			if (!insertItem(stack, RingVehicleInventory.TOOL_SLOTS, RingVehicleInventory.STORAGE_START, false)
					&& (!hasChest() || !insertItem(stack, RingVehicleInventory.STORAGE_START, VEHICLE_SLOT_COUNT, false))) {
				return ItemStack.EMPTY;
			}
		} else if (stack.get(DataComponentTypes.TOOL) != null) {
			if (!insertItem(stack, 0, TOOL_SLOT_COUNT, false)
					&& (!hasChest() || !insertItem(stack, TOOL_SLOT_COUNT, VEHICLE_SLOT_COUNT, false))) {
				return ItemStack.EMPTY;
			}
		} else if (!hasChest() || !insertItem(stack, TOOL_SLOT_COUNT, VEHICLE_SLOT_COUNT, false)) {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) {
			slot.setStack(ItemStack.EMPTY);
		} else {
			slot.markDirty();
		}
		return original;
	}

	@Override
	public void onClosed(PlayerEntity player) {
		super.onClosed(player);
		inventory.onClose(player);
	}

	private static final class ToolSlot extends Slot {
		private ToolSlot(Inventory inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			return stack.get(DataComponentTypes.TOOL) != null;
		}

		@Override
		public int getMaxItemCount() {
			return 1;
		}
	}

	private static final class AbilitySlot extends Slot {
		private AbilitySlot(Inventory inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			return switch (getIndex()) {
				case RingVehicleInventory.ABILITY_CHEST -> stack.isOf(net.minecraft.item.Items.CHEST);
				case RingVehicleInventory.ABILITY_JUMP -> stack.isOf(net.minecraft.item.Items.RABBIT_FOOT);
				case RingVehicleInventory.ABILITY_DASH -> stack.isOf(net.minecraft.item.Items.SUGAR);
				case RingVehicleInventory.ABILITY_SMASH -> stack.isOf(net.minecraft.item.Items.HEAVY_CORE)
						|| stack.isOf(net.minecraft.item.Items.MACE);
				default -> false;
			};
		}

		@Override
		public int getMaxItemCount() {
			return getIndex() == RingVehicleInventory.ABILITY_JUMP
					|| getIndex() == RingVehicleInventory.ABILITY_DASH ? 64 : 1;
		}
	}

	private final class ClutchSlot extends Slot {
		private ClutchSlot(Inventory inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean isEnabled() {
			return vehicle != null && vehicle.supportsReinforcedClutch();
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			return isEnabled() && stack.isOf(com.xc.echominecart.EchoMinecartRegistry.REINFORCED_CLUTCH);
		}

		@Override
		public int getMaxItemCount() {
			return 1;
		}
	}

	private static boolean isAbilityItem(ItemStack stack) {
		return stack.isOf(net.minecraft.item.Items.CHEST)
				|| stack.isOf(net.minecraft.item.Items.RABBIT_FOOT)
				|| stack.isOf(net.minecraft.item.Items.SUGAR)
				|| stack.isOf(net.minecraft.item.Items.HEAVY_CORE)
				|| stack.isOf(net.minecraft.item.Items.MACE)
				|| stack.isOf(com.xc.echominecart.EchoMinecartRegistry.REINFORCED_CLUTCH);
	}

	public static final class Factory implements ExtendedScreenHandlerFactory<Integer> {
		private final RingVehicleEntity vehicle;

		public Factory(RingVehicleEntity vehicle) {
			this.vehicle = vehicle;
		}

		@Override
		public Integer getScreenOpeningData(ServerPlayerEntity player) {
			return vehicle.getId();
		}

		@Override
		public Text getDisplayName() {
			return Text.translatable("screen.echominecart.ring_vehicle");
		}

		@Override
		public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
			return new RingVehicleScreenHandler(syncId, playerInventory, vehicle);
		}
	}

	private final class StorageSlot extends Slot {
		private StorageSlot(Inventory inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean isEnabled() {
			return hasChest();
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			return hasChest();
		}

		@Override
		public boolean canTakeItems(PlayerEntity playerEntity) {
			return hasChest();
		}
	}
}
