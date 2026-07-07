package com.xc.echominecart.mixin;

import com.xc.echominecart.rail.RailPhysics;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InGameOverlayRenderer.class)
public abstract class InGameOverlayRendererMixin {
	@Inject(method = "getInWallBlockState", at = @At("HEAD"), cancellable = true)
	private static void echominecart$ignoreVanillaWallOverlayOnAttachedRails(PlayerEntity player, CallbackInfoReturnable<BlockState> cir) {
		if (!(player.getVehicle() instanceof AbstractMinecartEntity minecart)) {
			return;
		}
		RailPhysics.findContact(minecart.getWorld(), minecart)
				.filter(contact -> contact.face() != Direction.UP)
				.ifPresent(contact -> cir.setReturnValue(null));
	}
}
