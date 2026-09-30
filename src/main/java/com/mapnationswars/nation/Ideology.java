package com.mapnationswars.nation;

/** The ideologies a nation can choose. Each one gives the leader a title and a symbol. */
public enum Ideology {
	// Traditional / Authoritarian
	EMPIRE("Empire / Imperialism", Category.TRADITIONAL, "Emperor", "♛",
			"Strong central authority under an emperor or supreme leader, focused on expansion and hierarchy"),
	FASCISM("Fascism", Category.TRADITIONAL, "Supreme Leader", "⚔",
			"Authoritarian ultranationalism with total state control and cult of the leader"),
	THEOCRACY("Theocracy", Category.TRADITIONAL, "High Priest", "✝",
			"Rule by religious leaders and divine law"),

	// Liberal / Democratic
	LIBERALISM("Liberalism", Category.LIBERAL, "President", "★",
			"Individual rights, free markets, and representative government"),
	CONSERVATISM("Conservatism", Category.LIBERAL, "King", "♔",
			"Tradition, order, gradual change, and strong institutions"),
	LIBERTARIANISM("Libertarianism", Category.LIBERAL, "Guide", "⚑",
			"Maximum personal freedom and minimal government"),

	// Socialist / Collectivist
	SOCIALISM("Socialism", Category.SOCIALIST, "Chairman", "✊",
			"Collective or state ownership of the means of production"),
	COMMUNISM("Communism", Category.SOCIALIST, "General Secretary", "☭",
			"Classless society with common ownership of everything"),
	SYNDICALISM("Syndicalism", Category.SOCIALIST, "Union Chief", "⚒",
			"Workers' unions control the economy and politics"),

	// Alternative / Decentralized
	ANARCHISM("Anarchism", Category.ALTERNATIVE, "Coordinator", "Ⓐ",
			"No centralized state, pure voluntary association"),
	TECHNOCRACY("Technocracy", Category.ALTERNATIVE, "Chief Engineer", "⚙",
			"Rule by scientists, engineers, and technical experts"),
	AGRARIANISM("Agrarianism", Category.ALTERNATIVE, "Elder", "☘",
			"Rural, land-based society focused on farming and local communities"),

	// Neutral / Non-Aligned
	NEUTRAL("Neutral", Category.NEUTRAL, "Leader", "☮",
			"Switzerland mode. You don't pick a side.");

	public enum Category {
		TRADITIONAL("Traditional / Authoritarian", 0xE0B040),
		LIBERAL("Liberal / Democratic", 0x4FA3FF),
		SOCIALIST("Socialist / Collectivist", 0xFF5050),
		ALTERNATIVE("Alternative / Decentralized", 0xB080FF),
		NEUTRAL("Neutral / Non-Aligned", 0xDDDDDD);

		public final String displayName;
		public final int color;

		Category(String displayName, int color) {
			this.displayName = displayName;
			this.color = color;
		}
	}

	public final String displayName;
	public final Category category;
	public final String leaderTitle;
	public final String symbol;
	public final String description;

	Ideology(String displayName, Category category, String leaderTitle, String symbol, String description) {
		this.displayName = displayName;
		this.category = category;
		this.leaderTitle = leaderTitle;
		this.symbol = symbol;
		this.description = description;
	}

	/** Safe lookup by name; unknown names become NEUTRAL. */
	public static Ideology byName(String name) {
		for (Ideology i : values()) {
			if (i.name().equals(name)) {
				return i;
			}
		}

		return NEUTRAL;
	}

	public static boolean exists(String name) {
		for (Ideology i : values()) {
			if (i.name().equals(name)) {
				return true;
			}
		}

		return false;
	}
}
