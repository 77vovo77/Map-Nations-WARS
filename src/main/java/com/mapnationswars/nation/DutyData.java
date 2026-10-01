package com.mapnationswars.nation;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * A duty (Map Nations WARS 1.9): a small task a nation gives its members - or a village gives a stranger who wants
 * its people's support. Done automatically when the progress is full.
 */
public final class DutyData {
	public enum Type {
		KILL("Hunt monsters"),
		DONATE("Bring emeralds"),
		VISIT("Visit"),
		PATROL("Patrol"),
		FIGHT("Fight the enemy");

		public final String displayName;

		Type(String displayName) {
			this.displayName = displayName;
		}
	}

	public final UUID id;
	public Type type = Type.KILL;
	/** The nation that gave it (members), or null when a village gave it (strangers). */
	public UUID nation;
	/** The province it is about (or the village that gave it). */
	public UUID province;
	public String text = "";
	public int needed = 1;
	public int progress;
	public int rewardMerit;
	public int rewardEmeralds;
	public int rewardSupport;

	public DutyData(UUID id) {
		this.id = id;
	}

	public String reward() {
		StringBuilder b = new StringBuilder();

		if (this.rewardMerit > 0) {
			b.append("+").append(this.rewardMerit).append(" merit");
		}

		if (this.rewardEmeralds > 0) {
			b.append(b.length() > 0 ? ", " : "").append("+").append(this.rewardEmeralds).append(" emeralds");
		}

		if (this.rewardSupport > 0) {
			b.append(b.length() > 0 ? ", " : "").append("+").append(this.rewardSupport).append(" support");
		}

		return b.toString();
	}

	public void write(RegistryFriendlyByteBuf buf) {
		NationData.writeUuid(buf, this.id);
		buf.writeVarInt(this.type.ordinal());
		buf.writeBoolean(this.nation != null);

		if (this.nation != null) {
			NationData.writeUuid(buf, this.nation);
		}

		buf.writeBoolean(this.province != null);

		if (this.province != null) {
			NationData.writeUuid(buf, this.province);
		}

		buf.writeUtf(this.text);
		buf.writeVarInt(this.needed);
		buf.writeVarInt(this.progress);
		buf.writeVarInt(this.rewardMerit);
		buf.writeVarInt(this.rewardEmeralds);
		buf.writeVarInt(this.rewardSupport);
	}

	public static DutyData read(RegistryFriendlyByteBuf buf) {
		DutyData d = new DutyData(NationData.readUuid(buf));
		d.type = Type.values()[Math.min(Type.values().length - 1, buf.readVarInt())];
		d.nation = buf.readBoolean() ? NationData.readUuid(buf) : null;
		d.province = buf.readBoolean() ? NationData.readUuid(buf) : null;
		d.text = buf.readUtf();
		d.needed = buf.readVarInt();
		d.progress = buf.readVarInt();
		d.rewardMerit = buf.readVarInt();
		d.rewardEmeralds = buf.readVarInt();
		d.rewardSupport = buf.readVarInt();
		return d;
	}
}
