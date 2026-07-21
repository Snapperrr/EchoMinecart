package com.xc.echominecart.ringvehicle;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.collection.DefaultedList;

/** Fixed-layout module inventory with versioned migration and overflow recovery. */
public final class RingVehicleInventory implements Inventory {
	public static final int TOOL_SLOTS = 3;
	public static final int ABILITY_SLOTS = 4;
	public static final int ABILITY_CHEST = TOOL_SLOTS;
	public static final int ABILITY_JUMP = TOOL_SLOTS + 1;
	public static final int ABILITY_DASH = TOOL_SLOTS + 2;
	public static final int ABILITY_SMASH = TOOL_SLOTS + 3;
	public static final int CLUTCH_SLOT = TOOL_SLOTS + ABILITY_SLOTS;
	public static final int STORAGE_START = CLUTCH_SLOT + 1;
	public static final int STORAGE_SLOTS = 27;
	public static final int SIZE = STORAGE_START + STORAGE_SLOTS;
	public static final int DATA_VERSION = 4;
	private static final int OLD_TOOL_SLOTS = 4;
	private static final int VERSION_3_SIZE = OLD_TOOL_SLOTS + ABILITY_SLOTS + 1 + STORAGE_SLOTS;
	private static final int VERSION_2_SIZE = OLD_TOOL_SLOTS + ABILITY_SLOTS + STORAGE_SLOTS;
	private static final int LEGACY_SIZE = OLD_TOOL_SLOTS + STORAGE_SLOTS;

	private final RingVehicleEntity vehicle;
	private final DefaultedList<ItemStack> stacks = DefaultedList.ofSize(SIZE, ItemStack.EMPTY);
	private ItemStack migrationOverflow = ItemStack.EMPTY;

	RingVehicleInventory(RingVehicleEntity vehicle) {
		this.vehicle = vehicle;
	}

	DefaultedList<ItemStack> stacks() {
		return stacks;
	}

	void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		LoadedInventory loaded = readInventory(nbt, registries);
		stacks.clear();
		for (int slot = 0; slot < loaded.stacks().size(); slot++) {
			stacks.set(slot, loaded.stacks().get(slot));
		}
		migrationOverflow = loaded.overflow();
	}

	static DefaultedList<ItemStack> readStacks(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		return readInventory(nbt, registries).stacks();
	}

	ItemStack takeMigrationOverflow() {
		ItemStack result = migrationOverflow;
		migrationOverflow = ItemStack.EMPTY;
		return result;
	}

	private static LoadedInventory readInventory(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		DefaultedList<ItemStack> result = DefaultedList.ofSize(SIZE, ItemStack.EMPTY);
		int version = nbt.getInt("InventoryVersion");
		if (version >= DATA_VERSION) {
			Inventories.readNbt(nbt, result, registries);
			return new LoadedInventory(result, ItemStack.EMPTY);
		}

		if (version >= 3) {
			DefaultedList<ItemStack> previous = DefaultedList.ofSize(VERSION_3_SIZE, ItemStack.EMPTY);
			Inventories.readNbt(nbt, previous, registries);
			copyOldTools(previous, result);
			copyRange(previous, OLD_TOOL_SLOTS, result, TOOL_SLOTS, ABILITY_SLOTS);
			result.set(CLUTCH_SLOT, previous.get(OLD_TOOL_SLOTS + ABILITY_SLOTS));
			copyRange(previous, OLD_TOOL_SLOTS + ABILITY_SLOTS + 1,
					result, STORAGE_START, STORAGE_SLOTS);
			return new LoadedInventory(result, previous.get(OLD_TOOL_SLOTS - 1));
		}

		if (version >= 2) {
			DefaultedList<ItemStack> previous = DefaultedList.ofSize(VERSION_2_SIZE, ItemStack.EMPTY);
			Inventories.readNbt(nbt, previous, registries);
			copyOldTools(previous, result);
			copyRange(previous, OLD_TOOL_SLOTS, result, TOOL_SLOTS, ABILITY_SLOTS);
			copyRange(previous, OLD_TOOL_SLOTS + ABILITY_SLOTS,
					result, STORAGE_START, STORAGE_SLOTS);
			return new LoadedInventory(result, previous.get(OLD_TOOL_SLOTS - 1));
		}

		DefaultedList<ItemStack> legacy = DefaultedList.ofSize(LEGACY_SIZE, ItemStack.EMPTY);
		Inventories.readNbt(nbt, legacy, registries);
		copyOldTools(legacy, result);
		if (nbt.getBoolean("ChestAttached")) {
			result.set(ABILITY_CHEST, new ItemStack(Items.CHEST));
		}
		copyRange(legacy, OLD_TOOL_SLOTS, result, STORAGE_START, STORAGE_SLOTS);
		return new LoadedInventory(result, legacy.get(OLD_TOOL_SLOTS - 1));
	}

	private static void copyOldTools(DefaultedList<ItemStack> source, DefaultedList<ItemStack> target) {
		copyRange(source, 0, target, 0, TOOL_SLOTS);
	}

	private static void copyRange(DefaultedList<ItemStack> source, int sourceStart,
			DefaultedList<ItemStack> target, int targetStart, int count) {
		for (int offset = 0; offset < count; offset++) {
			target.set(targetStart + offset, source.get(sourceStart + offset));
		}
	}

	private record LoadedInventory(DefaultedList<ItemStack> stacks, ItemStack overflow) {
	}

	@Override
	public int size() {
		return SIZE;
	}

	@Override
	public boolean isEmpty() {
		for (ItemStack stack : stacks) {
			if (!stack.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getStack(int slot) {
		return slot >= 0 && slot < stacks.size() ? stacks.get(slot) : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeStack(int slot, int amount) {
		ItemStack result = Inventories.splitStack(stacks, slot, amount);
		if (!result.isEmpty()) {
			markDirty();
		}
		return result;
	}

	@Override
	public ItemStack removeStack(int slot) {
		ItemStack result = Inventories.removeStack(stacks, slot);
		if (!result.isEmpty()) {
			markDirty();
		}
		return result;
	}

	@Override
	public void setStack(int slot, ItemStack stack) {
		if (slot < 0 || slot >= stacks.size()) {
			return;
		}
		stacks.set(slot, stack);
		stack.capCount(getMaxCount(stack));
		markDirty();
	}

	@Override
	public void markDirty() {
		vehicle.markInventoryDirty();
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return vehicle.isAlive() && player.squaredDistanceTo(vehicle) <= 64.0D;
	}

	@Override
	public void clear() {
		stacks.clear();
		markDirty();
	}
}
