package com.mapnationswars.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/** Client -> server: "please send me the terrain of these chunks" (for places you have not explored). */
public record TerrainRequestPayload(List<Long> chunks) implements CustomPacketPayload {
	/** Most chunks one request may ask for. */
	public static final int MAX_CHUNKS = 256;

	public static final CustomPacketPayload.Type<TerrainRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("terrain_request"));

	public static final StreamCodec<FriendlyByteBuf, TerrainRequestPayload> CODEC =
			CustomPacketPayload.codec(TerrainRequestPayload::write, TerrainRequestPayload::read);

	private void write(FriendlyByteBuf buf) {
		int n = Math.min(this.chunks.size(), MAX_CHUNKS);
		buf.writeVarInt(n);

		for (int i = 0; i < n; i++) {
			buf.writeLong(this.chunks.get(i));
		}
	}

	private static TerrainRequestPayload read(FriendlyByteBuf buf) {
		int n = buf.readVarInt();

		if (n < 0 || n > MAX_CHUNKS) {
			throw new IllegalArgumentException("Too many chunks in one terrain request: " + n);
		}

		List<Long> list = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			list.add(buf.readLong());
		}

		return new TerrainRequestPayload(list);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
