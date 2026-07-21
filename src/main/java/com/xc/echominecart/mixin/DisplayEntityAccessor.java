package com.xc.echominecart.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.AffineTransformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes display interpolation timing required by carried-block visual synchronization. */
@Mixin(DisplayEntity.class)
public interface DisplayEntityAccessor {
	@Invoker("setTransformation")
	void echominecart$setTransformation(AffineTransformation transformation);

	@Invoker("setInterpolationDuration")
	void echominecart$setInterpolationDuration(int duration);
}
