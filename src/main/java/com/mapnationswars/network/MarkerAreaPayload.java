package com.mapnationswars.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/** Client -> server: new borders (list of chunk keys) for a settlement marker. */
public record MarkerAreaPayload(String markerId, List<Long> chunks) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MarkerAreaPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("marker_area"));

	public static final StreamCodec<FriendlyByteBuf, MarkerAreaPayload> CODEC =
			CustomPacketPayload.codec(MarkerAreaPayload::write, MarkerAreaPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.markerId);
		buf.writeVarInt(this.chunks.size());

		for (long key : this.chunks) {
			buf.writeLong(key);
		}
	}

	private static MarkerAreaPayload read(FriendlyByteBuf buf) {
		String id = buf.readUtf();
		int size = Math.min(buf.readVarInt(), 1024);
		List<Long> chunks = new ArrayList<>(size);

		for (int i = 0; i < size; i++) {
			chunks.add(buf.readLong());
		}

		return new MarkerAreaPayload(id, chunks);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
