package com.xc.echominecart.client.screen;

import com.xc.echominecart.client.NestedChestClientConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
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
		addStrengthControl(
				centerX - 155, startY,
				Text.translatable("option.echominecart.minecart_fov_strength"),
				NestedChestClientConfig.MIN_MINECART_FOV_STRENGTH,
				NestedChestClientConfig.MAX_MINECART_FOV_STRENGTH,
				NestedChestClientConfig::minecartFovStrength,
				NestedChestClientConfig::setMinecartFovStrength);
		addStrengthControl(
				centerX - 155, startY + 28,
				Text.translatable("option.echominecart.minecart_sway_strength"),
				NestedChestClientConfig.MIN_MINECART_SWAY_STRENGTH,
				NestedChestClientConfig.MAX_MINECART_SWAY_STRENGTH,
				NestedChestClientConfig::minecartSwayStrength,
				NestedChestClientConfig::setMinecartSwayStrength);
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

	private void addStrengthControl(int x, int y, Text label, double min, double max,
			DoubleSupplier getter, DoubleConsumer setter) {
		StrengthValueField valueField = new StrengthValueField(
				this.textRenderer, x + 244, y, 66, 20, label, min, max, setter);
		StrengthSlider slider = new StrengthSlider(
				x, y, 236, 20, label, min, max, getter, setter, valueField::setStrength);
		valueField.bind(slider);
		valueField.setStrength(getter.getAsDouble());
		this.addDrawableChild(slider);
		this.addDrawableChild(valueField);
	}

	private static final class StrengthSlider extends SliderWidget {
		private final Text label;
		private final double min;
		private final double max;
		private final DoubleConsumer setter;
		private final DoubleConsumer valueListener;

		private StrengthSlider(int x, int y, int width, int height, Text label, double min, double max,
				DoubleSupplier getter, DoubleConsumer setter, DoubleConsumer valueListener) {
			super(x, y, width, height, Text.empty(), normalize(getter.getAsDouble(), min, max));
			this.label = label;
			this.min = min;
			this.max = max;
			this.setter = setter;
			this.valueListener = valueListener;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Text.translatable("option.echominecart.percent_value", label, percentageText(currentValue())));
		}

		@Override
		protected void applyValue() {
			setter.accept(currentValue());
			valueListener.accept(currentValue());
			updateMessage();
		}

		private void setStrength(double strength) {
			this.value = normalize(strength, min, max);
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

	private static final class StrengthValueField extends TextFieldWidget {
		private final double min;
		private final double max;
		private final DoubleConsumer setter;
		private StrengthSlider slider;
		private boolean synchronizing;

		private StrengthValueField(net.minecraft.client.font.TextRenderer textRenderer, int x, int y, int width, int height,
				Text label, double min, double max, DoubleConsumer setter) {
			super(textRenderer, x, y, width, height, label);
			this.min = min;
			this.max = max;
			this.setter = setter;
			setMaxLength(8);
			setTextPredicate(StrengthValueField::isValidNumberInput);
			setChangedListener(this::applyTextValue);
		}

		private void bind(StrengthSlider slider) {
			this.slider = slider;
		}

		private void setStrength(double strength) {
			synchronizing = true;
			setText(editablePercentageText(strength));
			synchronizing = false;
		}

		private void applyTextValue(String text) {
			if (synchronizing || text.isBlank() || text.equals(".") || text.equals(",")) {
				return;
			}
			try {
				double percentage = Double.parseDouble(text.replace(',', '.'));
				if (!Double.isFinite(percentage)) {
					return;
				}
				double strength = Math.max(min, Math.min(max, percentage / 100.0D));
				setter.accept(strength);
				if (slider != null) {
					slider.setStrength(strength);
				}
			} catch (NumberFormatException ignored) {
				// Partial decimal input remains editable until it becomes a complete number.
			}
		}

		private static boolean isValidNumberInput(String text) {
			return text.isEmpty() || text.matches("\\d{0,4}([.,]\\d{0,3})?");
		}

		private static String editablePercentageText(double strength) {
			String text = String.format(Locale.ROOT, "%.3f", strength * 100.0D);
			while (text.contains(".") && text.endsWith("0")) {
				text = text.substring(0, text.length() - 1);
			}
			return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
		}
	}
}
