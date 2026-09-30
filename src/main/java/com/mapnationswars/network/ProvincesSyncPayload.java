package com.mapnationswars.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.ProvinceData;

/** Server -> client: every province (villages, outposts, mansions, bastions) and who owns it. */
public record ProvincesSyncPayload(List<ProvinceData> provinces) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ProvincesSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("provinces_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ProvincesSyncPayload> CODEC =
			CustomPacketPayload.codec(ProvincesSyncPayload::write, ProvincesSyncPayload::read);

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(this.provinces.size());

		for (ProvinceData p : this.provinces) {
			p.write(buf);
		}
	}

	private static ProvincesSyncPayload read(RegistryFriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<ProvinceData> list = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			list.add(ProvinceData.read(buf));
		}

		return new ProvincesSyncPayload(list);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
