package com.mapnationswars.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.DutyData;
import com.mapnationswars.nation.NationData;

/** Server -> one player: what each nation thinks of them, their personal wars and their duties (1.9). */
public record PersonalSyncPayload(List<Standing> standings, List<DutyData> duties, int conspiracy) implements CustomPacketPayload {
	/** opinion = -100 .. 100; war = a personal war with that nation */
	public record Standing(UUID nation, int opinion, boolean war) {
	}

	public static final CustomPacketPayload.Type<PersonalSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("personal_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PersonalSyncPayload> CODEC =
			CustomPacketPayload.codec(PersonalSyncPayload::write, PersonalSyncPayload::read);

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(this.standings.size());

		for (Standing s : this.standings) {
			NationData.writeUuid(buf, s.nation());
			buf.writeInt(s.opinion());
			buf.writeBoolean(s.war());
		}

		buf.writeVarInt(this.duties.size());

		for (DutyData d : this.duties) {
			d.write(buf);
		}

		buf.writeVarInt(this.conspiracy);
	}

	private static PersonalSyncPayload read(RegistryFriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<Standing> standings = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			standings.add(new Standing(NationData.readUuid(buf), buf.readInt(), buf.readBoolean()));
		}

		int m = buf.readVarInt();
		List<DutyData> duties = new ArrayList<>(m);

		for (int i = 0; i < m; i++) {
			duties.add(DutyData.read(buf));
		}

		return new PersonalSyncPayload(standings, duties, buf.readVarInt());
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
