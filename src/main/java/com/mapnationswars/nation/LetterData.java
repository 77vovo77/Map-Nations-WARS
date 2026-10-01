package com.mapnationswars.nation;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** A letter between two nations (Map Nations WARS stage 4). */
public final class LetterData {
	public enum Type {
		MESSAGE("Message", "Just words."),
		GIFT("Gift", "Send emeralds from your treasury. They will like you more."),
		TRADE("Trade agreement", "Both treasuries earn 3 emeralds a day while it lasts."),
		ALLIANCE("Alliance", "Become allies. They only agree if they like you a lot."),
		PEACE("Peace", "End a war between you."),
		TRIBUTE("Demand tribute", "Demand emeralds. Weak nations may pay - everyone will hate you."),
		WAR("Declaration of war", "You will be at war. Battles come in the next stage.");

		public final String displayName;
		public final String help;

		Type(String displayName, String help) {
			this.displayName = displayName;
			this.help = help;
		}

		public static Type byName(String name) {
			for (Type t : values()) {
				if (t.name().equals(name)) {
					return t;
				}
			}

			return MESSAGE;
		}
	}

	public enum Status { PENDING, ACCEPTED, REFUSED, DONE }

	public final UUID id;
	public UUID from;
	public UUID to;
	public String sender = "";
	public Type type = Type.MESSAGE;
	public int amount;
	public String text = "";
	public long day;
	public Status status = Status.PENDING;
	public String reply = "";

	public LetterData(UUID id) {
		this.id = id;
	}

	public void write(RegistryFriendlyByteBuf buf) {
		NationData.writeUuid(buf, this.id);
		NationData.writeUuid(buf, this.from);
		NationData.writeUuid(buf, this.to);
		buf.writeUtf(this.sender);
		buf.writeUtf(this.type.name());
		buf.writeVarInt(this.amount);
		buf.writeUtf(this.text);
		buf.writeVarLong(this.day);
		buf.writeUtf(this.status.name());
		buf.writeUtf(this.reply);
	}

	public static LetterData read(RegistryFriendlyByteBuf buf) {
		LetterData l = new LetterData(NationData.readUuid(buf));
		l.from = NationData.readUuid(buf);
		l.to = NationData.readUuid(buf);
		l.sender = buf.readUtf();
		l.type = Type.byName(buf.readUtf());
		l.amount = buf.readVarInt();
		l.text = buf.readUtf();
		l.day = buf.readVarLong();
		l.status = Status.valueOf(buf.readUtf());
		l.reply = buf.readUtf();
		return l;
	}
}
