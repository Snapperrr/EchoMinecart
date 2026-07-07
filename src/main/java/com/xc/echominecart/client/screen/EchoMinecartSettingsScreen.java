package com.xc.echominecart.client.screen;

import com.xc.echominecart.client.NestedChestClientConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

public class EchoMinecartSettingsScreen extends Screen {
	private final Screen parent;

	public EchoMinecartSettingsScreen(Screen parent) {
		super(Text.translatable("screen.echominecart.settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int startY = this.height / 2 - 38;
		this.addDrawableChild(new StrengthSlider(
				centerX - 155,
				startY,
				310,
				20,
				Text.translatable("option.echominecart.minecart_fov_strength"),
				NestedChestClientConfig.MIN_MINECART_FOV_STRENGTH,
				NestedChestClientConfig.MAX_MINECART_FOV_STRENGTH,
				NestedChestClientConfig::minecartFovStrength,
				NestedChestClientConfig::setMinecartFovStrength));
		this.addDrawableChild(new StrengthSlider(
				centerX - 155,
				startY + 28,
				310,
				20,
				Text.translatable("option.echominecart.minecart_sway_strength"),
				NestedChestClientConfig.MIN_MINECART_SWAY_STRENGTH,
				NestedChestClientConfig.MAX_MINECART_SWAY_STRENGTH,
				NestedChestClientConfig::minecartSwayStrength,
				NestedChestClientConfig::setMinecartSwayStrength));
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("button.echominecart.reset_defaults"), button -> {
					NestedChestClientConfig.resetMinecartRideEffects();
					this.clearAndInit();
				})
				.dimensions(centerX - 155, startY + 64, 150, 20)
				.build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
				.dimensions(centerX + 5, startY + 64, 150, 20)
				.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 24, 0xFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("screen.echominecart.settings.hint"), this.width / 2, 44, 0xA0A0A0);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(parent);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private static final class StrengthSlider extends SliderWidget {
		private final Text label;
		private final double min;
		private final double max;
		private final DoubleConsumer setter;

		private StrengthSlider(int x, int y, int width, int height, Text label, double min, double max, DoubleSupplier getter, DoubleConsumer setter) {
			super(x, y, width, height, Text.empty(), normalize(getter.getAsDouble(), min, max));
			this.label = label;
			this.min = min;
			this.max = max;
			this.setter = setter;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Text.translatable("option.echominecart.percent_value", label, percentageText(currentValue())));
		}

		@Override
		protected void applyValue() {
			setter.accept(currentValue());
			updateMessage();
		}

		private double currentValue() {
			return min + (max - min) * value;
		}

		private static double normalize(double value, double min, double max) {
			if (max <= min) {
				return 0.0D;
			}
			return Math.max(0.0D, Math.min(1.0D, (value - min) / (max - min)));
		}

		private static String percentageText(double value) {
			return String.format(Locale.ROOT, "%.0f%%", value * 100.0D);
		}
	}
}
