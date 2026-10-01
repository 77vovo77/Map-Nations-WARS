package com.mapnationswars.nation;

import java.util.UUID;

/**
 * How nations feel about each other (Map Nations WARS stage 4).
 * The base comes from who they are (faction and ideology); letters, gifts, demands and wars change it.
 */
public final class Relations {
	/** Opinion changes, war and trade between two nations. */
	public static final class Relation {
		public int modifier;
		public boolean war;
		public boolean trade;
	}

	private Relations() {
	}

	/** Same key for (a, b) and (b, a). */
	public static String key(UUID a, UUID b) {
		return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
	}

	/** How much two nations like each other just because of who they are. */
	public static int base(NationData a, NationData b) {
		int o = 0;
		Faction fa = a.faction;
		Faction fb = b.faction;

		if (fa == fb && fa != Faction.PLAYER) {
			o += fa == Faction.PIGLIN ? 5 : 20;
		} else if (isLiving(fa) && isLiving(fb)) {
			o += 5;
		}

		if ((fa == Faction.UNDEAD) != (fb == Faction.UNDEAD)) {
			o -= 70; // the dead hate the living
		} else if ((fa == Faction.ILLAGER && fb == Faction.VILLAGER) || (fb == Faction.ILLAGER && fa == Faction.VILLAGER)) {
			o -= 50;
		} else if ((fa == Faction.PIGLIN) != (fb == Faction.PIGLIN)) {
			o -= 30;
		}

		if (a.ideology == b.ideology) {
			o += 20;
		} else if (a.ideology.category == b.ideology.category) {
			o += 10;
		} else if ((a.ideology.category == Ideology.Category.TRADITIONAL) != (b.ideology.category == Ideology.Category.TRADITIONAL)) {
			o -= 10;
		}

		return o;
	}

	private static boolean isLiving(Faction f) {
		return f == Faction.VILLAGER || f == Faction.PLAYER;
	}

	public static int clamp(int opinion) {
		return Math.max(-100, Math.min(100, opinion));
	}

	public static String label(int opinion) {
		if (opinion >= 60) {
			return "Loyal friends";
		} else if (opinion >= 25) {
			return "Friendly";
		} else if (opinion > -25) {
			return "Neutral";
		} else if (opinion > -60) {
			return "Hostile";
		}

		return "Hateful";
	}

	public static int color(int opinion) {
		return opinion >= 25 ? 0x7CFF7C : (opinion > -25 ? 0xDDDDDD : (opinion > -60 ? 0xFFB060 : 0xFF6060));
	}
}
