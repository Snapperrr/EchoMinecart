package com.xc.echominecart.carriage;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.UUID;

/**
 * 扩充车厢的几何形状：锚点矿车、按前进方向排序的座位格、
 * 车体碰撞盒集合与整体包围盒。
 */
/** Immutable occupied-cell geometry and anchor metadata used by server and client carriage code. */
public record CarriageShape(
		UUID anchorCart,
		BlockPos anchorBlock,
		Vec3d anchorPos,
		List<BlockPos> seats,
		List<Box> bodyBoxes,
		Box bounds,
		Box impactBounds,
		Vec3d forward) {

	public boolean intersectsBody(Box entityBox) {
		for (Box box : bodyBoxes) {
			if (entityBox.intersects(box)) {
				return true;
			}
		}
		return false;
	}
}
