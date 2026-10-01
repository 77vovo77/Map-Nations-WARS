package com.mapnationswars.nation;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * One army division (Map Nations WARS stage 5). Divisions march across the map, fight battles
 * against divisions of nations they are at war with, and besiege enemy provinces.
 * Positions are in blocks; the fighting is worked out by the server once a second.
 */
public final class DivisionData {
	public enum Kind {
		// cost, soldiers, attack, defence, speed (blocks / second), siege power
		INFANTRY("Infantry", 40, 100, 1.0, 1.2, 3.0, 1.0, "⚔"),
		CAVALRY("Cavalry", 70, 60, 1.5, 0.8, 7.0, 0.5, "♞"),
		ARCHERS("Archers", 55, 80, 1.3, 0.9, 3.0, 0.8, "➹"),
		SIEGE("Siege", 90, 40, 0.6, 0.5, 1.5, 3.0, "♜");

		public final String displayName;
		public final int cost;
		public final int maxStrength;
		public final double attack;
		public final double defence;
		public final double speed;
		public final double siege;
		public final String symbol;

		Kind(String displayName, int cost, int maxStrength, double attack, double defence, double speed, double siege, String symbol) {
			this.displayName = displayName;
			this.cost = cost;
			this.maxStrength = maxStrength;
			this.attack = attack;
			this.defence = defence;
			this.speed = speed;
			this.siege = siege;
			this.symbol = symbol;
		}

		/** Emeralds a day to keep the division. */
		public int upkeep() {
			return Math.max(2, this.cost / 10);
		}

		/** What the soldiers are called in each faction. */
		public String unitName(Faction f) {
			return switch (f) {
				case ILLAGER -> switch (this) {
					case INFANTRY -> "Vindicators";
					case CAVALRY -> "Ravager Riders";
					case ARCHERS -> "Pillagers";
					case SIEGE -> "Siege Ravagers";
				};
				case PIGLIN -> switch (this) {
					case INFANTRY -> "Brutes";
					case CAVALRY -> "Hoglin Riders";
					case ARCHERS -> "Crossbow Piglins";
					case SIEGE -> "Gold Rams";
				};
				case UNDEAD -> switch (this) {
					case INFANTRY -> "Horde";
					case CAVALRY -> "Skeleton Riders";
					case ARCHERS -> "Bone Archers";
					case SIEGE -> "Abominations";
				};
				default -> switch (this) {
					case INFANTRY -> "Militia";
					case CAVALRY -> "Lancers";
					case ARCHERS -> "Bowmen";
					case SIEGE -> "Battering Rams";
				};
			};
		}

		public static Kind byName(String name) {
			for (Kind k : values()) {
				if (k.name().equals(name)) {
					return k;
				}
			}

			return INFANTRY;
		}
	}

	public enum State {
		IDLE("Holding"),
		MARCHING("Marching"),
		FIGHTING("In battle"),
		SIEGING("Besieging"),
		RETREATING("Retreating");

		public final String displayName;

		State(String displayName) {
			this.displayName = displayName;
		}
	}

	public final UUID id;
	public UUID nation;
	public String name = "";
	public Kind kind = Kind.INFANTRY;
	/** Soldiers left (0 .. kind.maxStrength). */
	public double strength;
	/** 0 = breaking, 100 = eager. Below 15 the division retreats. */
	public double morale = 70;
	public String dimension = "minecraft:overworld";
	public double x;
	public double z;
	/** Where it is marching to. */
	public double goalX;
	public double goalZ;
	/** The province it is marching to (to defend it, or to besiege it), or null. */
	public UUID target;
	public State state = State.IDLE;
	/** Where it was raised. */
	public UUID home;
	/** The player who gave the last order (the game's own generals leave such divisions alone for a while). */
	public UUID commander;
	public long orderTime;
	/** The portal it marches to, to come out on the other side (server only, stage 8). */
	public UUID portal;

	public DivisionData(UUID id) {
		this.id = id;
	}

	public int soldiers() {
		return (int) Math.ceil(Math.max(0, this.strength));
	}

	public void write(RegistryFriendlyByteBuf buf) {
		NationData.writeUuid(buf, this.id);
		NationData.writeUuid(buf, this.nation);
		buf.writeUtf(this.name);
		buf.writeVarInt(this.kind.ordinal());
		buf.writeVarInt(this.soldiers());
		buf.writeVarInt((int) Math.round(this.morale));
		buf.writeUtf(this.dimension);
		buf.writeFloat((float) this.x);
		buf.writeFloat((float) this.z);
		buf.writeFloat((float) this.goalX);
		buf.writeFloat((float) this.goalZ);
		buf.writeBoolean(this.target != null);

		if (this.target != null) {
			NationData.writeUuid(buf, this.target);
		}

		buf.writeVarInt(this.state.ordinal());
	}

	public static DivisionData read(RegistryFriendlyByteBuf buf) {
		DivisionData d = new DivisionData(NationData.readUuid(buf));
		d.nation = NationData.readUuid(buf);
		d.name = buf.readUtf();
		d.kind = Kind.values()[Math.min(Kind.values().length - 1, buf.readVarInt())];
		d.strength = buf.readVarInt();
		d.morale = buf.readVarInt();
		d.dimension = buf.readUtf();
		d.x = buf.readFloat();
		d.z = buf.readFloat();
		d.goalX = buf.readFloat();
		d.goalZ = buf.readFloat();
		d.target = buf.readBoolean() ? NationData.readUuid(buf) : null;
		d.state = State.values()[Math.min(State.values().length - 1, buf.readVarInt())];
		return d;
	}
}
