package com.xc.echominecart.network;

import com.xc.echominecart.NestedChestMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Short-lived driver input sampled by the server; stale packets deliberately expire. */
public record RingVehicleControlPayload(int entityId, float throttle, float steering, boolean clutchHeld)
		implements CustomPayload {
	public static final Id<RingVehicleControlPayload> ID = new Id<>(Identifier.of(NestedChestMod.MOD_ID, "ring_vehicle_control"));
	public static final PacketCodec<ByteBuf, RingVehicleControlPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, RingVehicleControlPayload::entityId,
			PacketCodecs.FLOAT, RingVehicleControlPayload::throttle,
			PacketCodecs.FLOAT, RingVehicleControlPayload::steering,
			PacketCodecs.BOOL, RingVehicleControlPayload::clutchHeld,
			RingVehicleControlPayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
