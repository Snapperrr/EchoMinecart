package com.xc.echominecart.item;

import com.xc.echominecart.carriage.CarriageManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.world.World;

/**
 * 矿车拆分/合并工具：潜行右键依次选中两个矿车轮组。
 * 同一节车厢里的两个轮组会被切开并禁止自动合并；
 * 不同车厢的两个轮组会解除禁令，允许靠近后重新合并。
 * 矿车不是 LivingEntity，useOnEntity 不会触发，
 * 实际分发在 EchoMinecartRegistry 的 UseEntityCallback 里。
 */
public final class MinecartLinkToolItem extends Item {
	public MinecartLinkToolItem(Settings settings) {
		super(settings);
	}

	public ActionResult useOnMinecart(PlayerEntity player, World world, AbstractMinecartEntity minecart) {
		if (!player.isSneaking()) {
			if (!world.isClient()) {
				player.sendMessage(Text.literal("潜行右键依次点选两个矿车轮组即可拆分或允许合并。"), true);
			}
			return ActionResult.SUCCESS;
		}
		if (!world.isClient()) {
			CarriageManager.useLinkTool(player, minecart);
		}
		return ActionResult.SUCCESS;
	}
}
