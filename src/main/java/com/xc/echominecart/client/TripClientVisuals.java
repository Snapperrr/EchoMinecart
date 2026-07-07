package com.xc.echominecart.client;

import java.util.HashMap;
import java.util.Map;

/** 客户端绊倒状态表：entityId → 趴倒朝向；由 TripSyncPayload 维护。 */
public final class TripClientVisuals {
	private static final Map<Integer, Float> TRIPPED_YAW = new HashMap<>();

	private TripClientVisuals() {
	}

	public static void update(int entityId, boolean tripped, float yaw) {
		if (tripped) {
			TRIPPED_YAW.put(entityId, yaw);
		} else {
			TRIPPED_YAW.remove(entityId);
		}
	}

	public static Float trippedYaw(int entityId) {
		return TRIPPED_YAW.get(entityId);
	}

	public static void clear() {
		TRIPPED_YAW.clear();
	}
}
