package com.xc.echominecart.ringvehicle;

import com.xc.echominecart.EchoMinecartRegistry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.SpecialCraftingRecipe;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.World;

/** Adds one outer rail ring while preserving all serialized vehicle state. */
public final class RingVehicleExpansionRecipe extends SpecialCraftingRecipe {
	public RingVehicleExpansionRecipe(CraftingRecipeCategory category) {
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
		data.putInt("RingLevel", RingVehicleEntity.MAX_RING_LEVEL);
		result.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
		return result;
	}

	@Override
	public boolean fits(int width, int height) {
		return width >= 3 && height >= 3;
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return EchoMinecartRegistry.RING_VEHICLE_EXPANSION_RECIPE;
	}

	private static ItemStack findBaseVehicle(CraftingRecipeInput input) {
		if (input.getWidth() != 3 || input.getHeight() != 3) {
			return null;
		}
		ItemStack center = input.getStackInSlot(1, 1);
		if (!(center.getItem() instanceof RingVehicleItem vehicleItem)
				|| RingVehicleItem.ringLevel(center) >= RingVehicleEntity.MAX_RING_LEVEL) {
			return null;
		}
		Item requiredRail = vehicleItem.variant().powered() ? Items.POWERED_RAIL : Items.RAIL;
		for (int y = 0; y < 3; y++) {
			for (int x = 0; x < 3; x++) {
				if (x == 1 && y == 1) {
					continue;
				}
				if (!input.getStackInSlot(x, y).isOf(requiredRail)) {
					return null;
				}
			}
		}
		return center;
	}
}
