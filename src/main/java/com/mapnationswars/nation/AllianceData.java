package com.mapnationswars.nation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/**
 * An alliance: a group of whole nations (not players).
 * Only nation leaders join, and they bring their whole nation with them.
 * The first nation in the list is the head of the alliance; its leader runs it.
 */
public final class AllianceData {
	public final UUID id;
	public String name;
	public int color;
	public ItemStack banner = ItemStack.EMPTY;
	/** Member nations, in the order they joined. The first one leads the alliance. */
	public final List<UUID> nations = new ArrayList<>();
	/** Nations asking to join (waiting for the head nation's leader). */
	public final List<UUID> requests = new ArrayList<>();

	public AllianceData(UUID id) {
		this.id = id;
	}

	/** The nation that leads the alliance. */
	public UUID head() {
		return this.nations.isEmpty() ? null : this.nations.get(0);
	}

	public void write(RegistryFriendlyByteBuf buf) {
		NationData.writeUuid(buf, this.id);
		buf.writeUtf(this.name);
		buf.writeInt(this.color);
		ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, this.banner);
		writeIds(buf, this.nations);
		writeIds(buf, this.requests);
	}

	public static AllianceData read(RegistryFriendlyByteBuf buf) {
		AllianceData a = new AllianceData(NationData.readUuid(buf));
		a.name = buf.readUtf();
		a.color = buf.readInt();
		a.banner = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
		readIds(buf, a.nations);
		readIds(buf, a.requests);
		return a;
	}

	private static void writeIds(RegistryFriendlyByteBuf buf, List<UUID> list) {
		buf.writeVarInt(list.size());

		for (UUID id : list) {
			NationData.writeUuid(buf, id);
		}
	}

	private static void readIds(RegistryFriendlyByteBuf buf, List<UUID> into) {
		int n = buf.readVarInt();

		for (int i = 0; i < n; i++) {
			into.add(NationData.readUuid(buf));
		}
	}
}
