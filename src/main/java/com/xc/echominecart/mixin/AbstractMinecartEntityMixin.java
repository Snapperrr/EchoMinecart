package com.xc.echominecart.mixin;

import com.xc.echominecart.carriage.CarriageManager;
import com.xc.echominecart.rail.OmniRailBlock;
import com.xc.echominecart.rail.RailPhysics;
import com.xc.echominecart.rail.SpeedRailRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** Routes pre/post rail movement and maximum-speed queries through Echo rail physics. */
@Mixin(AbstractMinecartEntity.class)
public abstract class AbstractMinecartEntityMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void echominecart$applyOmniRailPhysics(CallbackInfo ci) {
		AbstractMinecartEntity cart = (AbstractMinecartEntity) (Object) this;
		if (CarriageManager.isSuppressedModule(cart)) {
			// 隐藏的车厢模块由 CarriageManager 直接定位，不走轨道物理。
			cart.setNoGravity(true);
			return;
		}
		RailPhysics.beforeCartTick(cart);
	}

	/**
	 * 原版 moveOnRail 只认地面轨：它会把矿车 Y 拉回轨道格高度、把速度投影到
	 * SHAPE 的水平轴上（垂直爬升速度直接清零）。贴附态矿车和隐藏车厢模块的
	 * 位移由本模组自己积分，必须整体跳过原版轨道物理。
	 */
	@Inject(method = "moveOnRail", at = @At("HEAD"), cancellable = true)
	private void echominecart$skipVanillaRailPhysics(BlockPos pos, BlockState state, CallbackInfo ci) {
		AbstractMinecartEntity cart = (AbstractMinecartEntity) (Object) this;
		if (RailPhysics.isControlled(cart) || CarriageManager.isSuppressedModule(cart)) {
			ci.cancel();
			return;
		}
		SpeedRailRuntime.beforeMoveOnRail(cart, pos, state);
	}

	@Inject(method = "moveOnRail", at = @At("TAIL"))
	private void echominecart$finishSpeedRailMove(BlockPos pos, BlockState state, CallbackInfo ci) {
		SpeedRailRuntime.afterMoveOnRail((AbstractMinecartEntity) (Object) this, pos, state);
	}

	@Inject(method = "getMaxSpeed", at = @At("RETURN"), cancellable = true)
	private void echominecart$allowConfiguredSpeed(CallbackInfoReturnable<Double> cir) {
		AbstractMinecartEntity cart = (AbstractMinecartEntity) (Object) this;
		cir.setReturnValue(SpeedRailRuntime.maxSpeed(cart, cir.getReturnValue()));
	}

	/**
	 * 渲染器和部分原版逻辑用 snapPositionToRail 把位置吸回轨道格高度，
	 * 会把爬墙/贴顶矿车的渲染位置拽回格底。贴附轨直接返回 null（无吸附）。
	 */
	@Inject(method = "snapPositionToRail", at = @At("HEAD"), cancellable = true)
	private void echominecart$noSnapOnAttachedRails(double x, double y, double z, CallbackInfoReturnable<Vec3d> cir) {
		AbstractMinecartEntity cart = (AbstractMinecartEntity) (Object) this;
		BlockState state = cart.getWorld().getBlockState(BlockPos.ofFloored(x, y, z));
		if (state.getBlock() instanceof OmniRailBlock && state.get(OmniRailBlock.FACE) != Direction.UP) {
			cir.setReturnValue(null);
		}
	}

	@Inject(method = "getPassengerAttachmentPos", at = @At("HEAD"), cancellable = true)
	private void echominecart$spreadStretchedSeats(Entity passenger, EntityDimensions dimensions, float scaleFactor, CallbackInfoReturnable<Vec3d> cir) {
		AbstractMinecartEntity cart = (AbstractMinecartEntity) (Object) this;
		Optional<Vec3d> offset = CarriageManager.passengerOffset(cart, passenger)
				.or(() -> RailPhysics.passengerAttachmentOffset(cart));
		offset.ifPresent(cir::setReturnValue);
	}

	@Inject(method = "collidesWith", at = @At("HEAD"), cancellable = true)
	private void echominecart$suppressModuleCollision(Entity other, CallbackInfoReturnable<Boolean> cir) {
		if (CarriageManager.isPhantomBody((AbstractMinecartEntity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
	private void echominecart$suppressModulePush(CallbackInfoReturnable<Boolean> cir) {
		if (CarriageManager.isPhantomBody((AbstractMinecartEntity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "canHit", at = @At("HEAD"), cancellable = true)
	private void echominecart$suppressModuleHitbox(CallbackInfoReturnable<Boolean> cir) {
		if (CarriageManager.isPhantomBody((AbstractMinecartEntity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
	private void echominecart$writeCartData(NbtCompound nbt, CallbackInfo ci) {
		CarriageManager.writeCartNbt((AbstractMinecartEntity) (Object) this, nbt);
	}

	@Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
	private void echominecart$readCartData(NbtCompound nbt, CallbackInfo ci) {
		CarriageManager.readCartNbt((AbstractMinecartEntity) (Object) this, nbt);
	}
}
