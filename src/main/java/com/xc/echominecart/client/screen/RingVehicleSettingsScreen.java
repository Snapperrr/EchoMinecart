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

/** Edits ring-vehicle steering and FOV multipliers independently from ordinary minecart settings. */
public final class RingVehicleSettingsScreen extends Screen {
	private final Screen parent;

	public RingVehicleSettingsScreen(Screen parent) {
		super(Text.translatable("screen.echominecart.ring_vehicle_settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int centerX = width / 2;
		int startY = height / 2 - 28;
		addSettingControl(
				centerX - 155, startY,
				Text.translatable("option.echominecart.ring_vehicle_steering"),
				NestedChestClientConfig.MIN_RING_VEHICLE_STEERING_SENSITIVITY,
				NestedChestClientConfig.MAX_RING_VEHICLE_STEERING_SENSITIVITY,
				NestedChestClientConfig::ringVehicleSteeringSensitivity,
				NestedChestClientConfig::setRingVehicleSteeringSensitivity);
		addSettingControl(
				centerX - 155, startY + 28,
				Text.translatable("option.echominecart.ring_vehicle_fov_strength"),
				NestedChestClientConfig.MIN_RING_VEHICLE_FOV_STRENGTH,
				NestedChestClientConfig.MAX_RING_VEHICLE_FOV_STRENGTH,
				NestedChestClientConfig::ringVehicleFovStrength,
				NestedChestClientConfig::setRingVehicleFovStrength);
		addDrawableChild(ButtonWidget.builder(Text.translatable("button.echominecart.reset_defaults"), button -> {
			NestedChestClientConfig.resetRingVehicleSettings();
			clearAndInit();
		}).dimensions(centerX - 155, startY + 64, 150, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
				.dimensions(centerX + 5, startY + 64, 150, 20).build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 28, 0xFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer,
				Text.translatable("screen.echominecart.ring_vehicle_settings.hint"), width / 2, 48, 0xA0A0A0);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private void addSettingControl(int x, int y, Text label, double min, double max,
			DoubleSupplier getter, DoubleConsumer setter) {
		SettingValueField valueField = new SettingValueField(
				textRenderer, x + 244, y, 66, 20, label, min, max, setter);
		SettingSlider slider = new SettingSlider(
				x, y, 236, 20, label, min, max, getter, setter, valueField::setSetting);
		valueField.bind(slider);
		valueField.setSetting(getter.getAsDouble());
		addDrawableChild(slider);
		addDrawableChild(valueField);
	}

	private static final class SettingSlider extends SliderWidget {
		private final Text label;
		private final double min;
		private final double max;
		private final DoubleConsumer setter;
		private final DoubleConsumer valueListener;

		private SettingSlider(int x, int y, int width, int height, Text label, double min, double max,
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
			setMessage(Text.translatable("option.echominecart.percent_value", label,
					String.format(Locale.ROOT, "%.0f%%", currentValue() * 100.0D)));
		}

		@Override
		protected void applyValue() {
			double setting = currentValue();
			setter.accept(setting);
			valueListener.accept(setting);
			updateMessage();
		}

		private void setSetting(double setting) {
			value = normalize(setting, min, max);
			updateMessage();
		}

		private double currentValue() {
			return min + (max - min) * value;
		}

		private static double normalize(double value, double min, double max) {
			return max <= min ? 0.0D : Math.max(0.0D, Math.min(1.0D, (value - min) / (max - min)));
		}
	}

	private static final class SettingValueField extends TextFieldWidget {
		private final double min;
		private final double max;
		private final DoubleConsumer setter;
		private SettingSlider slider;
		private boolean synchronizing;

		private SettingValueField(net.minecraft.client.font.TextRenderer textRenderer,
				int x, int y, int width, int height, Text label, double min, double max, DoubleConsumer setter) {
			super(textRenderer, x, y, width, height, label);
			this.min = min;
			this.max = max;
			this.setter = setter;
			setMaxLength(8);
			setTextPredicate(text -> text.isEmpty() || text.matches("\\d{0,4}([.,]\\d{0,3})?"));
			setChangedListener(this::applyTextValue);
		}

		private void bind(SettingSlider slider) {
			this.slider = slider;
		}

		private void setSetting(double setting) {
			synchronizing = true;
			String text = String.format(Locale.ROOT, "%.3f", setting * 100.0D);
			while (text.contains(".") && text.endsWith("0")) {
				text = text.substring(0, text.length() - 1);
			}
			setText(text.endsWith(".") ? text.substring(0, text.length() - 1) : text);
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
				double setting = Math.max(min, Math.min(max, percentage / 100.0D));
				setter.accept(setting);
				if (slider != null) {
					slider.setSetting(setting);
				}
			} catch (NumberFormatException ignored) {
				// Partial decimal input remains editable.
			}
		}
	}
}
