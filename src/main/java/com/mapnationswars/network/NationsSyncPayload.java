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

		// claims, grouped by dimension, with small numbers (there can be many thousands of them)
		java.util.Map<UUID, Integer> index = new java.util.HashMap<>();

		for (int i = 0; i < this.nations.size(); i++) {
			index.put(this.nations.get(i).id, i);
		}

		java.util.Map<String, List<Claim>> byDim = new java.util.LinkedHashMap<>();

		for (Claim c : this.claims) {
			if (index.containsKey(c.nation())) {
				byDim.computeIfAbsent(c.dimension(), d -> new ArrayList<>()).add(c);
			}
		}

		buf.writeVarInt(byDim.size());

		for (java.util.Map.Entry<String, List<Claim>> e : byDim.entrySet()) {
			buf.writeUtf(e.getKey());
			buf.writeVarInt(e.getValue().size());

			for (Claim c : e.getValue()) {
				buf.writeVarInt(zigzag(c.chunkX()));
				buf.writeVarInt(zigzag(c.chunkZ()));
				buf.writeVarInt(index.get(c.nation()));
			}
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

		List<Claim> claims = new ArrayList<>();
		int dims = buf.readVarInt();

		for (int d = 0; d < dims; d++) {
			String dim = buf.readUtf();
			int count = buf.readVarInt();

			for (int i = 0; i < count; i++) {
				int x = unzigzag(buf.readVarInt());
				int z = unzigzag(buf.readVarInt());
				int n = buf.readVarInt();

				if (n >= 0 && n < nations.size()) {
					claims.add(new Claim(dim, x, z, nations.get(n).id));
				}
			}
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

	private static int zigzag(int v) {
		return (v << 1) ^ (v >> 31);
	}

	private static int unzigzag(int v) {
		return (v >>> 1) ^ -(v & 1);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
