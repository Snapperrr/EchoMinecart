package com.xc.echominecart.client;

import com.xc.echominecart.ringvehicle.SpiderLegItem;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.MinecartEntityModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.RotationAxis;

/** Renders the same block-built mechanical leg used by the deployed spider vehicle. */
public final class SpiderLegItemRenderer implements BuiltinItemRendererRegistry.DynamicItemRenderer {
	private final MinecraftClient client;
	private RingVehicleModelRenderer modelRenderer;

	public SpiderLegItemRenderer(MinecraftClient client) {
		this.client = client;
	}

	@Override
	public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light, int overlay) {
		if (!(stack.getItem() instanceof SpiderLegItem legItem)) {
			return;
		}
		matrices.push();
		matrices.translate(0.5F, 0.49F, 0.5F);
		float scale = mode == ModelTransformationMode.GUI ? 0.29F : 0.20F;
		matrices.scale(scale, scale, scale);
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-18.0F));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(32.0F));
		matrices.translate(-0.10F, -0.10F, 0.0F);
		modelRenderer().renderSpiderLegItem(legItem.variant(), legItem.lavaProof(),
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
