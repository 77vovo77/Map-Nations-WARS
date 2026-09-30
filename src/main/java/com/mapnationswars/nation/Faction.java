package com.mapnationswars.nation;

/** Who a nation belongs to. Players found player nations; the others are run by the game. */
public enum Faction {
	PLAYER("Player nation", 0xFFD54F),
	VILLAGER("Villagers", 0x7CB342),
	ILLAGER("Illagers", 0x8D8D8D),
	PIGLIN("Piglins", 0xFFA000),
	UNDEAD("Undead", 0x4E7D3A);

	public final String displayName;
	public final int color;

	Faction(String displayName, int color) {
		this.displayName = displayName;
		this.color = color;
	}

	public static Faction byName(String name) {
		for (Faction f : values()) {
			if (f.name().equals(name)) {
				return f;
			}
		}

		return PLAYER;
	}
}
