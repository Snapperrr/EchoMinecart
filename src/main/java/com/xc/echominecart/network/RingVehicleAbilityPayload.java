package com.xc.echominecart.network;

import com.xc.echominecart.NestedChestMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client ability request carrying a normalized charge; server inventory and state gate execution. */
public record RingVehicleAbilityPayload(int entityId, int ability, float value) implements CustomPayload {
	public static final int JUMP = 0;
	public static final int DASH = 1;
	public static final int SMASH = 2;
	public static final Id<RingVehicleAbilityPayload> ID = new Id<>(Identifier.of(NestedChestMod.MOD_ID, "ring_vehicle_ability"));
	public static final PacketCodec<ByteBuf, RingVehicleAbilityPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, RingVehicleAbilityPayload::entityId,
			PacketCodecs.VAR_INT, RingVehicleAbilityPayload::ability,
			PacketCodecs.FLOAT, RingVehicleAbilityPayload::value,
			RingVehicleAbilityPayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
