package com.xc.echominecart.item;

import com.xc.echominecart.carriage.CarriageManager;
import com.xc.echominecart.haul.HaulManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.world.World;

/**
 * 搬运绑定工具：潜行右键矿车，把整节车厢上方的方块列和实体绑定成货物。
 * 车厢开动后货物随车移动，停稳且放置条件满足时原样放回。
 */
/** Selects a structure and binds the captured haul to a minecart or carriage group. */
public final class TransportBinderItem extends Item {
	public TransportBinderItem(Settings settings) {
		super(settings);
	}

	public ActionResult useOnMinecart(PlayerEntity player, World world, AbstractMinecartEntity minecart) {
		if (!player.isSneaking()) {
			if (!world.isClient()) {
				player.sendMessage(Text.literal("潜行右键矿车即可绑定车厢上方的建筑。"), true);
			}
			return ActionResult.SUCCESS;
		}
		if (!world.isClient()) {
			HaulManager.bind(player, CarriageManager.snapshotFor(minecart));
		}
		return ActionResult.SUCCESS;
	}
}
