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
	private static final int PANEL_WIDTH = 234;
	private static final int PANEL_HEIGHT = 264;
	private static final int GUI_BACKGROUND = 0xFFC6C6C6;
	private static final int GUI_PANEL = 0xFFB8B8B8;
	private static final int GUI_HIGHLIGHT = 0xFFFFFFFF;
	private static final int GUI_MID = 0xFF8B8B8B;
	private static final int GUI_SHADOW = 0xFF555555;
	private static final int GUI_DARK = 0xFF373737;
	private static final int GUI_DISABLED = 0xFF9A9A9A;
	private static final int ACTIVE_BORDER = 0xFF5E8D68;
	private static final ItemStack MINING_ICON = Items.IRON_PICKAXE.getDefaultStack();
	private static final ItemStack MINECART_ICON = Items.MINECART.getDefaultStack();
	private static final String[] EQUIPMENT_TOOLTIPS = {
			"slot.echominecart.ring_vehicle_tool",
			"slot.echominecart.ring_vehicle_tool",
			"slot.echominecart.ring_vehicle_tool",
			"slot.echominecart.ring_vehicle_chest",
			"slot.echominecart.ring_vehicle_jump",
			"slot.echominecart.ring_vehicle_dash",
			"slot.echominecart.ring_vehicle_smash",
			"slot.echominecart.ring_vehicle_ammo",
			"slot.echominecart.ring_vehicle_clutch"
	};

	private MiningToggleWidget miningButton;
	private DiscMinecartRemoveWidget removeMinecartButton;

	public RingVehicleScreen(RingVehicleScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		backgroundWidth = PANEL_WIDTH;
		backgroundHeight = PANEL_HEIGHT;
		playerInventoryTitleX = 36;
		playerInventoryTitleY = 169;
		titleX = 12;
		titleY = 8;
	}

	@Override
	protected void init() {
		super.init();
		miningButton = addDrawableChild(new MiningToggleWidget(x + 71, y + 50));
		removeMinecartButton = addDrawableChild(new DiscMinecartRemoveWidget(x + 207, y + 5));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		if (miningButton != null) {
			miningButton.active = handler.hasMiningTool();
			miningButton.setMessage(miningButtonText());
			miningButton.setTooltip(Tooltip.of(miningButtonTooltip()));
		}
		if (removeMinecartButton != null) {
			removeMinecartButton.visible = handler.isDiscMode();
			removeMinecartButton.active = handler.canRemoveDiscMinecart();
			removeMinecartButton.setTooltip(Tooltip.of(removeMinecartTooltip()));
		}
		super.render(context, mouseX, mouseY, delta);
		drawMouseoverTooltip(context, mouseX, mouseY);
		drawEmptyEquipmentTooltip(context, mouseX, mouseY);
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		drawFrame(context);
		drawPanel(context, x + 7, y + 29, 84, 54);
		drawPanel(context, x + 95, y + 29, 132, 54);
		drawPanel(context, x + 30, y + 87, 174, 74);
		drawPanel(context, x + 30, y + 163, 174, 97);

		for (int slot = 0; slot < 3; slot++) {
			drawSlotFrame(context, x + 11 + slot * 20, y + 50, true);
		}
		for (int slot = 0; slot < 5; slot++) {
			drawSlotFrame(context, x + 100 + slot * 20, y + 50, true);
		}
		drawSlotFrame(context, x + 204, y + 50, handler.supportsClutch());
		context.fill(x + 200, y + 40, x + 201, y + 75, GUI_SHADOW);
		context.fill(x + 201, y + 40, x + 202, y + 75, GUI_HIGHLIGHT);

		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				drawSlotFrame(context, x + 36 + column * 18, y + 105 + row * 18,
						handler.hasChest());
			}
		}
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				drawSlotFrame(context, x + 36 + column * 18, y + 182 + row * 18, true);
			}
		}
		for (int column = 0; column < 9; column++) {
			drawSlotFrame(context, x + 36 + column * 18, y + 238, true);
		}
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		context.drawText(textRenderer, title, titleX, titleY, GUI_DARK, false);
		context.drawText(textRenderer,
				Text.translatable("screen.echominecart.ring_vehicle_tools"), 14, 34, GUI_DARK, false);
		context.drawText(textRenderer,
				Text.translatable("screen.echominecart.ring_vehicle_abilities"), 102, 34, GUI_DARK, false);

		context.drawText(textRenderer, Text.translatable(handler.hasChest()
				? "screen.echominecart.ring_vehicle_storage"
				: "screen.echominecart.ring_vehicle_no_storage"), 36, 91,
				handler.hasChest() ? GUI_DARK : GUI_SHADOW, false);
		context.drawText(textRenderer, playerInventoryTitle, playerInventoryTitleX,
				playerInventoryTitleY, GUI_DARK, false);
	}

	private void drawFrame(DrawContext context) {
		context.fill(x + 3, y + 3, x + backgroundWidth + 3, y + backgroundHeight + 3, 0x66000000);
		context.fill(x, y, x + backgroundWidth, y + backgroundHeight, GUI_DARK);
		context.fill(x + 1, y + 1, x + backgroundWidth - 1, y + backgroundHeight - 1, GUI_HIGHLIGHT);
		context.fill(x + 2, y + 2, x + backgroundWidth - 2, y + backgroundHeight - 2, GUI_BACKGROUND);
		context.fill(x + 3, y + 24, x + backgroundWidth - 3, y + 25, GUI_SHADOW);
		context.fill(x + 3, y + 25, x + backgroundWidth - 3, y + 26, GUI_HIGHLIGHT);
	}

	private static void drawPanel(DrawContext context, int left, int top, int width, int height) {
		context.fill(left, top, left + width, top + height, GUI_SHADOW);
		context.fill(left + 1, top + 1, left + width, top + height, GUI_HIGHLIGHT);
		context.fill(left + 1, top + 1, left + width - 1, top + height - 1, GUI_PANEL);
	}

	private static void drawSlotFrame(DrawContext context, int left, int top, boolean enabled) {
		context.fill(left, top, left + 18, top + 18, GUI_DARK);
		context.fill(left + 1, top + 1, left + 18, top + 18, GUI_HIGHLIGHT);
		context.fill(left + 1, top + 1, left + 17, top + 17, enabled ? GUI_MID : GUI_DISABLED);
		if (!enabled) {
			context.fill(left + 2, top + 2, left + 16, top + 16, 0x22707070);
		}
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

	private Text removeMinecartTooltip() {
		Text tooltip = Text.translatable("button.echominecart.ring_vehicle_remove_minecart");
		return handler.discExtraMinecarts() > 0
				? tooltip.copy().append(Text.literal(" (" + handler.discExtraMinecarts() + ")"))
				: tooltip;
	}

	private static void drawVanillaButton(DrawContext context, int left, int top, int width, int height,
			boolean active, boolean hovered, boolean selected) {
		int center = !active ? GUI_DISABLED : hovered ? 0xFFD2D2D2 : GUI_BACKGROUND;
		context.fill(left, top, left + width, top + height, GUI_DARK);
		context.fill(left + 1, top + 1, left + width, top + height, GUI_HIGHLIGHT);
		context.fill(left + 2, top + 2, left + width - 1, top + height - 1, center);
		if (selected) {
			context.drawBorder(left + 1, top + 1, width - 2, height - 2, ACTIVE_BORDER);
		} else if (hovered && active) {
			context.drawBorder(left + 1, top + 1, width - 2, height - 2, GUI_MID);
		}
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
			drawVanillaButton(context, getX(), getY(), width, height, active, hovered, running);
			context.drawItem(MINING_ICON, getX() + 1, getY() + 1);
			if (!active) {
				context.fill(getX() + 1, getY() + 1, getX() + 17, getY() + 17, 0x88707070);
			}
		}
	}

	private final class DiscMinecartRemoveWidget extends PressableWidget {
		private DiscMinecartRemoveWidget(int x, int y) {
			super(x, y, 18, 18, Text.translatable("button.echominecart.ring_vehicle_remove_minecart"));
		}

		@Override
		public void onPress() {
			if (handler.vehicleId() >= 0 && handler.canRemoveDiscMinecart()) {
				ClientPlayNetworking.send(new RingVehicleActionPayload(
						handler.vehicleId(), RingVehicleActionPayload.REMOVE_DISC_MINECART));
			}
		}

		@Override
		protected void appendClickableNarrations(NarrationMessageBuilder builder) {
			appendDefaultNarrations(builder);
		}

		@Override
		protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			drawVanillaButton(context, getX(), getY(), width, height, active, hovered, false);
			context.drawItem(MINECART_ICON, getX() + 1, getY() + 1);
			if (!active) {
				context.fill(getX() + 1, getY() + 1, getX() + 17, getY() + 17, 0x66707070);
			}
		}
	}
}
