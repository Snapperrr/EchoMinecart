package com.xc.echominecart.mixin;

import com.xc.echominecart.client.NestedChestOverlay;
import com.xc.echominecart.client.NestedChestScreenBridge;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forwards container render/input events to the nested-chest overlay when supported. */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> implements NestedChestScreenBridge {
	@Shadow
	@Final
	protected T handler;

	@Shadow
	protected int x;

	@Shadow
	protected int y;

	@Inject(method = "drawMouseoverTooltip", at = @At("HEAD"), cancellable = true)
	private void echominecart$hideVanillaTooltip(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
		if (NestedChestOverlay.supports(this.handler) && NestedChestOverlay.blocksVanillaTooltip(mouseX, mouseY)) {
			NestedChestOverlay.render(this.handler, context, mouseX, mouseY);
			ci.cancel();
		}
	}

	@Inject(method = "drawMouseoverTooltip", at = @At("TAIL"))
	private void echominecart$render(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
		if (NestedChestOverlay.supports(this.handler)) {
			NestedChestOverlay.render(this.handler, context, mouseX, mouseY);
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void echominecart$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (NestedChestOverlay.supports(this.handler) && NestedChestOverlay.mouseClicked(this.handler, mouseX, mouseY, button)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
	private void echominecart$mouseReleased(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (NestedChestOverlay.supports(this.handler) && NestedChestOverlay.mouseReleased(mouseX, mouseY, button)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void echominecart$mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY, CallbackInfoReturnable<Boolean> cir) {
		if (NestedChestOverlay.supports(this.handler) && NestedChestOverlay.mouseDragged(mouseX, mouseY, button)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void echominecart$keyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (NestedChestOverlay.supports(this.handler) && NestedChestOverlay.keyPressed(this.handler, keyCode, scanCode, modifiers)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void echominecart$removed(CallbackInfo ci) {
		if (NestedChestOverlay.supports(this.handler)) {
			NestedChestOverlay.reset();
		}
	}

	@Override
	public int echominecart$getRootX() {
		return this.x;
	}

	@Override
	public int echominecart$getRootY() {
		return this.y;
	}
}
