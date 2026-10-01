package com.mapnationswars.nation;

/**
 * Map Nations WARS stage 7: the rules for founding your own nation and for coups,
 * shared by the server (which decides) and the client (which explains).
 */
public final class Charter {
	public static final int COST = 200;
	public static final int SUPPORT_NEEDED = 100;
	/** With this much support even a happy village follows you. */
	public static final int SUPPORT_BELOVED = 200;
	public static final int COUP_MERIT = 150;
	public static final int COUP_COST = 100;

	private Charter() {
	}

	/** Why a nation can't be founded at this village (null = it can). */
	public static String problem(ProvinceData p, int support, int emeralds) {
		if (p.capital) {
			return p.name + " is a capital - its people will never leave their nation.";
		}

		if (support < SUPPORT_NEEDED) {
			return "The people of " + p.name + " don't back you enough yet (" + support + "/" + SUPPORT_NEEDED
					+ "). Give them emeralds, guard them from monsters and spend time there.";
		}

		if (p.unrest < 40 && p.happiness >= 45 && support < SUPPORT_BELOVED) {
			return p.name + " is happy with its rulers. Wait for unrest (40%+), or win even more of their hearts (" + SUPPORT_BELOVED + " support).";
		}

		if (emeralds < COST) {
			return "A charter costs " + COST + " emeralds (you carry " + emeralds + ").";
		}

		return null;
	}

	/** The chance a coup works (0..1). */
	public static double coupChance(NationData n, java.util.UUID player, double avgHappiness, boolean leaderOnline) {
		double chance = 0.2 + Math.min(0.3, n.merit.getOrDefault(player, 0) / 1000.0) + (50 - avgHappiness) / 100.0;

		if (!n.aiRuled() && leaderOnline) {
			chance -= 0.15; // a player leader on guard
		}

		return Math.max(0.05, Math.min(0.85, chance));
	}
}
