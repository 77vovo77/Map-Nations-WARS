package com.mapnationswars;

import java.util.Random;

import com.mapnationswars.nation.Faction;
import com.mapnationswars.nation.Ideology;
import com.mapnationswars.nation.ProvinceData;

/** Made-up names for places, people and nations. */
final class Names {
	private static final String[] VILLAGE_START = {"Ald", "Bren", "Cor", "Dun", "El", "Fal", "Gil", "Har", "Ing", "Kel", "Lor", "Mar",
			"Nor", "Os", "Pen", "Ros", "Sel", "Tor", "Ul", "Val", "Wen", "Yor", "Ash", "Bram", "Cal", "Dor", "Fen", "Glen", "Hol",
			"Lin", "Mil", "Oak", "Red", "Stan", "Thorn", "Wil", "Elm", "Birch", "Stone", "Mead", "Wheat", "Clay"};
	private static final String[] VILLAGE_END = {"ton", "ville", "by", "ford", "holm", "wick", "stead", "bury", "field", "brook",
			"moor", "dale", "mere", "haven", "worth", "ley", "cross", "well", "gate", "wood"};
	private static final String[] LAND_END = {"ia", "ar", "en", "or", "heim", "land", "ria", "mark", "shire", "vale", "ora", "esse"};
	private static final String[] FIRST = {"Aldric", "Bertram", "Cedric", "Dorian", "Edmund", "Fenwick", "Gareth", "Harold", "Ivo",
			"Jasper", "Konrad", "Leopold", "Magnus", "Nils", "Osric", "Percival", "Quentin", "Roland", "Silas", "Tobias",
			"Ulric", "Victor", "Wendel", "Yorick", "Ada", "Brigid", "Clara", "Dagny", "Elsa", "Freya", "Greta", "Hilda",
			"Ingrid", "Juna", "Kira", "Lena", "Maren", "Nora", "Odile", "Petra", "Runa", "Sigrid", "Tilde", "Una", "Vera", "Wilma"};
	private static final String[] ILLAGER_START = {"Vex", "Grim", "Mor", "Krag", "Zul", "Rav", "Dro", "Skar", "Vor", "Nark", "Gall", "Ebon"};
	private static final String[] ILLAGER_END = {"goth", "mar", "rok", "gar", "eth", "ax", "moor", "fang", "hold", "spire"};
	private static final String[] PIGLIN_START = {"Gru", "Snor", "Bar", "Gol", "Ork", "Zug", "Hog", "Brux", "Gor", "Mak", "Rut", "Tusk"};
	private static final String[] PIGLIN_END = {"nak", "gul", "rash", "dor", "gog", "zak", "mog", "grak", "ruk"};

	private Names() {
	}

	private static String pick(Random random, String[] list) {
		return list[random.nextInt(list.length)];
	}

	static String person(Random random) {
		return pick(random, FIRST);
	}

	static String place(Random random, ProvinceData.Type type) {
		return switch (type) {
			case VILLAGE -> pick(random, VILLAGE_START) + pick(random, VILLAGE_END);
			case OUTPOST -> pick(random, ILLAGER_START) + pick(random, ILLAGER_END) + " Watch";
			case MANSION -> pick(random, ILLAGER_START) + pick(random, ILLAGER_END) + " Manor";
			case BASTION -> pick(random, PIGLIN_START) + pick(random, PIGLIN_END);
		};
	}

	/** The ruler of an illager, piglin or undead nation. */
	static String warlord(Random random, ProvinceData.Type type) {
		return switch (type) {
			case OUTPOST, MANSION -> pick(random, ILLAGER_START) + pick(random, ILLAGER_END);
			case BASTION -> pick(random, PIGLIN_START) + pick(random, PIGLIN_END);
			case VILLAGE -> "the Pale " + pick(random, FIRST);
		};
	}

	static String nation(Random random, Faction faction, Ideology ideology, String capital, int size) {
		String land = switch (faction) {
			case ILLAGER -> pick(random, ILLAGER_START) + pick(random, ILLAGER_END);
			case PIGLIN -> pick(random, PIGLIN_START) + pick(random, PIGLIN_END);
			default -> pick(random, VILLAGE_START) + pick(random, LAND_END);
		};

		return switch (faction) {
			case ILLAGER -> size > 2 ? "Illager Dominion of " + land : "Warband of " + land;
			case PIGLIN -> "Piglin Clan of " + land;
			case UNDEAD -> "Undead Horde of " + capital;
			default -> size == 1 ? switch (ideology) {
				case ANARCHISM -> "Free Commune of " + capital;
				case LIBERTARIANISM -> "Free City of " + capital;
				default -> "Village of " + capital;
			} : switch (ideology) {
				case EMPIRE -> "Empire of " + land;
				case CONSERVATISM -> "Kingdom of " + land;
				case LIBERALISM -> "Republic of " + land;
				case THEOCRACY -> "Holy Order of " + land;
				case SOCIALISM -> "People's Union of " + land;
				case TECHNOCRACY -> "Guild State of " + land;
				case AGRARIANISM -> "Farmers' League of " + land;
				default -> "Duchy of " + land;
			};
		};
	}

	/** A colour that fits the faction (villagers: any bright colour; illagers: grey and dark; piglins: gold and red...). */
	static int color(Random random, Faction faction) {
		float hue;
		float sat;
		float bri;

		switch (faction) {
			case ILLAGER -> {
				hue = 0.55f + random.nextFloat() * 0.25f;
				sat = 0.15f + random.nextFloat() * 0.25f;
				bri = 0.35f + random.nextFloat() * 0.25f;
			}
			case PIGLIN -> {
				hue = 0.0f + random.nextFloat() * 0.12f;
				sat = 0.75f + random.nextFloat() * 0.25f;
				bri = 0.7f + random.nextFloat() * 0.3f;
			}
			case UNDEAD -> {
				hue = 0.22f + random.nextFloat() * 0.12f;
				sat = 0.5f + random.nextFloat() * 0.3f;
				bri = 0.3f + random.nextFloat() * 0.2f;
			}
			default -> {
				hue = random.nextFloat();
				sat = 0.55f + random.nextFloat() * 0.4f;
				bri = 0.7f + random.nextFloat() * 0.3f;
			}
		}

		return hsb(hue, sat, bri);
	}

	private static int hsb(float h, float s, float b) {
		float r;
		float g;
		float bl;
		float hh = (h - (float) Math.floor(h)) * 6f;
		int i = (int) hh;
		float f = hh - i;
		float p = b * (1 - s);
		float q = b * (1 - s * f);
		float t = b * (1 - s * (1 - f));

		switch (i) {
			case 0 -> { r = b; g = t; bl = p; }
			case 1 -> { r = q; g = b; bl = p; }
			case 2 -> { r = p; g = b; bl = t; }
			case 3 -> { r = p; g = q; bl = b; }
			case 4 -> { r = t; g = p; bl = b; }
			default -> { r = b; g = p; bl = q; }
		}

		return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (bl * 255);
	}
}
