package com.xc.echominecart.client.screen;

import com.xc.echominecart.network.SpeedRailSetPayload;
import com.xc.echominecart.rail.SpeedRailStorage;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public final class SpeedRailSettingsScreen extends Screen {
	private final Screen parent;
	private final BlockPos railPos;
	private final double initialSpeed;
	private TextFieldWidget speedField;
	private Text status = Text.empty();

	public SpeedRailSettingsScreen(Screen parent, BlockPos railPos, double initialSpeed) {
		super(Text.translatable("screen.echominecart.speed_rail"));
		this.parent = parent;
		this.railPos = railPos;
		this.initialSpeed = initialSpeed;
	}

	@Override
	protected void init() {
		int centerX = width / 2;
		int top = height / 2 - 58;
		speedField = new TextFieldWidget(textRenderer, centerX - 100, top + 28, 200, 20,
				Text.translatable("option.echominecart.speed_rail_speed"));
		speedField.setMaxLength(32);
		speedField.setTextPredicate(SpeedRailSettingsScreen::validPartialNumber);
		speedField.setText(formatSpeed(initialSpeed));
		addDrawableChild(speedField);
		setInitialFocus(speedField);

		double[] presets = {-8.0D, -1.0D, 0.0D, 1.0D, 8.0D};
		for (int index = 0; index < presets.length; index++) {
			double preset = presets[index];
			addDrawableChild(ButtonWidget.builder(Text.literal(formatSpeed(preset)), button -> {
				speedField.setText(formatSpeed(preset));
				status = Text.empty();
			}).dimensions(centerX - 100 + index * 41, top + 56, 36, 20).build());
		}

		addDrawableChild(ButtonWidget.builder(Text.translatable("button.echominecart.speed_rail_apply"), button -> apply())
				.dimensions(centerX - 100, top + 88, 96, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> close())
				.dimensions(centerX + 4, top + 88, 96, 20)
				.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);
		int centerX = width / 2;
		int top = height / 2 - 58;
		context.drawCenteredTextWithShadow(textRenderer, title, centerX, top, 0xFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.translatable("option.echominecart.speed_rail_speed"),
				centerX - 100, top + 16, 0xA0A0A0);
		context.drawCenteredTextWithShadow(textRenderer,
				Text.translatable("option.echominecart.speed_rail_range",
						formatSpeed(SpeedRailStorage.MAX_ABSOLUTE_SPEED), formatSpeed(SpeedRailStorage.MAX_ABSOLUTE_SPEED)),
				centerX, top + 116, 0x808080);
		if (!status.getString().isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, status, centerX, top + 132, 0xFF6060);
		}
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			apply();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
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

	private void apply() {
		try {
			double parsed = Double.parseDouble(speedField.getText().trim());
			if (!Double.isFinite(parsed) || Math.abs(parsed) > SpeedRailStorage.MAX_ABSOLUTE_SPEED) {
				status = Text.translatable("error.echominecart.speed_rail_range");
				return;
			}
			ClientPlayNetworking.send(new SpeedRailSetPayload(railPos.asLong(), parsed));
			close();
		} catch (NumberFormatException ignored) {
			status = Text.translatable("error.echominecart.speed_rail_number");
		}
	}

	private static boolean validPartialNumber(String value) {
		return value.isEmpty() || value.matches("[+\\-0-9.eE]*");
	}

	private static String formatSpeed(double speed) {
		String text = String.format(Locale.ROOT, "%.4f", speed);
		while (text.contains(".") && text.endsWith("0")) {
			text = text.substring(0, text.length() - 1);
		}
		return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
	}
}
