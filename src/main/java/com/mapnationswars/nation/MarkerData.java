package com.mapnationswars.nation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** One marker on the map. */
public final class MarkerData {
	public final UUID id;
	public UUID owner;
	public String ownerName;
	/** The nation of the owner when it was placed (null if they had none). */
	public UUID nation;
	public String dimension;
	public int x;
	public int z;
	public MarkerType type;
	public String label;
	public int visibility;
	public long created;
	/** Players who voted to destroy this marker. */
	public final List<UUID> votes = new ArrayList<>();
	/** Settlements only: the chunks inside its borders. */
	public final List<Long> area = new ArrayList<>();
	/** Settlements only: villagers living inside its borders (counted by the server). */
	public int population;

	/** Map Nations WARS: set when this "marker" is really a province (a village or stronghold of the world). */
	public ProvinceData province;

	public MarkerData(UUID id) {
		this.id = id;
	}

	/** The label, or the type name if no label was written. */
	public String title() {
		return this.label == null || this.label.isBlank() ? this.type.displayName : this.label;
	}

	public void write(RegistryFriendlyByteBuf buf) {
		NationData.writeUuid(buf, this.id);
		NationData.writeUuid(buf, this.owner);
		buf.writeUtf(this.ownerName);
		buf.writeBoolean(this.nation != null);

		if (this.nation != null) {
			NationData.writeUuid(buf, this.nation);
		}

		buf.writeUtf(this.dimension);
		buf.writeInt(this.x);
		buf.writeInt(this.z);
		buf.writeUtf(this.type.name());
		buf.writeUtf(this.label);
		buf.writeVarInt(this.visibility);
		buf.writeLong(this.created);
		buf.writeVarInt(this.votes.size());

		for (UUID v : this.votes) {
			NationData.writeUuid(buf, v);
		}

		buf.writeVarInt(this.area.size());

		for (long key : this.area) {
			buf.writeLong(key);
		}

		buf.writeVarInt(this.population);
	}

	public boolean areaContains(int chunkX, int chunkZ) {
		return this.area.contains(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
	}

	public static MarkerData read(RegistryFriendlyByteBuf buf) {
		MarkerData m = new MarkerData(NationData.readUuid(buf));
		m.owner = NationData.readUuid(buf);
		m.ownerName = buf.readUtf();

		if (buf.readBoolean()) {
			m.nation = NationData.readUuid(buf);
		}

		m.dimension = buf.readUtf();
		m.x = buf.readInt();
		m.z = buf.readInt();
		MarkerType type = MarkerType.byName(buf.readUtf());
		m.type = type != null ? type : MarkerType.LANDMARK;
		m.label = buf.readUtf();
		m.visibility = buf.readVarInt();
		m.created = buf.readLong();
		int votes = buf.readVarInt();

		for (int i = 0; i < votes; i++) {
			m.votes.add(NationData.readUuid(buf));
		}

		int areaSize = buf.readVarInt();

		for (int i = 0; i < areaSize; i++) {
			m.area.add(buf.readLong());
		}

		m.population = buf.readVarInt();

		return m;
	}
}
