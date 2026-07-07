package com.xc.echominecart.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 充能棒：潜行右键矿车，沿"玩家 → 矿车"方向推一把，
 * 用来在没有充能轨的地方直接启动矿车（含墙面/天花板轨）。
 */
public final class BoosterRodItem extends Item {
	private static final double PUSH_STRENGTH = 0.5D;

	public BoosterRodItem(Settings settings) {
		super(settings);
	}

	public ActionResult useOnMinecart(PlayerEntity player, World world, AbstractMinecartEntity minecart) {
		if (!player.isSneaking()) {
			if (!world.isClient()) {
				player.sendMessage(Text.literal("潜行右键矿车即可沿你面向矿车的方向推动它。"), true);
			}
			return ActionResult.SUCCESS;
		}
		if (!world.isClient()) {
			Vec3d push = minecart.getPos().subtract(player.getPos());
			if (push.lengthSquared() < 1.0E-4D) {
				push = player.getRotationVec(1.0F);
			}
			minecart.setVelocity(minecart.getVelocity().add(push.normalize().multiply(PUSH_STRENGTH)));
			minecart.velocityModified = true;
			player.sendMessage(Text.literal("已推动矿车。"), true);
		}
		return ActionResult.SUCCESS;
	}
}
