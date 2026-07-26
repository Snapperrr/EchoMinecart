package com.xc.echominecart.ringvehicle;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** A craftable mechanical leg whose rail and heat protection must match its spider body. */
public final class SpiderLegItem extends Item {
	private final RingVehicleVariant variant;
	private final boolean lavaProof;

	public SpiderLegItem(RingVehicleVariant variant, boolean lavaProof, Settings settings) {
		super(settings);
		this.variant = variant;
		this.lavaProof = lavaProof;
	}

	public RingVehicleVariant variant() {
		return variant;
	}

	public boolean lavaProof() {
		return lavaProof;
	}

	public boolean matches(RingVehicleEntity vehicle) {
		return vehicle.getVariant() == variant && vehicle.isLavaProof() == lavaProof;
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return variant.powered();
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip,
			net.minecraft.item.tooltip.TooltipType type) {
		super.appendTooltip(stack, context, tooltip, type);
		tooltip.add(Text.translatable(variant.powered()
				? "tooltip.echominecart.spider_leg_powered"
				: "tooltip.echominecart.spider_leg_rail").formatted(Formatting.GRAY));
		if (lavaProof) {
			tooltip.add(Text.translatable("tooltip.echominecart.spider_leg_lava_proof")
					.formatted(Formatting.DARK_PURPLE));
		}
	}
}
