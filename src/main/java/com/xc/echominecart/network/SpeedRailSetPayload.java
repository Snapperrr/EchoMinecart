package com.xc.echominecart.network;

import com.xc.echominecart.NestedChestMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SpeedRailSetPayload(long pos, double speed) implements CustomPayload {
	public static final Id<SpeedRailSetPayload> ID = new Id<>(Identifier.of(NestedChestMod.MOD_ID, "speed_rail_set"));
	public static final PacketCodec<ByteBuf, SpeedRailSetPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_LONG, SpeedRailSetPayload::pos,
			PacketCodecs.DOUBLE, SpeedRailSetPayload::speed,
			SpeedRailSetPayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
