package com.xc.echominecart.network;

import com.xc.echominecart.NestedChestMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Mining-button state and camera aim; the server performs its own raycast and reach validation. */
public record RingVehicleMiningPayload(int entityId, boolean active, float aimX, float aimY, float aimZ)
		implements CustomPayload {
	public static final Id<RingVehicleMiningPayload> ID = new Id<>(
			Identifier.of(NestedChestMod.MOD_ID, "ring_vehicle_mining"));
	public static final PacketCodec<ByteBuf, RingVehicleMiningPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, RingVehicleMiningPayload::entityId,
			PacketCodecs.BOOL, RingVehicleMiningPayload::active,
			PacketCodecs.FLOAT, RingVehicleMiningPayload::aimX,
			PacketCodecs.FLOAT, RingVehicleMiningPayload::aimY,
			PacketCodecs.FLOAT, RingVehicleMiningPayload::aimZ,
			RingVehicleMiningPayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
