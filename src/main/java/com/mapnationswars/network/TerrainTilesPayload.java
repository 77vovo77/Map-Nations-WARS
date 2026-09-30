package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/**
 * Server -> client: map pictures of chunks (16 x 16 colours each, 3 bytes per colour).
 * worldId changes when the world (seed) changes, so the client keeps a separate saved copy per world.
 * A packet with no chunks just tells the client the worldId (sent when you join).
 */
public record TerrainTilesPayload(String dimension, long worldId, long[] chunks, byte[] rgb) implements CustomPacketPayload {
	public static final int BYTES_PER_CHUNK = 16 * 16 * 3;
	public static final int MAX_CHUNKS = 64;

	public static final CustomPacketPayload.Type<TerrainTilesPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("terrain_tiles"));

	public static final StreamCodec<FriendlyByteBuf, TerrainTilesPayload> CODEC =
			CustomPacketPayload.codec(TerrainTilesPayload::write, TerrainTilesPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.dimension);
		buf.writeLong(this.worldId);
		buf.writeVarInt(this.chunks.length);

		for (long key : this.chunks) {
			buf.writeLong(key);
		}

		buf.writeBytes(this.rgb, 0, this.chunks.length * BYTES_PER_CHUNK);
	}

	private static TerrainTilesPayload read(FriendlyByteBuf buf) {
		String dimension = buf.readUtf();
		long worldId = buf.readLong();
		int n = buf.readVarInt();

		if (n < 0 || n > MAX_CHUNKS) {
			throw new IllegalArgumentException("Too many chunks in one terrain packet: " + n);
		}

		long[] chunks = new long[n];

		for (int i = 0; i < n; i++) {
			chunks[i] = buf.readLong();
		}

		byte[] rgb = new byte[n * BYTES_PER_CHUNK];
		buf.readBytes(rgb);
		return new TerrainTilesPayload(dimension, worldId, chunks, rgb);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
