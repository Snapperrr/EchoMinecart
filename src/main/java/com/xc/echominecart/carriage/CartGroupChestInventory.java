package com.xc.echominecart.carriage;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * 把车厢组里所有加装箱子的 27 格页拼成一个连续库存，
 * 直接套用 Chest 迁移过来的连接箱滚动 UI。
 * 槽位按矿车 UUID 稳定排序，多次打开时每个箱子对应固定页。
 */
public final class CartGroupChestInventory implements Inventory {
	private final List<AbstractMinecartEntity> carts;

	public CartGroupChestInventory(List<AbstractMinecartEntity> carts) {
		this.carts = List.copyOf(carts);
	}

	@Override
	public int size() {
		int total = 0;
		for (AbstractMinecartEntity cart : carts) {
			total += CartAttachment.of(cart).attachedChests() * CartAttachment.CHEST_PAGE_SIZE;
		}
		return total;
	}

	@Override
	public boolean isEmpty() {
		for (int i = 0; i < size(); i++) {
			if (!getStack(i).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getStack(int slot) {
		SlotRef ref = resolve(slot);
		return ref == null ? ItemStack.EMPTY : ref.stacks().get(ref.index());
	}

	@Override
	public ItemStack removeStack(int slot, int amount) {
		SlotRef ref = resolve(slot);
		if (ref == null || ref.stacks().get(ref.index()).isEmpty()) {
			return ItemStack.EMPTY;
		}
		return ref.stacks().get(ref.index()).split(amount);
	}

	@Override
	public ItemStack removeStack(int slot) {
		SlotRef ref = resolve(slot);
		if (ref == null) {
			return ItemStack.EMPTY;
		}
		ItemStack removed = ref.stacks().get(ref.index());
		ref.stacks().set(ref.index(), ItemStack.EMPTY);
		return removed;
	}

	@Override
	public void setStack(int slot, ItemStack stack) {
		SlotRef ref = resolve(slot);
		if (ref != null) {
			ref.stacks().set(ref.index(), stack);
		}
	}

	@Override
	public void markDirty() {
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		for (AbstractMinecartEntity cart : carts) {
			if (cart.isAlive() && cart.getWorld() == player.getWorld() && cart.squaredDistanceTo(player) <= 64.0D) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void clear() {
		for (AbstractMinecartEntity cart : carts) {
			List<ItemStack> stacks = CartAttachment.of(cart).chestStacks();
			for (int i = 0; i < stacks.size(); i++) {
				stacks.set(i, ItemStack.EMPTY);
			}
		}
	}

	private SlotRef resolve(int slot) {
		if (slot < 0) {
			return null;
		}
		for (AbstractMinecartEntity cart : carts) {
			CartAttachment attachment = CartAttachment.of(cart);
			int pageSlots = attachment.attachedChests() * CartAttachment.CHEST_PAGE_SIZE;
			if (slot < pageSlots) {
				attachment.normalize();
				return new SlotRef(attachment.chestStacks(), slot);
			}
			slot -= pageSlots;
		}
		return null;
	}

	private record SlotRef(List<ItemStack> stacks, int index) {
	}
}
