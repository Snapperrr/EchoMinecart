package com.xc.echominecart.mixin;

import com.xc.echominecart.rail.RailPhysics;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies carriage-wide render scaling and offsets around the elected anchor cart. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {
	@Inject(method = "getLight", at = @At("HEAD"), cancellable = true)
	private void echominecart$sampleAttachedPassengerLight(T entity, float tickDelta, CallbackInfoReturnable<Integer> cir) {
		if (!(entity.getVehicle() instanceof AbstractMinecartEntity minecart)) {
			return;
		}
		RailPhysics.findContact(minecart.getWorld(), minecart)
				.filter(contact -> contact.face() != Direction.UP)
				.ifPresent(contact -> cir.setReturnValue(attachedPassengerLight(entity, minecart, contact, tickDelta)));
	}

	private int attachedPassengerLight(T entity, AbstractMinecartEntity minecart, RailPhysics.RailContact contact, float tickDelta) {
		World world = entity.getWorld();
		Vec3d sample = lightSamplePoint(minecart, contact, tickDelta);
		int block = entity.isOnFire() ? 15 : bestLight(world, LightType.BLOCK, sample);
		int sky = bestLight(world, LightType.SKY, sample);
		return LightmapTextureManager.pack(block, sky);
	}

	private int bestLight(World world, LightType type, Vec3d sample) {
		BlockPos center = BlockPos.ofFloored(sample);
		int best = world.getLightLevel(type, center);
		for (Direction direction : Direction.values()) {
			best = Math.max(best, world.getLightLevel(type, center.offset(direction)));
		}
		return best;
	}

	private Vec3d lightSamplePoint(AbstractMinecartEntity minecart, RailPhysics.RailContact contact, float tickDelta) {
		Direction face = contact.face();
		Vec3d cartPos = minecart.getLerpedPos(tickDelta);
		Vec3d surface = RailPhysics.surfacePoint(contact.pos(), face);
		Vec3d normal = Vec3d.of(face.getVector());
		if (face == Direction.DOWN) {
			return new Vec3d(cartPos.x, surface.y - 0.64D, cartPos.z);
		}
		double x = face.getAxis() == Direction.Axis.X ? surface.x : cartPos.x;
		double z = face.getAxis() == Direction.Axis.Z ? surface.z : cartPos.z;
		return new Vec3d(x, cartPos.y + 0.45D, z).add(normal.multiply(0.62D));
	}
}
