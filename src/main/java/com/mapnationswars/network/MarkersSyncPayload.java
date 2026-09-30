package com.mapnationswars.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.MarkerData;

/** Server -> client: the markers THIS player is allowed to see. */
public record MarkersSyncPayload(List<MarkerData> markers) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MarkersSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("markers_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MarkersSyncPayload> CODEC =
			CustomPacketPayload.codec(MarkersSyncPayload::write, MarkersSyncPayload::read);

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(this.markers.size());

		for (MarkerData m : this.markers) {
			m.write(buf);
		}
	}

	private static MarkersSyncPayload read(RegistryFriendlyByteBuf buf) {
		int size = buf.readVarInt();
		List<MarkerData> list = new ArrayList<>(size);

		for (int i = 0; i < size; i++) {
			list.add(MarkerData.read(buf));
		}

		return new MarkersSyncPayload(list);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
