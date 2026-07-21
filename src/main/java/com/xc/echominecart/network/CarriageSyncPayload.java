package com.xc.echominecart.network;

import com.xc.echominecart.NestedChestMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 扩充车厢形状同步：服务端把锚点矿车的实体 ID 和车厢覆盖格
 * （BlockPos.asLong 列表）发给正在追踪该实体的客户端，
 * 客户端据此渲染拉伸后的车体，不再自行推断。
 */
/** Server-to-client render snapshot for one carriage anchor and its occupied cells. */
public record CarriageSyncPayload(int anchorId, long anchorCell, List<Long> cells, int chestCount) implements CustomPayload {
	public static final Id<CarriageSyncPayload> ID = new Id<>(Identifier.of(NestedChestMod.MOD_ID, "carriage_sync"));
	private static final PacketCodec<ByteBuf, List<Long>> CELLS_CODEC = PacketCodecs.VAR_LONG
			.collect(PacketCodecs.toList(256))
			.xmap(List::copyOf, ArrayList::new);
	public static final PacketCodec<ByteBuf, CarriageSyncPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, CarriageSyncPayload::anchorId,
			PacketCodecs.VAR_LONG, CarriageSyncPayload::anchorCell,
			CELLS_CODEC, CarriageSyncPayload::cells,
			PacketCodecs.VAR_INT, CarriageSyncPayload::chestCount,
			CarriageSyncPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
