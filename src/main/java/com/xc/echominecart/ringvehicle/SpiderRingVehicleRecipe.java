package com.xc.echominecart.ringvehicle;

import com.xc.echominecart.EchoMinecartRegistry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.SpecialCraftingRecipe;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.World;

/** Reinforces a picked-up flight disc into an empty spider body while preserving its modules. */
public final class SpiderRingVehicleRecipe extends SpecialCraftingRecipe {
	public SpiderRingVehicleRecipe(CraftingRecipeCategory category) {
		super(category);
	}

	@Override
	public boolean matches(CraftingRecipeInput input, World world) {
		return findBaseVehicle(input) != null;
	}

	@Override
	public ItemStack craft(CraftingRecipeInput input, RegistryWrapper.WrapperLookup registries) {
		ItemStack source = findBaseVehicle(input);
		if (source == null) {
			return ItemStack.EMPTY;
		}
		ItemStack result = source.copy();
		result.setCount(1);
		NbtComponent component = result.get(DataComponentTypes.CUSTOM_DATA);
		NbtCompound data = component == null ? new NbtCompound() : component.copyNbt();
		// Spider and flight forms share one data-bearing vehicle item, but their physics are exclusive.
		data.putBoolean("SpiderMode", true);
		data.putBoolean("DiscMode", false);
		data.putInt("SpiderLegMask", 0);
		data.putInt("SpiderPendingLeg", -1);
		data.putBoolean("SpiderAwake", false);
		data.putFloat("SpiderDeployProgress", 0.0F);
		data.remove("DiscExtraMinecarts");
		data.remove("FlightRotorSpeed");
		data.remove("DiscFlightActive");
		data.remove("MomentumStorageEnabled");
		data.remove("MomentumStorageCharge");
		data.remove("MomentumReleaseActive");
		result.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
		return result;
	}

	@Override
	public boolean fits(int width, int height) {
		return width >= 3 && height >= 3;
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return EchoMinecartRegistry.SPIDER_RING_VEHICLE_RECIPE;
	}

	private static ItemStack findBaseVehicle(CraftingRecipeInput input) {
		if (input.getWidth() != 3 || input.getHeight() != 3) {
			return null;
		}
		ItemStack center = input.getStackInSlot(1, 1);
		if (!(center.getItem() instanceof RingVehicleItem)
				|| RingVehicleItem.spiderMode(center)
				|| !RingVehicleItem.discMode(center)) {
			return null;
		}
		for (int y = 0; y < 3; y++) {
			for (int x = 0; x < 3; x++) {
				if (x == 1 && y == 1) {
					continue;
				}
				if (!input.getStackInSlot(x, y).isOf(Items.IRON_BLOCK)) {
					return null;
				}
			}
		}
		return center;
	}
}
