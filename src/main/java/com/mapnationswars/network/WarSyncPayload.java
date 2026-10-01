package com.mapnationswars.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.DivisionData;
import com.mapnationswars.nation.NationData;

/** Server -> client: every division, siege and battle (sent once a second while armies are in the field). */
public record WarSyncPayload(List<DivisionData> divisions, List<Siege> sieges, List<Battle> battles) implements CustomPacketPayload {
	/** A province under siege: who besieges it and how far they got (0..100). */
	public record Siege(UUID province, UUID attacker, float progress) {
	}

	/** A battle between two nations at a place. */
	public record Battle(String dimension, float x, float z, UUID a, UUID b) {
	}

	public static final CustomPacketPayload.Type<WarSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("war_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, WarSyncPayload> CODEC =
			CustomPacketPayload.codec(WarSyncPayload::write, WarSyncPayload::read);

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(this.divisions.size());

		for (DivisionData d : this.divisions) {
			d.write(buf);
		}

		buf.writeVarInt(this.sieges.size());

		for (Siege s : this.sieges) {
			NationData.writeUuid(buf, s.province());
			NationData.writeUuid(buf, s.attacker());
			buf.writeFloat(s.progress());
		}

		buf.writeVarInt(this.battles.size());

		for (Battle b : this.battles) {
			buf.writeUtf(b.dimension());
			buf.writeFloat(b.x());
			buf.writeFloat(b.z());
			NationData.writeUuid(buf, b.a());
			NationData.writeUuid(buf, b.b());
		}
	}

	private static WarSyncPayload read(RegistryFriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<DivisionData> divisions = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			divisions.add(DivisionData.read(buf));
		}

		n = buf.readVarInt();
		List<Siege> sieges = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			sieges.add(new Siege(NationData.readUuid(buf), NationData.readUuid(buf), buf.readFloat()));
		}

		n = buf.readVarInt();
		List<Battle> battles = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			battles.add(new Battle(buf.readUtf(), buf.readFloat(), buf.readFloat(), NationData.readUuid(buf), NationData.readUuid(buf)));
		}

		return new WarSyncPayload(divisions, sieges, battles);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
