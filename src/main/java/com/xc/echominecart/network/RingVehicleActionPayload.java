package com.xc.echominecart.network;

import com.xc.echominecart.NestedChestMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Discrete ring-vehicle command such as gear, inventory, mining, or flight-energy toggles. */
public record RingVehicleActionPayload(int entityId, int action) implements CustomPayload {
	public static final int TOGGLE_GEAR = 0;
	public static final int OPEN_INVENTORY = 1;
	public static final int TOGGLE_MINING = 2;
	public static final int TOGGLE_MOMENTUM_STORAGE = 3;
	public static final int REMOVE_DISC_MINECART = 4;
	public static final int TOGGLE_SPIDER_EXPLORE_UP = 5;
	public static final int TOGGLE_SPIDER_EXPLORE_DOWN = 6;
	public static final int FIRE_SPIDER_WEAPON = 7;
	public static final Id<RingVehicleActionPayload> ID = new Id<>(Identifier.of(NestedChestMod.MOD_ID, "ring_vehicle_action"));
	public static final PacketCodec<ByteBuf, RingVehicleActionPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, RingVehicleActionPayload::entityId,
			PacketCodecs.VAR_INT, RingVehicleActionPayload::action,
			RingVehicleActionPayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
