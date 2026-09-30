package com.mapnationswars.nation;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * One province: a village, pillager outpost, woodland mansion or bastion, with the land around it.
 * Provinces exist from the start of the world; nations own them.
 */
public final class ProvinceData {
	public enum Type {
		VILLAGE("Village"),
		OUTPOST("Pillager Outpost"),
		MANSION("Woodland Mansion"),
		BASTION("Bastion");

		public final String displayName;

		Type(String displayName) {
			this.displayName = displayName;
		}

		public static Type byName(String name) {
			for (Type t : values()) {
				if (t.name().equals(name)) {
					return t;
				}
			}

			return VILLAGE;
		}
	}

	public final UUID id;
	public String name;
	public Type type;
	public String dimension;
	public int x;
	public int z;
	/** The nation that owns it (null = nobody). */
	public UUID nation;
	public String mayorName = "";
	/** Villagers living there (an estimate until someone has been close enough to count them). */
	public int population;
	/** The nation's capital. */
	public boolean capital;
	/** A village with no villagers left, only zombies. */
	public boolean abandoned;
	/** How many chunks its land covers. */
	public int chunks;
	/** Its land (chunk keys). */
	public final java.util.List<Long> area = new java.util.ArrayList<>();

	public ProvinceData(UUID id) {
		this.id = id;
	}

	public String title() {
		return this.abandoned ? "Abandoned " + this.name : this.name;
	}

	public void write(RegistryFriendlyByteBuf buf) {
		NationData.writeUuid(buf, this.id);
		buf.writeUtf(this.name);
		buf.writeUtf(this.type.name());
		buf.writeUtf(this.dimension);
		buf.writeInt(this.x);
		buf.writeInt(this.z);
		buf.writeBoolean(this.nation != null);

		if (this.nation != null) {
			NationData.writeUuid(buf, this.nation);
		}

		buf.writeUtf(this.mayorName);
		buf.writeVarInt(this.population);
		buf.writeBoolean(this.capital);
		buf.writeBoolean(this.abandoned);
		buf.writeVarInt(this.area.size());

		for (long key : this.area) {
			int cx = (int) (key >> 32);
			int cz = (int) key;
			buf.writeVarInt((cx << 1) ^ (cx >> 31));
			buf.writeVarInt((cz << 1) ^ (cz >> 31));
		}
	}

	public static ProvinceData read(RegistryFriendlyByteBuf buf) {
		ProvinceData p = new ProvinceData(NationData.readUuid(buf));
		p.name = buf.readUtf();
		p.type = Type.byName(buf.readUtf());
		p.dimension = buf.readUtf();
		p.x = buf.readInt();
		p.z = buf.readInt();
		p.nation = buf.readBoolean() ? NationData.readUuid(buf) : null;
		p.mayorName = buf.readUtf();
		p.population = buf.readVarInt();
		p.capital = buf.readBoolean();
		p.abandoned = buf.readBoolean();
		int n = buf.readVarInt();

		for (int i = 0; i < n; i++) {
			int zx = buf.readVarInt();
			int zz = buf.readVarInt();
			int cx = (zx >>> 1) ^ -(zx & 1);
			int cz = (zz >>> 1) ^ -(zz & 1);
			p.area.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
		}

		p.chunks = p.area.size();
		return p;
	}
}
