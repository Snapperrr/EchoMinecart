package com.xc.echominecart.client.screen;

import com.xc.echominecart.network.RingVehicleActionPayload;
import com.xc.echominecart.screen.RingVehicleScreenHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

/** Draws ring-vehicle module slots, storage, durability, and the server-backed mining toggle. */
public final class RingVehicleScreen extends HandledScreen<RingVehicleScreenHandler> {
	private static final int PANEL_WIDTH = 214;
	private static final int PANEL_HEIGHT = 258;
	private static final int TOOL_ACCENT = 0xFFC57B3A;
	private static final int ABILITY_ACCENT = 0xFF4D8E87;
	private static final int ACTIVE_ACCENT = 0xFF55B875;
	private static final ItemStack MINING_ICON = Items.IRON_PICKAXE.getDefaultStack();
	private static final String[] EQUIPMENT_TOOLTIPS = {
			"slot.echominecart.ring_vehicle_tool",
			"slot.echominecart.ring_vehicle_tool",
			"slot.echominecart.ring_vehicle_tool",
			"slot.echominecart.ring_vehicle_chest",
			"slot.echominecart.ring_vehicle_jump",
			"slot.echominecart.ring_vehicle_dash",
			"slot.echominecart.ring_vehicle_smash",
			"slot.echominecart.ring_vehicle_clutch"
	};

	private MiningToggleWidget miningButton;

	public RingVehicleScreen(RingVehicleScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		backgroundWidth = PANEL_WIDTH;
		backgroundHeight = PANEL_HEIGHT;
		playerInventoryTitleX = 26;
		playerInventoryTitleY = 169;
		titleX = 12;
		titleY = 8;
	}

