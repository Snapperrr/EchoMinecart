package com.xc.echominecart.network;

import com.xc.echominecart.NestedChestMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Confirms a successful spider-weapon shot and describes its local camera impact. */
public record SpiderWeaponFiredPayload(float intensity, boolean explosive, int color) implements CustomPayload {
	public static final Id<SpiderWeaponFiredPayload> ID = new Id<>(
			Identifier.of(NestedChestMod.MOD_ID, "spider_weapon_fired"));
	public static final PacketCodec<ByteBuf, SpiderWeaponFiredPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.FLOAT, SpiderWeaponFiredPayload::intensity,
			PacketCodecs.BOOL, SpiderWeaponFiredPayload::explosive,
			PacketCodecs.VAR_INT, SpiderWeaponFiredPayload::color,
			SpiderWeaponFiredPayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
