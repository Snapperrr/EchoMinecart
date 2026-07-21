package com.xc.echominecart.carriage;

import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单节矿车（轮组）的附加数据：加装箱子的数量与内容、
 * 扩充车厢里的模块位置缓存、岔路拉伸倒计时。
 * 箱子部分随矿车实体 NBT 持久化。
 */
/** Reads and writes per-minecart link, anchor, storage, and runner metadata in command tags/NBT. */
public final class CartAttachment {
	public static final int CHEST_PAGE_SIZE = 27;
	private static final Map<UUID, CartAttachment> BY_CART = new HashMap<>();

	int attachedChests;
	final List<ItemStack> chestStacks = new ArrayList<>();
	boolean suppressedAsModule;
	boolean wasSuppressedAsModule;
	int junctionStretchTicks;
	UUID anchorCart;
	BlockPos moduleOffset;

	private CartAttachment() {
	}

	public static CartAttachment of(AbstractMinecartEntity cart) {
		return of(cart.getUuid());
	}

	public static CartAttachment of(UUID cartId) {
		return BY_CART.computeIfAbsent(cartId, id -> new CartAttachment());
	}

	public static CartAttachment peek(UUID cartId) {
		return BY_CART.get(cartId);
	}

	public static void remove(UUID cartId) {
		BY_CART.remove(cartId);
	}

	public int attachedChests() {
		return attachedChests;
	}

	public List<ItemStack> chestStacks() {
		return chestStacks;
	}

	/** 加装一个箱子：占用一个座位并扩出一页 27 格存储。 */
	public void addChestPage() {
		attachedChests++;
		for (int i = 0; i < CHEST_PAGE_SIZE; i++) {
			chestStacks.add(ItemStack.EMPTY);
		}
	}

	/** 把最后 moveCount 页箱子连同内容搬给另一节轮组。 */
	public void movePagesTo(CartAttachment sink, int moveCount) {
		normalize();
		for (int i = 0; i < moveCount && attachedChests > 0; i++) {
			int pageStart = chestStacks.size() - CHEST_PAGE_SIZE;
			List<ItemStack> page = chestStacks.subList(pageStart, pageStart + CHEST_PAGE_SIZE);
			sink.chestStacks.addAll(new ArrayList<>(page));
			page.clear();
			attachedChests--;
			sink.attachedChests++;
		}
	}

	public void clearChests() {
		chestStacks.clear();
		attachedChests = 0;
	}

	/** 保证存储条数与箱子页数一致（读档或异常后的自愈）。 */
	public void normalize() {
		int expected = attachedChests * CHEST_PAGE_SIZE;
		while (chestStacks.size() < expected) {
			chestStacks.add(ItemStack.EMPTY);
		}
	}

	public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		if (attachedChests <= 0) {
			return;
		}
		normalize();
		nbt.putInt("EchoAttachedChests", attachedChests);
		DefaultedList<ItemStack> stacks = DefaultedList.ofSize(chestStacks.size(), ItemStack.EMPTY);
		for (int i = 0; i < chestStacks.size(); i++) {
			stacks.set(i, chestStacks.get(i));
		}
		NbtCompound chestNbt = new NbtCompound();
		Inventories.writeNbt(chestNbt, stacks, true, registries);
		nbt.put("EchoChestItems", chestNbt);
	}

	public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		if (!nbt.contains("EchoAttachedChests", NbtElement.INT_TYPE)) {
			return;
		}
		attachedChests = Math.max(0, nbt.getInt("EchoAttachedChests"));
		chestStacks.clear();
		DefaultedList<ItemStack> stacks = DefaultedList.ofSize(attachedChests * CHEST_PAGE_SIZE, ItemStack.EMPTY);
		if (nbt.contains("EchoChestItems", NbtElement.COMPOUND_TYPE)) {
			Inventories.readNbt(nbt.getCompound("EchoChestItems"), stacks, registries);
		}
		chestStacks.addAll(stacks);
	}
}
