package com.xc.echominecart.mixin;

import com.xc.echominecart.building.BuildingWandManager;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BuildingWandBlockItemMixin {
	@Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;",
			at = @At("RETURN"))
	private void echoMinecart$afterBlockPlaced(ItemPlacementContext context,
			CallbackInfoReturnable<ActionResult> callback) {
		if (!callback.getReturnValue().isAccepted()
				|| !(context.getWorld() instanceof ServerWorld world)
				|| !(context.getPlayer() instanceof ServerPlayerEntity player)) {
			return;
		}
		BlockPos pos = context.getBlockPos();
		BuildingWandManager.onBlockPlaced(player, pos, world.getBlockState(pos));
	}
}
