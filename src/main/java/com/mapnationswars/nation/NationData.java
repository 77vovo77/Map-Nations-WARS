package com.mapnationswars.nation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/** One nation: its look, ideology, leader, members (in join order) and join requests. */
public final class NationData {
	public record Member(UUID id, String name) {
	}

	public final UUID id;
	public String name;
	public int color;
	public Ideology ideology;
	public ItemStack banner = ItemStack.EMPTY;
	public UUID leader;
	/** Members in the order they joined. The leader is also in this list. */
	public final List<Member> members = new ArrayList<>();
	/** Players asking to join. */
	public final List<Member> requests = new ArrayList<>();
	/** Allied nations. */
	public final List<UUID> allies = new ArrayList<>();
	/** Nations that offered us an alliance (waiting for our leader). */
	public final List<UUID> allyRequests = new ArrayList<>();
	/** Villagers living in the nation's land (counted by the server). */
	public int population;
	/** Run by the game, not by a player. */
	public boolean ai = false;
	public Faction faction = Faction.PLAYER;
	/** The name of an AI nation's ruler (AI nations have no player leader). */
	public String rulerName = "";
	/** Emeralds in the nation's treasury (kept by the server, can't be stolen). */
	public long treasury = 0;
	/** Treasury change on the last day (taxes minus upkeep). */
	public int lastBalance = 0;

	// ---------------------------------------------------------------- 2.0: taxes and the treasury's ledger
	public static final String[] TAX_NAMES = {"Low", "Normal", "High", "Harsh"};
	/** Share of the villages' income that goes to the treasury. */
	public static final double[] TAX_RATES = {0.3, 0.5, 0.65, 0.8};
	/** How taxes change the villages' mood (happiness target). */
	public static final int[] TAX_MOOD = {8, 0, -7, -16};
	public int taxLevel = 1;
	/** Yesterday's money: what came in and went out, by reason. */
	public final java.util.LinkedHashMap<String, Integer> ledger = new java.util.LinkedHashMap<>();

	public void book(String reason, int amount) {
		if (amount != 0) {
			this.ledger.merge(reason, amount, Integer::sum);
		}
	}

	public double taxRate() {
		return TAX_RATES[Math.max(0, Math.min(3, this.taxLevel))];
	}

	// ---------------------------------------------------------------- stage 3: ranks, merit, salaries, elections
	/** player -> rank (Ranks.CITIZEN ... Ranks.MINISTER) */
	public final java.util.Map<UUID, Integer> ranks = new java.util.HashMap<>();
	/** player -> merit earned by serving the nation */
	public final java.util.Map<UUID, Integer> merit = new java.util.HashMap<>();
	/** player -> salary not collected yet (emeralds) */
	public final java.util.Map<UUID, Integer> owed = new java.util.HashMap<>();
	/** Players running in the next election. */
	public final List<UUID> candidates = new ArrayList<>();
	/** Day (game time / 24000) of the next election; 0 = none planned. */
	public long nextElection = 0;
	public String lastElection = "";
	/** 2.1: campaign points of each candidate (speeches, posters, feasts) */
	public final java.util.Map<UUID, Integer> campaign = new java.util.HashMap<>();
	/** 2.1: member -> the candidate they vote for */
	public final java.util.Map<UUID, UUID> votes = new java.util.HashMap<>();
	/** 2.1: the last election's result: name -> votes */
	public final java.util.LinkedHashMap<String, Integer> lastResults = new java.util.LinkedHashMap<>();

	public int rankOf(UUID player) {
		return this.ranks.getOrDefault(player, Ranks.CITIZEN);
	}

	/** True while the nation is ruled by the game (no player is its leader). */
	public boolean aiRuled() {
		return this.ai && !this.isMember(this.leader);
	}

	/** Members with the Officer rank: they can propose new territory. */
	public final List<UUID> officers = new ArrayList<>();
	public boolean isOfficer(UUID player) {
		return this.officers.contains(player);
	}

	public NationData(UUID id) {
		this.id = id;
	}

	public boolean isMember(UUID player) {
		return this.member(player) != null;
	}

	public Member member(UUID player) {
		for (Member m : this.members) {
			if (m.id().equals(player)) {
				return m;
			}
		}

		return null;
	}

	public boolean hasRequest(UUID player) {
		for (Member m : this.requests) {
			if (m.id().equals(player)) {
				return true;
			}
		}

		return false;
	}

	public boolean removeRequest(UUID player) {
		return this.requests.removeIf(m -> m.id().equals(player));
	}

	public String leaderName() {
		if (this.ai && this.member(this.leader) == null) {
			return this.rulerName;
		}

		Member m = this.member(this.leader);
		return m != null ? m.name() : "?";
	}

	// ---------------------------------------------------------------- network

