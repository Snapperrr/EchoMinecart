package com.xc.echominecart.item;

import com.xc.echominecart.building.BuildingWandManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;

import java.util.List;

/** Selects and edits bounded regions through the server-side building-wand manager. */
public final class BuildingWandItem extends Item {
	public BuildingWandItem(Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		PlayerEntity player = context.getPlayer();
		if (player == null) {
			return ActionResult.PASS;
		}
		if (context.getWorld().isClient) {
			return ActionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayerEntity serverPlayer)) {
			return ActionResult.FAIL;
		}
		if (player.isSneaking()) {
			BuildingWandManager.clearSelection(serverPlayer);
		} else {
			BuildingWandManager.selectCorner(serverPlayer, context.getBlockPos());
		}
		return ActionResult.SUCCESS;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context,
			List<Text> tooltip, TooltipType type) {
		tooltip.add(Text.translatable("tooltip.echominecart.building_wand.select")
				.formatted(Formatting.GRAY));
		tooltip.add(Text.translatable("tooltip.echominecart.building_wand.fill")
				.formatted(Formatting.DARK_GRAY));
		tooltip.add(Text.translatable("tooltip.echominecart.building_wand.clear")
				.formatted(Formatting.DARK_GRAY));
		tooltip.add(Text.translatable("tooltip.echominecart.building_wand.cancel")
				.formatted(Formatting.DARK_GRAY));
	}
}
