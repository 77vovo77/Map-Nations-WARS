package com.mapnationswars.nation;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** A letter between two nations (Map Nations WARS stage 4). */
public final class LetterData {
	public enum Type {
		// display name, what it does for a nation, what it does from one person, nation letter?, personal letter?
		MESSAGE("Message", "Just words.", "Just words. A kind letter makes them like you a little.", true, true),
		GIFT("Gift", "Send emeralds from your treasury. They will like you more.", "Give emeralds you carry. They will like you more.", true, true),
		TRADE("Trade agreement", "Both treasuries earn 3 emeralds a day while it lasts.", "", true, false),
		ALLIANCE("Alliance", "Become allies. They only agree if they like you a lot.", "", true, false),
		PEACE("Peace", "End a war between you.", "Offer emeralds (you carry them) to end your personal war with them.", true, true),
		TRIBUTE("Demand tribute", "Demand emeralds. Weak nations may pay - everyone will hate you.", "", true, false),
		WAR("Declaration of war", "You will be at war: armies, sieges, guards.", "Your own war against them: their village guards will hunt you.", true, true),
		JOIN("Ask to join", "", "Ask to become a citizen of their nation.", false, true),
		PROMOTION("Ask for promotion", "", "Ask your nation for the next rank (you need enough merit).", false, true);

		public final String displayName;
		public final String help;
		public final String personalHelp;
		public final boolean forNations;
		public final boolean forPeople;

		Type(String displayName, String help, String personalHelp, boolean forNations, boolean forPeople) {
			this.displayName = displayName;
			this.help = help;
			this.personalHelp = personalHelp;
			this.forNations = forNations;
			this.forPeople = forPeople;
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
	/** Written by one player for themselves (from = the player), not by a nation. */
	public boolean personal;

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
		buf.writeBoolean(this.personal);
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
		l.personal = buf.readBoolean();
		return l;
	}
}