	@Override
	protected void init() {
		super.init();
		miningButton = addDrawableChild(new MiningToggleWidget(x + 71, y + 50));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		if (miningButton != null) {
			miningButton.active = handler.hasMiningTool();
			miningButton.setMessage(miningButtonText());
			miningButton.setTooltip(Tooltip.of(miningButtonTooltip()));
		}
		super.render(context, mouseX, mouseY, delta);
		drawMouseoverTooltip(context, mouseX, mouseY);
		drawEmptyEquipmentTooltip(context, mouseX, mouseY);
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		drawFrame(context);
		drawPanel(context, x + 7, y + 29, 84, 54, TOOL_ACCENT, true);
		drawPanel(context, x + 95, y + 29, 112, 54, ABILITY_ACCENT, true);
		drawPanel(context, x + 20, y + 87, 174, 74,
				handler.hasChest() ? ACTIVE_ACCENT : 0xFF7A6252, false);
		drawPanel(context, x + 20, y + 163, 174, 94, 0xFF78817C, false);

		for (int slot = 0; slot < 3; slot++) {
			drawSlotFrame(context, x + 11 + slot * 20, y + 50, true, true);
		}
		for (int slot = 0; slot < 4; slot++) {
			drawSlotFrame(context, x + 100 + slot * 20, y + 50, true, true);
		}
		drawSlotFrame(context, x + 184, y + 50, handler.supportsClutch(), true);
		context.fill(x + 180, y + 40, x + 181, y + 75, 0xFF171C1B);
		context.fill(x + 181, y + 40, x + 182, y + 75, 0xFF626B67);

		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				drawSlotFrame(context, x + 26 + column * 18, y + 105 + row * 18,
						handler.hasChest(), false);
			}
		}
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				drawSlotFrame(context, x + 26 + column * 18, y + 182 + row * 18, true, false);
			}
		}
		for (int column = 0; column < 9; column++) {
			drawSlotFrame(context, x + 26 + column * 18, y + 238, true, false);
		}
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		context.drawTextWithShadow(textRenderer, title, titleX, titleY, 0xFFF2F4F1);
		context.drawTextWithShadow(textRenderer,
				Text.translatable("screen.echominecart.ring_vehicle_tools"), 14, 34, 0xFFF0D8C3);
		context.drawTextWithShadow(textRenderer,
				Text.translatable("screen.echominecart.ring_vehicle_abilities"), 102, 34, 0xFFD7EEEA);

		int storageColor = handler.hasChest() ? 0xFF314238 : 0xFF5A4940;
		context.fill(26, 93, 30, 97, handler.hasChest() ? ACTIVE_ACCENT : 0xFF9B6A4B);
		context.drawText(textRenderer, Text.translatable(handler.hasChest()
				? "screen.echominecart.ring_vehicle_storage"
				: "screen.echominecart.ring_vehicle_no_storage"), 34, 91, storageColor, false);
		context.drawText(textRenderer, playerInventoryTitle, playerInventoryTitleX,
				playerInventoryTitleY, 0xFF303633, false);
	}

	private void drawFrame(DrawContext context) {
		fillChamferedRect(context, x + 4, y + 5, x + backgroundWidth + 4, y + backgroundHeight + 5, 0x66000000);
		fillChamferedRect(context, x, y, x + backgroundWidth, y + backgroundHeight, 0xFF151A19);
		fillChamferedRect(context, x + 1, y + 1, x + backgroundWidth - 1, y + backgroundHeight - 1, 0xFF68706C);
		fillChamferedRect(context, x + 2, y + 2, x + backgroundWidth - 2, y + backgroundHeight - 2, 0xFFD8DBD8);
		fillChamferedRect(context, x + 3, y + 3, x + backgroundWidth - 3, y + backgroundHeight - 3, 0xFFB3B9B5);
		fillChamferedRect(context, x + 3, y + 3, x + backgroundWidth - 3, y + 25, 0xFF252C2B);
		context.fill(x + 3, y + 3, x + backgroundWidth - 3, y + 5, TOOL_ACCENT);
		context.fill(x + 3, y + 24, x + backgroundWidth - 3, y + 26, 0xFF101514);
		context.fill(x + 4, y + 26, x + backgroundWidth - 4, y + 27, 0xFFE3E6E3);
	}

	private static void drawPanel(DrawContext context, int left, int top, int width, int height,
			int accent, boolean dark) {
		fillChamferedRect(context, left, top, left + width, top + height, 0xFF4F5753);
		fillChamferedRect(context, left + 1, top + 1, left + width - 1, top + height - 1,
				dark ? 0xFF252C2B : 0xFFC5CAC6);
		context.fill(left + 3, top + 7, left + 5, top + height - 5, accent);
		context.fill(left + 5, top + 2, left + width - 3, top + 3,
				dark ? 0xFF3C4542 : 0xFFE5E8E5);
		context.fill(left + 1, top + height - 2, left + width - 1, top + height - 1, 0xFF777F7B);
	}

	private static void drawSlotFrame(DrawContext context, int left, int top, boolean enabled, boolean dark) {
		int outer = enabled ? 0xFF111615 : 0xFF555B58;
		int inner = enabled
				? dark ? 0xFF2B3331 : 0xFFBEC4C0
				: dark ? 0xFF484E4B : 0xFF9FA5A1;
		int highlight = enabled ? dark ? 0xFF626B67 : 0xFFE8EBE8 : 0xFFB4B9B6;
		context.fill(left, top, left + 18, top + 18, outer);
		context.fill(left + 1, top + 1, left + 17, top + 17, inner);
		context.fill(left + 1, top + 16, left + 17, top + 17, highlight);
		context.fill(left + 16, top + 1, left + 17, top + 17, highlight);
		if (!enabled) {
			context.fill(left + 3, top + 3, left + 15, top + 15, 0x33505050);
		}
	}

	private static void fillChamferedRect(DrawContext context, int left, int top, int right, int bottom, int color) {
		if (right - left < 5 || bottom - top < 5) {
			context.fill(left, top, right, bottom, color);
			return;
		}
		context.fill(left + 2, top, right - 2, bottom, color);
		context.fill(left + 1, top + 1, right - 1, bottom - 1, color);
		context.fill(left, top + 2, right, bottom - 2, color);
	}

	private void drawEmptyEquipmentTooltip(DrawContext context, int mouseX, int mouseY) {
		for (int slotIndex = 0; slotIndex < EQUIPMENT_TOOLTIPS.length; slotIndex++) {
			Slot slot = handler.getSlot(slotIndex);
			if (slot.hasStack() || !isWithin(mouseX, mouseY, x + slot.x - 1, y + slot.y - 1, 18, 18)) {
				continue;
			}
			context.drawTooltip(textRenderer, Text.translatable(EQUIPMENT_TOOLTIPS[slotIndex]), mouseX, mouseY);
			return;
		}
	}

	private static boolean isWithin(int mouseX, int mouseY, int left, int top, int width, int height) {
		return mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < top + height;
	}

	private Text miningButtonText() {
		return Text.translatable(handler.isMiningModeEnabled()
				? "button.echominecart.ring_vehicle_mining_stop"
				: "button.echominecart.ring_vehicle_mining_start");
	}

	private Text miningButtonTooltip() {
		if (!handler.hasMiningTool()) {
			return Text.translatable("button.echominecart.ring_vehicle_mining_no_tool");
		}
		return Text.translatable(handler.isMiningModeEnabled()
				? "button.echominecart.ring_vehicle_mining_stop_tooltip"
				: "button.echominecart.ring_vehicle_mining_start_tooltip");
	}

	private final class MiningToggleWidget extends PressableWidget {
		private MiningToggleWidget(int x, int y) {
			super(x, y, 18, 18, miningButtonText());
		}

		@Override
		public void onPress() {
			if (handler.vehicleId() >= 0 && handler.hasMiningTool()) {
				ClientPlayNetworking.send(new RingVehicleActionPayload(
						handler.vehicleId(), RingVehicleActionPayload.TOGGLE_MINING));
			}
		}

		@Override
		protected void appendClickableNarrations(NarrationMessageBuilder builder) {
			appendDefaultNarrations(builder);
		}

		@Override
		protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			boolean running = handler.isMiningModeEnabled();
			fillChamferedRect(context, getX(), getY(), getX() + width, getY() + height,
					active ? 0xFF111615 : 0xFF555B58);
			fillChamferedRect(context, getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1,
					active ? 0xFF2B3331 : 0xFF484E4B);
			if (hovered && active) {
				context.drawBorder(getX(), getY(), width, height, running ? 0xFF8CE6A6 : 0xFFF0B47C);
			}
			context.drawItem(MINING_ICON, getX() + 1, getY() + 1);
			if (running) {
				context.fill(getX() + 2, getY() + 14, getX() + 16, getY() + 16, ACTIVE_ACCENT);
				context.fill(getX() + 13, getY() + 2, getX() + 16, getY() + 5, 0xFF8CE6A6);
			} else if (active) {
				context.fill(getX() + 2, getY() + 14, getX() + 16, getY() + 16, TOOL_ACCENT);
			} else {
				context.fill(getX() + 1, getY() + 1, getX() + 17, getY() + 17, 0x88707070);
			}
		}
	}
}
