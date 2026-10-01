package com.mapnationswars.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.NationData;

/** Server -> one player: how much the people of each province support that player (stage 7, for founding a nation). */
public record SupportSyncPayload(List<Entry> support) implements CustomPacketPayload {
	public record Entry(UUID province, int support) {
	}

	public static final CustomPacketPayload.Type<SupportSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("support_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SupportSyncPayload> CODEC =
			CustomPacketPayload.codec(SupportSyncPayload::write, SupportSyncPayload::read);

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(this.support.size());

		for (Entry e : this.support) {
			NationData.writeUuid(buf, e.province());
			buf.writeVarInt(e.support());
		}
	}

	private static SupportSyncPayload read(RegistryFriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<Entry> list = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			list.add(new Entry(NationData.readUuid(buf), buf.readVarInt()));
		}

		return new SupportSyncPayload(list);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
