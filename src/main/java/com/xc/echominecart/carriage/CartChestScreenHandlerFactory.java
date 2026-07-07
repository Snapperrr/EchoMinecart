package com.xc.echominecart.carriage;

import com.xc.echominecart.screen.ConnectedChestScreenHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class CartChestScreenHandlerFactory implements ExtendedScreenHandlerFactory<Integer> {
	private final Inventory inventory;
	private final int chestCount;

	public CartChestScreenHandlerFactory(Inventory inventory, int chestCount) {
		this.inventory = inventory;
		this.chestCount = chestCount;
	}

	@Override
	public Integer getScreenOpeningData(ServerPlayerEntity player) {
		return inventory.size();
	}

	@Override
	public Text getDisplayName() {
		return Text.literal("矿车连接箱 x" + chestCount);
	}

	@Override
	public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
		return new ConnectedChestScreenHandler(syncId, playerInventory, inventory);
	}
}
