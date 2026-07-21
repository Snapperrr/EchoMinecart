package com.xc.echominecart.ringvehicle;

import com.xc.echominecart.EchoMinecartRegistry;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.SpecialCraftingRecipe;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.World;

/** Wraps an existing ring vehicle in obsidian without discarding installed modules or contents. */
public final class LavaProofRingVehicleRecipe extends SpecialCraftingRecipe {
	public LavaProofRingVehicleRecipe(CraftingRecipeCategory category) {
		super(category);
	}

	@Override
	public boolean matches(CraftingRecipeInput input, World world) {
		return findBaseVehicle(input) != null;
	}

	@Override
	public ItemStack craft(CraftingRecipeInput input, RegistryWrapper.WrapperLookup registries) {
		ItemStack source = findBaseVehicle(input);
		if (source == null || !(source.getItem() instanceof RingVehicleItem item)) {
			return ItemStack.EMPTY;
		}
		return source.copyComponentsToNewStack(EchoMinecartRegistry.ringVehicleItem(item.variant(), true), 1);
	}

	@Override
	public boolean fits(int width, int height) {
		return width >= 3 && height >= 3;
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return EchoMinecartRegistry.LAVA_PROOF_RING_VEHICLE_RECIPE;
	}

	private static ItemStack findBaseVehicle(CraftingRecipeInput input) {
		if (input.getWidth() != 3 || input.getHeight() != 3) {
			return null;
		}
		ItemStack center = input.getStackInSlot(1, 1);
		if (!(center.getItem() instanceof RingVehicleItem item) || item.lavaProof()) {
			return null;
		}
		for (int y = 0; y < 3; y++) {
			for (int x = 0; x < 3; x++) {
				if (x == 1 && y == 1) {
					continue;
				}
				if (!input.getStackInSlot(x, y).isOf(Items.OBSIDIAN)) {
					return null;
				}
			}
		}
		return center;
	}
}
