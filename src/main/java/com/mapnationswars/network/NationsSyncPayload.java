package com.mapnationswars.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.AllianceData;
import com.mapnationswars.nation.NationData;

/** Server -> client: every nation and every claimed chunk. */
public record NationsSyncPayload(List<NationData> nations, List<Claim> claims, List<Proposal> proposals, List<AllianceData> alliances) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<NationsSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("nations_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, NationsSyncPayload> CODEC =
			CustomPacketPayload.codec(NationsSyncPayload::write, NationsSyncPayload::read);

	public record Claim(String dimension, int chunkX, int chunkZ, UUID nation) {
	}

	/** Land an officer proposed; the leader still has to accept it. */
	public record Proposal(String dimension, int chunkX, int chunkZ, UUID nation, String proposer) {
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(this.nations.size());

		for (NationData n : this.nations) {
			n.write(buf);
		}

		buf.writeVarInt(this.claims.size());

		for (Claim c : this.claims) {
			buf.writeUtf(c.dimension());
			buf.writeInt(c.chunkX());
			buf.writeInt(c.chunkZ());
			NationData.writeUuid(buf, c.nation());
		}

		buf.writeVarInt(this.proposals.size());

		for (Proposal p : this.proposals) {
			buf.writeUtf(p.dimension());
			buf.writeInt(p.chunkX());
			buf.writeInt(p.chunkZ());
			NationData.writeUuid(buf, p.nation());
			buf.writeUtf(p.proposer());
		}

		buf.writeVarInt(this.alliances.size());

		for (AllianceData a : this.alliances) {
			a.write(buf);
		}
	}

	private static NationsSyncPayload read(RegistryFriendlyByteBuf buf) {
		int nationCount = buf.readVarInt();
		List<NationData> nations = new ArrayList<>(nationCount);

		for (int i = 0; i < nationCount; i++) {
			nations.add(NationData.read(buf));
		}

		int claimCount = buf.readVarInt();
		List<Claim> claims = new ArrayList<>(claimCount);

		for (int i = 0; i < claimCount; i++) {
			String dim = buf.readUtf();
			int x = buf.readInt();
			int z = buf.readInt();
			UUID nation = NationData.readUuid(buf);
			claims.add(new Claim(dim, x, z, nation));
		}

		int proposalCount = buf.readVarInt();
		List<Proposal> proposals = new ArrayList<>(proposalCount);

		for (int i = 0; i < proposalCount; i++) {
			String dim = buf.readUtf();
			int x = buf.readInt();
			int z = buf.readInt();
			UUID nation = NationData.readUuid(buf);
			proposals.add(new Proposal(dim, x, z, nation, buf.readUtf()));
		}

		int allianceCount = buf.readVarInt();
		List<AllianceData> alliances = new ArrayList<>(allianceCount);

		for (int i = 0; i < allianceCount; i++) {
			alliances.add(AllianceData.read(buf));
		}

		return new NationsSyncPayload(nations, claims, proposals, alliances);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
