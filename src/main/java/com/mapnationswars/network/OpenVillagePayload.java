package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/** Server -> client: you talked to a mayor, open that village's page. carried = emeralds you have. */
public record OpenVillagePayload(String province, int carried, boolean canOrder) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<OpenVillagePayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("open_village"));

	public static final StreamCodec<FriendlyByteBuf, OpenVillagePayload> CODEC =
			CustomPacketPayload.codec(OpenVillagePayload::write, OpenVillagePayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.province);
		buf.writeVarInt(this.carried);
		buf.writeBoolean(this.canOrder);
	}

	private static OpenVillagePayload read(FriendlyByteBuf buf) {
		String province = buf.readUtf();
		int carried = buf.readVarInt();
		boolean canOrder = buf.readBoolean();
		return new OpenVillagePayload(province, carried, canOrder);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
