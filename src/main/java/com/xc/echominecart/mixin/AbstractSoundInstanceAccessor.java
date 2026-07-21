package com.xc.echominecart.mixin;

import net.minecraft.client.sound.AbstractSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor for the mutable pitch field used by minecart sound effects. */
@Mixin(AbstractSoundInstance.class)
public interface AbstractSoundInstanceAccessor {
	@Accessor("volume")
	void echominecart$setVolume(float volume);
}
