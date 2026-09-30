package com.mapnationswars.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.NationData;

/** Server -> client: where every online player is (sent twice a second). */
public record PlayersPayload(List<Entry> players) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<PlayersPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("players"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PlayersPayload> CODEC =
			CustomPacketPayload.codec(PlayersPayload::write, PlayersPayload::read);

	public record Entry(UUID id, String name, String dimension, double x, double z, float yaw) {
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(this.players.size());

		for (Entry e : this.players) {
			NationData.writeUuid(buf, e.id());
			buf.writeUtf(e.name());
			buf.writeUtf(e.dimension());
			buf.writeDouble(e.x());
			buf.writeDouble(e.z());
			buf.writeFloat(e.yaw());
		}
	}

	private static PlayersPayload read(RegistryFriendlyByteBuf buf) {
		int size = buf.readVarInt();
		List<Entry> list = new ArrayList<>(size);

		for (int i = 0; i < size; i++) {
			UUID id = NationData.readUuid(buf);
			String name = buf.readUtf();
			String dim = buf.readUtf();
			double x = buf.readDouble();
			double z = buf.readDouble();
			float yaw = buf.readFloat();
			list.add(new Entry(id, name, dim, x, z, yaw));
		}

		return new PlayersPayload(list);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
