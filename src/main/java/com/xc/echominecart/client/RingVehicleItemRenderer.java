package com.xc.echominecart.client;

import com.xc.echominecart.ringvehicle.RingVehicleItem;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.MinecartEntityModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.util.math.RotationAxis;

/** Renders the actual ring geometry as the item preview instead of a flattened proxy icon. */
public final class RingVehicleItemRenderer implements BuiltinItemRendererRegistry.DynamicItemRenderer {
	private final MinecraftClient client;
	private RingVehicleModelRenderer modelRenderer;

	public RingVehicleItemRenderer(MinecraftClient client) {
		this.client = client;
	}

	@Override
	public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light, int overlay) {
		if (!(stack.getItem() instanceof RingVehicleItem item)) {
			return;
		}
		matrices.push();
		matrices.translate(0.5F, 0.5F, 0.5F);
		int ringLevel = RingVehicleItem.ringLevel(stack);
		float scale = mode == ModelTransformationMode.GUI
				? (ringLevel > 0 ? 0.25F : 0.39F)
				: (ringLevel > 0 ? 0.15F : 0.23F);
		matrices.scale(scale, scale, scale);
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(mode == ModelTransformationMode.GUI ? -8.0F : 5.0F));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(28.0F));
		NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
		boolean chestAttached = data != null && data.copyNbt().getBoolean("ChestAttached");
		boolean discMode = RingVehicleItem.discMode(stack);
		int extraMinecarts = data == null ? 0 : data.copyNbt().getInt("DiscExtraMinecarts");
		if (discMode) {
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90.0F));
		}
		modelRenderer().render(item.variant(), item.lavaProof(), chestAttached, ringLevel, 0.0F, 0.0F,
				discMode, extraMinecarts,
				matrices, vertexConsumers, light);
		matrices.pop();
	}

	private RingVehicleModelRenderer modelRenderer() {
		if (modelRenderer == null) {
			modelRenderer = new RingVehicleModelRenderer(
					new MinecartEntityModel<>(client.getEntityModelLoader().getModelPart(EntityModelLayers.MINECART)),
					client.getBlockRenderManager());
		}
		return modelRenderer;
	}
}
