package com.xc.echominecart.network;

import com.xc.echominecart.NestedChestMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 绊倒状态同步：客户端据此把生物渲染成趴倒姿态。
 * tripped=false 表示解除。yaw 是生物走上铁轨时的行进朝向。
 */
/** Server-to-client pose state for a living entity pinned to a rail. */
public record TripSyncPayload(int entityId, boolean tripped, float yaw) implements CustomPayload {
	public static final Id<TripSyncPayload> ID = new Id<>(Identifier.of(NestedChestMod.MOD_ID, "trip_sync"));
	public static final PacketCodec<ByteBuf, TripSyncPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, TripSyncPayload::entityId,
			PacketCodecs.BOOL, TripSyncPayload::tripped,
			PacketCodecs.FLOAT, TripSyncPayload::yaw,
			TripSyncPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
