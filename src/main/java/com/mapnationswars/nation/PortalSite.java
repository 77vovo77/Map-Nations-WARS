package com.mapnationswars.nation;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * A ruined portal - or a portal players built - in the Overworld (Map Nations WARS stage 8).
 * Its activation slowly grows; at 100% it tears open and the Nether invades.
 * Positions are Overworld blocks; the Nether side is at x / 8, z / 8.
 */
public final class PortalSite {
	public static final String OVERWORLD = "minecraft:overworld";
	public static final String NETHER = "minecraft:the_nether";

	public final UUID id;
	public String name = "Ruined Portal";
	public int x;
	public int y = 64;
	public int z;
	/** 0 .. 100 */
	public double activation;
	/** Activation gained per day (worked out by the server, shown on the map). */
	public double rate;
	/** Torn open: the Nether pours through. */
	public boolean open;
	/** Ticks left until an open ruined portal closes again. */
	public long openTicks;
	/** Built (or relit) by players: always usable by armies, and it wakes faster. */
	public boolean playerBuilt;
	public String builder = "";

	public PortalSite(UUID id) {
		this.id = id;
	}

	/** Armies can march through it. */
	public boolean usable() {
		return this.open || this.playerBuilt;
	}

	/** Where it is in a dimension (the Nether side is 8 times closer). */
	public double xIn(String dimension) {
		return NETHER.equals(dimension) ? this.x / 8.0 : this.x;
	}

	public double zIn(String dimension) {
		return NETHER.equals(dimension) ? this.z / 8.0 : this.z;
	}

	public void write(RegistryFriendlyByteBuf buf) {
		NationData.writeUuid(buf, this.id);
		buf.writeUtf(this.name);
		buf.writeInt(this.x);
		buf.writeInt(this.y);
		buf.writeInt(this.z);
		buf.writeFloat((float) this.activation);
		buf.writeFloat((float) this.rate);
		buf.writeBoolean(this.open);
		buf.writeBoolean(this.playerBuilt);
		buf.writeUtf(this.builder);
	}

	public static PortalSite read(RegistryFriendlyByteBuf buf) {
		PortalSite s = new PortalSite(NationData.readUuid(buf));
		s.name = buf.readUtf();
		s.x = buf.readInt();
		s.y = buf.readInt();
		s.z = buf.readInt();
		s.activation = buf.readFloat();
		s.rate = buf.readFloat();
		s.open = buf.readBoolean();
		s.playerBuilt = buf.readBoolean();
		s.builder = buf.readUtf();
		return s;
	}
}
