package com.mapnationswars.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.LetterData;
import com.mapnationswars.nation.NationData;

/** Server -> client: relations between nations (wars, trade, opinion changes) and your nation's letters. */
public record DiplomacySyncPayload(List<Entry> relations, List<LetterData> letters) implements CustomPacketPayload {
	public record Entry(UUID a, UUID b, int modifier, boolean war, boolean trade) {
	}

	public static final CustomPacketPayload.Type<DiplomacySyncPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("diplomacy_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, DiplomacySyncPayload> CODEC =
			CustomPacketPayload.codec(DiplomacySyncPayload::write, DiplomacySyncPayload::read);

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(this.relations.size());

		for (Entry e : this.relations) {
			NationData.writeUuid(buf, e.a());
			NationData.writeUuid(buf, e.b());
			buf.writeInt(e.modifier());
			buf.writeBoolean(e.war());
			buf.writeBoolean(e.trade());
		}

		buf.writeVarInt(this.letters.size());

		for (LetterData l : this.letters) {
			l.write(buf);
		}
	}

	private static DiplomacySyncPayload read(RegistryFriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<Entry> relations = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			UUID a = NationData.readUuid(buf);
			UUID b = NationData.readUuid(buf);
			int mod = buf.readInt();
			boolean war = buf.readBoolean();
			boolean trade = buf.readBoolean();
			relations.add(new Entry(a, b, mod, war, trade));
		}

		int m = buf.readVarInt();
		List<LetterData> letters = new ArrayList<>(m);

		for (int i = 0; i < m; i++) {
			letters.add(LetterData.read(buf));
		}

		return new DiplomacySyncPayload(relations, letters);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
