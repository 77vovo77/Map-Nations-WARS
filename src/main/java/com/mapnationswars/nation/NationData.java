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
