package com.mapnationswars.nation;

/**
 * Map Nations WARS stage 3: ranks of players in a nation.
 * You earn merit by serving (being in your nation's land, fighting monsters there, giving to its villages).
 * AI nations promote you when you have enough merit; player leaders promote by hand.
 */
public final class Ranks {
	public static final int CITIZEN = 0;
	public static final int SOLDIER = 1;
	public static final int OFFICER = 2;
	public static final int MINISTER = 3;

	public static final String[] NAMES = {"Citizen", "Soldier", "Officer", "Minister"};
	/** Merit needed to be promoted to each rank (in AI-ruled nations). */
	public static final int[] MERIT = {0, 20, 80, 250};
	/** Emeralds paid per day for each rank. */
	public static final int[] SALARY = {0, 2, 5, 10};
	public static final int LEADER_SALARY = 15;
	/** Days between elections (in nations whose ideology has them). */
	public static final int ELECTION_DAYS = 7;

	private Ranks() {
	}

	public static String name(int rank) {
		return NAMES[Math.max(0, Math.min(NAMES.length - 1, rank))];
	}

	/** Ideologies where the people vote for the leader. */
	public static boolean hasElections(Ideology ideology) {
		return switch (ideology) {
			case LIBERALISM, LIBERTARIANISM, SOCIALISM, SYNDICALISM, AGRARIANISM, ANARCHISM, TECHNOCRACY -> true;
			default -> false;
		};
	}

	private static final java.util.UUID NOBODY = new java.util.UUID(0, 0);

	public static java.util.UUID nobody() {
		return NOBODY;
	}
}
