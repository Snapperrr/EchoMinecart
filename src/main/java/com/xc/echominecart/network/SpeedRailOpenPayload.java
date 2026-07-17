package com.xc.echominecart.network;

import com.xc.echominecart.NestedChestMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SpeedRailOpenPayload(long pos, double speed) implements CustomPayload {
	public static final Id<SpeedRailOpenPayload> ID = new Id<>(Identifier.of(NestedChestMod.MOD_ID, "speed_rail_open"));
	public static final PacketCodec<ByteBuf, SpeedRailOpenPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_LONG, SpeedRailOpenPayload::pos,
			PacketCodecs.DOUBLE, SpeedRailOpenPayload::speed,
			SpeedRailOpenPayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
