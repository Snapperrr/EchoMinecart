package com.xc.echominecart.client;

import com.xc.echominecart.NestedChestMod;
import com.xc.echominecart.rail.OmniRailBlock;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricBakedModelManager;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.function.Supplier;

/** Selects the lateral wall-corner model from live rail geometry without adding block states. */
/** Wraps omni-rail baked models to apply locked lateral-transition transforms at render time. */
public final class LateralRailModelLoader {
	private static final Identifier RAIL_BLOCK = id("echo_rail");
	private static final Identifier POWERED_RAIL_BLOCK = id("echo_powered_rail");
	private static final Identifier RAIL = model("echo_rail_lateral");
	private static final Identifier RAIL_MIRRORED = model("echo_rail_lateral_mirrored");
	private static final Identifier POWERED_RAIL = model("echo_powered_rail_lateral");
	private static final Identifier POWERED_RAIL_MIRRORED = model("echo_powered_rail_lateral_mirrored");
	private static final Identifier POWERED_RAIL_ON = model("echo_powered_rail_on_lateral");
	private static final Identifier POWERED_RAIL_ON_MIRRORED = model("echo_powered_rail_on_lateral_mirrored");

	private LateralRailModelLoader() {
	}

	public static void initialize() {
		ModelLoadingPlugin.register(context -> {
			context.addModels(
					RAIL, RAIL_MIRRORED,
					POWERED_RAIL, POWERED_RAIL_MIRRORED,
					POWERED_RAIL_ON, POWERED_RAIL_ON_MIRRORED);
			context.modifyModelAfterBake().register((bakedModel, bakeContext) -> {
				ModelIdentifier topLevel = bakeContext.topLevelId();
				if (bakedModel == null || topLevel == null || ModelIdentifier.INVENTORY_VARIANT.equals(topLevel.getVariant())) {
					return bakedModel;
				}
				if (RAIL_BLOCK.equals(topLevel.id())) {
					return new LateralRailBakedModel(bakedModel, false);
				}
				if (POWERED_RAIL_BLOCK.equals(topLevel.id())) {
					return new LateralRailBakedModel(bakedModel, true);
				}
				return bakedModel;
			});
		});
	}

	private static Identifier id(String path) {
		return Identifier.of(NestedChestMod.MOD_ID, path);
	}

	private static Identifier model(String path) {
		return id("block/" + path);
	}

	private static final class LateralRailBakedModel extends ForwardingBakedModel {
		private final boolean poweredRail;

		private LateralRailBakedModel(BakedModel wrapped, boolean poweredRail) {
			super(wrapped);
			this.poweredRail = poweredRail;
		}

		@Override
		public boolean isVanillaAdapter() {
			return false;
		}

		@Override
		public void emitBlockQuads(BlockRenderView blockView, BlockState state, BlockPos pos,
				Supplier<Random> randomSupplier, RenderContext context) {
			OmniRailBlock.LateralTransition transition = OmniRailBlock.lateralTransition(blockView, pos, state);
			if (transition == OmniRailBlock.LateralTransition.NONE) {
				super.emitBlockQuads(blockView, state, pos, randomSupplier, context);
				return;
			}

			Direction face = OmniRailBlock.face(state);
			boolean mirrored = (face.getAxis() == Direction.Axis.X)
					== (transition == OmniRailBlock.LateralTransition.CLOCKWISE);
			Identifier modelId;
			if (!poweredRail) {
				modelId = mirrored ? RAIL_MIRRORED : RAIL;
			} else if (state.get(OmniRailBlock.POWERED)) {
				modelId = mirrored ? POWERED_RAIL_ON_MIRRORED : POWERED_RAIL_ON;
			} else {
				modelId = mirrored ? POWERED_RAIL_MIRRORED : POWERED_RAIL;
			}

			FabricBakedModelManager manager = (FabricBakedModelManager) MinecraftClient.getInstance().getBakedModelManager();
			manager.getModel(modelId).emitBlockQuads(blockView, state, pos, randomSupplier, context);
		}
	}
}
