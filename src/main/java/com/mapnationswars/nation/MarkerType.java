package com.mapnationswars.nation;

/**
 * Kinds of map markers.
 * Settlements (capital, city, castle, fort, village) belong to whichever nation owns the land
 * they stand on - if the land changes hands, so does the settlement (and its banner).
 * Important political and geographical places are always visible to everyone.
 */
public enum MarkerType {
	//        name              colour    settlement banner leader limit  alwaysPublic
	CAPITAL("Capital City", 0xFFD54F, true, true, true, 0, true),
	CITY("City", 0x90CAF9, true, true, false, 0, true),
	CASTLE("Castle", 0xCFD8DC, true, true, false, 0, true),
	FORT("Fort", 0xD7B899, true, true, false, 0, true),
	VILLAGE("Village", 0xC5E1A5, true, true, false, 0, true),

	PORT("Port", 0x4FC3F7, false, true, false, 0, true),
	MARKET("Market", 0xFFB74D, false, true, false, 0, true),
	TEMPLE("Temple", 0xE1BEE7, false, true, false, 0, true),
	FARM("Farm", 0xDCE775, false, true, false, 0, false),
	MINE("Mine", 0xB0BEC5, false, true, false, 0, false),

	HOME("Home", 0xFF8A80, false, false, false, 1, false),
	PORTAL("Nether Portal", 0xB388FF, false, false, false, 0, false),
	DANGER("Danger", 0xFF5252, false, false, false, 0, false),
	LANDMARK("Landmark", 0xFFF59D, false, false, false, 0, true),

	ARROW_N("Arrow North", 0xFFFFFF, false, false, false, 0, false),
	ARROW_NE("Arrow North-East", 0xFFFFFF, false, false, false, 0, false),
	ARROW_E("Arrow East", 0xFFFFFF, false, false, false, 0, false),
	ARROW_SE("Arrow South-East", 0xFFFFFF, false, false, false, 0, false),
	ARROW_S("Arrow South", 0xFFFFFF, false, false, false, 0, false),
	ARROW_SW("Arrow South-West", 0xFFFFFF, false, false, false, 0, false),
	ARROW_W("Arrow West", 0xFFFFFF, false, false, false, 0, false),
	ARROW_NW("Arrow North-West", 0xFFFFFF, false, false, false, 0, false),
	X_MARK("X Mark", 0xFF6B6B, false, false, false, 0, false),
	PLUS("Plus", 0xFFFFFF, false, false, false, 0, false),
	CIRCLE("Circle", 0xFFFFFF, false, false, false, 0, false),
	CHECK("Check Mark", 0x9CCC65, false, false, false, 0, false);

	public final String displayName;
	/** Accent colour used for text about this marker. */
	public final int color;
	/** Settlements need space between them and can't be placed in another nation's land. */
	public final boolean settlement;
	/** Shows the banner of the nation that owns the land. */
	public final boolean showsBanner;
	public final boolean leaderOnly;
	/** How many one player may have (0 = no limit). Placing another one moves the old one. */
	public final int playerLimit;
	/** Important places everybody must see: always "Everyone", can't be hidden. */
	public final boolean alwaysPublic;

	MarkerType(String displayName, int color, boolean settlement, boolean showsBanner, boolean leaderOnly, int playerLimit,
			boolean alwaysPublic) {
		this.displayName = displayName;
		this.color = color;
		this.settlement = settlement;
		this.showsBanner = showsBanner;
		this.leaderOnly = leaderOnly;
		this.playerLimit = playerLimit;
		this.alwaysPublic = alwaysPublic;
	}

	/** How many chunks around the marker its borders cover when it's placed (0 = just its own chunk; you drag to make it bigger). */
	public int defaultRadius() {
		return 0;
	}

	/** Biggest area (in chunks) a settlement's borders may cover. */
	public int maxArea() {
		return switch (this) {
			case CAPITAL -> 64;
			case CITY -> 49;
			case VILLAGE -> 25;
			case CASTLE -> 16;
			case FORT -> 9;
			default -> 0;
		};
	}

	public static MarkerType byName(String name) {
		for (MarkerType t : values()) {
			if (t.name().equals(name)) {
				return t;
			}
		}

		return null;
	}

	// ---------------------------------------------------------------- rules shared by client and server

	public static final int PRIVATE = 0;
	public static final int NATION = 1;
	public static final int PUBLIC = 2;

	/** How many markers one player may have for each visibility. */
	public static final int[] VISIBILITY_LIMITS = {30, 15, 12};
	public static final String[] VISIBILITY_NAMES = {"Only me", "My nation", "Everyone"};
	public static final int SETTLEMENT_SPACING = 16;
	public static final int OWN_MARKER_SPACING = 3;
	public static final long COOLDOWN_MS = 3000;
	public static final int MAX_LABEL = 32;
	/** How many players have to vote before someone else's marker is destroyed. */
	public static final int DESTROY_VOTES = 5;
	/** Settlement borders can reach at most this many chunks away from the marker. */
	public static final int AREA_REACH = 6;
}
