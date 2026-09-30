package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

import com.mapnationswars.MapNationsMod;

/** Client -> server: place, remove, rename, move or vote on a marker. */
public record MarkerActionPayload(int action, String markerId, int x, int z, String markerType, String label, int visibility, List<Long> area)
		implements CustomPacketPayload {
	public static final int PLACE = 0;
	public static final int REMOVE = 1;
	/** Owner only: new name in label. */
	public static final int RENAME = 2;
	/** Owner only: new position in x / z. */
	public static final int MOVE = 3;
	/** Vote to destroy someone else's marker (or take the vote back). */
	public static final int VOTE = 4;

	public static final CustomPacketPayload.Type<MarkerActionPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("marker_action"));

	public static final StreamCodec<FriendlyByteBuf, MarkerActionPayload> CODEC =
			CustomPacketPayload.codec(MarkerActionPayload::write, MarkerActionPayload::read);

	public static MarkerActionPayload remove(String markerId) {
		return new MarkerActionPayload(REMOVE, markerId, 0, 0, "", "", 0, List.of());
	}

	public static MarkerActionPayload rename(String markerId, String label) {
		return new MarkerActionPayload(RENAME, markerId, 0, 0, "", label, 0, List.of());
	}

	public static MarkerActionPayload move(String markerId, int x, int z) {
		return new MarkerActionPayload(MOVE, markerId, x, z, "", "", 0, List.of());
	}

	public static MarkerActionPayload vote(String markerId) {
		return new MarkerActionPayload(VOTE, markerId, 0, 0, "", "", 0, List.of());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.action);
		buf.writeUtf(this.markerId);
		buf.writeInt(this.x);
		buf.writeInt(this.z);
		buf.writeUtf(this.markerType);
		buf.writeUtf(this.label);
		buf.writeVarInt(this.visibility);
		buf.writeVarInt(this.area.size());

		for (long key : this.area) {
			buf.writeLong(key);
		}
	}

	private static MarkerActionPayload read(FriendlyByteBuf buf) {
		int action = buf.readVarInt();
		String id = buf.readUtf();
		int x = buf.readInt();
		int z = buf.readInt();
		String markerType = buf.readUtf();
		String label = buf.readUtf();
		int vis = buf.readVarInt();
		int areaSize = Math.min(buf.readVarInt(), 1024);
		List<Long> area = new ArrayList<>(areaSize);

		for (int i = 0; i < areaSize; i++) {
			area.add(buf.readLong());
		}

		return new MarkerActionPayload(action, id, x, z, markerType, label, vis, area);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