	public void write(RegistryFriendlyByteBuf buf) {
		writeUuid(buf, this.id);
		buf.writeUtf(this.name);
		buf.writeInt(this.color);
		buf.writeUtf(this.ideology.name());
		ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, this.banner);
		writeUuid(buf, this.leader);
		writeMembers(buf, this.members);
		writeMembers(buf, this.requests);
		writeIds(buf, this.allies);
		writeIds(buf, this.allyRequests);
		buf.writeVarInt(this.population);
		writeIds(buf, this.officers);
		buf.writeBoolean(this.ai);
		buf.writeUtf(this.faction.name());
		buf.writeUtf(this.rulerName);
		buf.writeVarLong(this.treasury);
		buf.writeInt(this.lastBalance);
		writeIntMap(buf, this.ranks);
		writeIntMap(buf, this.merit);
		writeIntMap(buf, this.owed);
		writeIds(buf, this.candidates);
		buf.writeVarLong(this.nextElection);
		buf.writeUtf(this.lastElection);
		writeIntMap(buf, this.campaign);
		buf.writeVarInt(this.votes.size());

		for (java.util.Map.Entry<UUID, UUID> e : this.votes.entrySet()) {
			writeUuid(buf, e.getKey());
			writeUuid(buf, e.getValue());
		}

		buf.writeVarInt(this.lastResults.size());

		for (java.util.Map.Entry<String, Integer> e : this.lastResults.entrySet()) {
			buf.writeUtf(e.getKey());
			buf.writeVarInt(e.getValue());
		}

		buf.writeVarInt(this.taxLevel);
		buf.writeVarInt(this.ledger.size());

		for (java.util.Map.Entry<String, Integer> e : this.ledger.entrySet()) {
			buf.writeUtf(e.getKey());
			buf.writeInt(e.getValue());
		}
	}

	private static void writeIntMap(RegistryFriendlyByteBuf buf, java.util.Map<UUID, Integer> map) {
		buf.writeVarInt(map.size());

		for (java.util.Map.Entry<UUID, Integer> e : map.entrySet()) {
			writeUuid(buf, e.getKey());
			buf.writeVarInt(e.getValue());
		}
	}

	private static void readIntMap(RegistryFriendlyByteBuf buf, java.util.Map<UUID, Integer> into) {
		int n = buf.readVarInt();

		for (int i = 0; i < n; i++) {
			UUID id = readUuid(buf);
			into.put(id, buf.readVarInt());
		}
	}

	public static NationData read(RegistryFriendlyByteBuf buf) {
		NationData n = new NationData(readUuid(buf));
		n.name = buf.readUtf();
		n.color = buf.readInt();
		n.ideology = Ideology.byName(buf.readUtf());
		n.banner = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
		n.leader = readUuid(buf);
		readMembers(buf, n.members);
		readMembers(buf, n.requests);
		readIds(buf, n.allies);
		readIds(buf, n.allyRequests);
		n.population = buf.readVarInt();
		readIds(buf, n.officers);
		n.ai = buf.readBoolean();
		n.faction = Faction.byName(buf.readUtf());
		n.rulerName = buf.readUtf();
		n.treasury = buf.readVarLong();
		n.lastBalance = buf.readInt();
		readIntMap(buf, n.ranks);
		readIntMap(buf, n.merit);
		readIntMap(buf, n.owed);
		readIds(buf, n.candidates);
		n.nextElection = buf.readVarLong();
		n.lastElection = buf.readUtf();
		readIntMap(buf, n.campaign);
		int votes = buf.readVarInt();

		for (int i = 0; i < votes; i++) {
			UUID voter = readUuid(buf);
			n.votes.put(voter, readUuid(buf));
		}

		int results = buf.readVarInt();

		for (int i = 0; i < results; i++) {
			n.lastResults.put(buf.readUtf(), buf.readVarInt());
		}

		n.taxLevel = buf.readVarInt();
		int entries = buf.readVarInt();

		for (int i = 0; i < entries; i++) {
			n.ledger.put(buf.readUtf(), buf.readInt());
		}

		return n;
	}

	private static void writeMembers(RegistryFriendlyByteBuf buf, List<Member> list) {
		buf.writeVarInt(list.size());

		for (Member m : list) {
			writeUuid(buf, m.id());
			buf.writeUtf(m.name());
		}
	}

	private static void readMembers(RegistryFriendlyByteBuf buf, List<Member> into) {
		int size = buf.readVarInt();

		for (int i = 0; i < size; i++) {
			UUID id = readUuid(buf);
			into.add(new Member(id, buf.readUtf()));
		}
	}

	private static void writeIds(RegistryFriendlyByteBuf buf, List<UUID> list) {
		buf.writeVarInt(list.size());

		for (UUID id : list) {
			writeUuid(buf, id);
		}
	}

	private static void readIds(RegistryFriendlyByteBuf buf, List<UUID> into) {
		int size = buf.readVarInt();

		for (int i = 0; i < size; i++) {
			into.add(readUuid(buf));
		}
	}

	public static void writeUuid(RegistryFriendlyByteBuf buf, UUID uuid) {
		buf.writeLong(uuid.getMostSignificantBits());
		buf.writeLong(uuid.getLeastSignificantBits());
	}

	public static UUID readUuid(RegistryFriendlyByteBuf buf) {
		long most = buf.readLong();
		long least = buf.readLong();
		return new UUID(most, least);
	}
}
