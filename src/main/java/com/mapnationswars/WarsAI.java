package com.mapnationswars;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import com.mapnationswars.nation.DivisionData;
import com.mapnationswars.nation.Faction;
import com.mapnationswars.nation.LetterData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.nation.Relations;

/**
 * Map Nations WARS stage 6: nations ruled by the game act on their own.
 * Once a day every AI ruler thinks about the world: who to trade with, who to befriend, who to threaten,
 * when to go to war and when to beg for peace - each faction in its own way. Their generals raise armies,
 * relieve besieged provinces, intercept invaders and march on the enemy, every few seconds.
 * Players can be on the receiving end: the AI writes real letters to player-led nations.
 * Saved in the world folder as mapnationswars_ai.json.
 */
public final class WarsAI {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** No AI wars in the first days of a world. */
	private static final int GRACE_DAYS = 3;
	/** Days between two war declarations of the same nation. */
	private static final int WAR_COOLDOWN = 5;
	/** How long a player's order to an AI nation's army is respected (ticks). */
	private static final long PLAYER_ORDER_TICKS = 12000;
	/** Capitals closer than this (blocks) are neighbours. */
	private static final double NEIGHBOUR = 1600;

	private static final Random RANDOM = new Random();
	/** nation -> day it last declared war */
	private static final Map<UUID, Long> LAST_WAR = new HashMap<>();
	/** "from|to|TYPE" -> day of the last letter */
	private static final Map<String, Long> LAST_LETTER = new HashMap<>();
	/** pair key -> day the war started */
	private static final Map<String, Long> WAR_START = new HashMap<>();
	/** pair key -> nation -> provinces it took in this war */
	private static final Map<String, Map<UUID, Integer>> CAPTURES = new HashMap<>();
	/** nation -> day the dead last rose */
	private static final Map<UUID, Long> LAST_RISE = new HashMap<>();
	private static Path file;
	private static int ticks = 0;

	private WarsAI() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(WarsAI::load);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save();
			LAST_WAR.clear();
			LAST_LETTER.clear();
			WAR_START.clear();
			CAPTURES.clear();
			LAST_RISE.clear();
			file = null;
		});

		// the generals think every 10 seconds
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 200 == 0 && WarsWorld.isGenerated()) {
				generals(server);
			}
		});
	}

	private static long day(MinecraftServer server) {
		return server.overworld().getGameTime() / WarsEconomy.DAY_TICKS;
	}

	// ---------------------------------------------------------------- events

	static void onWarStarted(MinecraftServer server, NationData a, NationData b) {
		String key = Relations.key(a.id, b.id);
		WAR_START.put(key, day(server));
		CAPTURES.remove(key);
	}

	static void onCapture(NationData winner, UUID loser) {
		if (winner == null || loser == null) {
			return;
		}

		CAPTURES.computeIfAbsent(Relations.key(winner.id, loser), k -> new HashMap<>()).merge(winner.id, 1, Integer::sum);
	}

	private static int captures(UUID by, UUID against) {
		Map<UUID, Integer> m = CAPTURES.get(Relations.key(by, against));
		return m == null ? 0 : m.getOrDefault(by, 0);
	}

	private static long warDays(MinecraftServer server, UUID a, UUID b) {
		Long start = WAR_START.get(Relations.key(a, b));
		return start == null ? 0 : day(server) - start;
	}

	/** Power for deciding wars: armies, garrisons, players. */
	private static double power(NationData n) {
		return WarsWar.armyStrength(n.id) + n.members.size() * 40 + n.treasury / 5.0;
	}

	/** Would this AI nation (me) make peace with them? */
	static boolean acceptsPeace(MinecraftServer server, NationData me, NationData them, int opinion) {
		int theyTook = captures(them.id, me.id);
		int weTook = captures(me.id, them.id);
		double ratio = power(me) / Math.max(1, power(them));
		long days = warDays(server, me.id, them.id);

		if (me.faction == Faction.UNDEAD) {
			return ratio < 0.5 && RANDOM.nextInt(3) == 0; // the dead hardly ever stop
		}

		if (theyTook > weTook || ratio < 0.8) {
			return true; // losing
		}

		if (weTook > theyTook && ratio > 1.2) {
			return false; // winning
		}

		return opinion > -40 || days >= 10 || RANDOM.nextInt(4) == 0;
	}

	// ---------------------------------------------------------------- once a day: the rulers

	static void runDay(MinecraftServer server) {
		long day = day(server);
		List<NationData> nations = new ArrayList<>(ServerNations.allNations());

		for (NationData n : nations) {
			if (ServerNations.nation(n.id) == null || !n.aiRuled()) {
				continue;
			}

			try {
				diplomacy(server, n, nations, day);
				military(server, n, day);
			} catch (Exception e) {
				MapNationsMod.LOGGER.warn("Map Nations WARS: AI of {} failed", n.name, e);
			}
		}

		save();
	}

	private static ProvinceData capital(UUID nation) {
		ProvinceData any = null;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (nation.equals(p.nation)) {
				if (p.capital) {
					return p;
				}

				any = p;
			}
		}

		return any;
	}

	private static double distance(NationData a, NationData b) {
		ProvinceData ca = capital(a.id);
		ProvinceData cb = capital(b.id);

		if (ca == null || cb == null || !ca.dimension.equals(cb.dimension)) {
			return Double.MAX_VALUE;
		}

		return Math.hypot(ca.x - cb.x, ca.z - cb.z);
	}

	private static boolean recently(UUID from, UUID to, LetterData.Type type, long day, int days) {
		Long last = LAST_LETTER.get(from + "|" + to + "|" + type.name());
		return last != null && day - last < days;
	}

	private static void letter(MinecraftServer server, NationData from, NationData to, LetterData.Type type, int amount, String text, long day) {
		LAST_LETTER.put(from.id + "|" + to.id + "|" + type.name(), day);
		WarsDiplomacy.aiLetter(server, from, to, type, amount, text);
	}

	private static void diplomacy(MinecraftServer server, NationData me, List<NationData> nations, long day) {
		List<NationData> enemies = WarsDiplomacy.enemiesOf(me);

		// 1. peace when the war goes badly (or drags on)
		for (NationData enemy : enemies) {
			int theyTook = captures(enemy.id, me.id);
			int weTook = captures(me.id, enemy.id);
			double ratio = power(me) / Math.max(1, power(enemy));
			long days = warDays(server, me.id, enemy.id);
			boolean losing = theyTook > weTook || ratio < 0.6;
			boolean tired = days >= 12 && weTook <= theyTook;

			if (me.faction != Faction.UNDEAD && (losing || tired) && !recently(me.id, enemy.id, LetterData.Type.PEACE, day, 3)
					&& !WarsDiplomacy.pendingLetter(me.id, enemy.id, LetterData.Type.PEACE)) {
				letter(server, me, enemy, LetterData.Type.PEACE, 0, peaceText(me, losing), day);
				return; // one letter a day
			}
		}

		// 2. the world around: neighbours and everyone else
		List<NationData> others = new ArrayList<>();

		for (NationData o : nations) {
			if (o != me && ServerNations.nation(o.id) != null) {
				others.add(o);
			}
		}

		others.sort((a, b) -> Double.compare(distance(me, a), distance(me, b)));
		boolean mayDeclare = day >= GRACE_DAYS && day - LAST_WAR.getOrDefault(me.id, -100L) >= WAR_COOLDOWN
				&& enemies.size() < (me.faction == Faction.UNDEAD ? 2 : 2);

		for (NationData other : others) {
			boolean neighbour = distance(me, other) < NEIGHBOUR;
			int o = WarsDiplomacy.opinion(me, other);
			double ratio = power(me) / Math.max(1, power(other));
			boolean war = WarsDiplomacy.atWar(me.id, other.id);
			boolean ally = me.allies.contains(other.id);

			if (war || ally && o > 0) {
				continue;
			}

			// war
			if (mayDeclare && neighbour && !ally && wantsWar(me, other, o, ratio)) {
				LAST_WAR.put(me.id, day);
				letter(server, me, other, LetterData.Type.WAR, 0, warText(me, other), day);
				return;
			}

			// threats: pay or else
			if (neighbour && (me.faction == Faction.ILLAGER || me.faction == Faction.PIGLIN) && ratio >= 1.6 && o < 20
					&& day >= GRACE_DAYS && !recently(me.id, other.id, LetterData.Type.TRIBUTE, day, 6) && RANDOM.nextInt(4) == 0
					&& !WarsDiplomacy.pendingLetter(me.id, other.id, LetterData.Type.TRIBUTE)) {
				int amount = (int) Math.max(15, Math.min(150, other.treasury / 4));
				letter(server, me, other, LetterData.Type.TRIBUTE, amount, tributeText(me, amount), day);
				return;
			}

			// friendship
			if (me.faction == Faction.UNDEAD || other.faction == Faction.UNDEAD) {
				continue;
			}

			if (o >= -10 && !WarsDiplomacy.trading(me.id, other.id) && !recently(me.id, other.id, LetterData.Type.TRADE, day, 5)
					&& !WarsDiplomacy.pendingLetter(me.id, other.id, LetterData.Type.TRADE) && RANDOM.nextInt(neighbour ? 3 : 8) == 0) {
				letter(server, me, other, LetterData.Type.TRADE, 0, tradeText(me), day);
				return;
			}

			if (o >= 45 && !ally && !recently(me.id, other.id, LetterData.Type.ALLIANCE, day, 6)
					&& !WarsDiplomacy.pendingLetter(me.id, other.id, LetterData.Type.ALLIANCE) && RANDOM.nextInt(3) == 0) {
				letter(server, me, other, LetterData.Type.ALLIANCE, 0, allianceText(me, other), day);
				return;
			}

			if (o >= 15 && o < 45 && me.treasury > 250 && me.faction == Faction.VILLAGER
					&& !recently(me.id, other.id, LetterData.Type.GIFT, day, 7) && RANDOM.nextInt(10) == 0) {
				letter(server, me, other, LetterData.Type.GIFT, 20, "A small token of our friendship.", day);
				return;
			}
		}
	}

	/** Does this ruler want to attack them? Every faction has its own temper. */
	private static boolean wantsWar(NationData me, NationData other, int opinion, double ratio) {
		boolean otherBusy = !WarsDiplomacy.enemiesOf(other).isEmpty();
		double r = RANDOM.nextDouble();

		return switch (me.faction) {
			// villagers fight only those they hate, or to take back villages from the dead
			case VILLAGER -> (opinion <= -50 && ratio >= 1.5 && r < 0.4) || (other.faction == Faction.UNDEAD && ratio >= 1.2 && r < 0.3);
			// illagers raid the living whenever they feel strong
			case ILLAGER -> other.faction != Faction.ILLAGER && opinion < 10 && ratio >= (otherBusy ? 0.9 : 1.15) && r < 0.35;
			// piglins fight for gold and pride
			case PIGLIN -> opinion < -20 && ratio >= 1.3 && r < 0.25;
			// the dead never rest
			case UNDEAD -> other.faction != Faction.UNDEAD && WarsDiplomacy.enemiesOf(me).isEmpty() && r < 0.5;
			default -> false;
		};
	}

	// ---------------------------------------------------------------- what they write

	private static String warText(NationData me, NationData other) {
		return switch (me.faction) {
			case ILLAGER -> "Your villages will burn and your emeralds are ours. The raid begins.";
			case PIGLIN -> "Your gold. Our gold now. Charge!";
			case UNDEAD -> "...the living... will join... us...";
			default -> "You leave us no choice. For our people, we take up arms against " + other.name + ".";
		};
	}

	private static String peaceText(NationData me, boolean losing) {
		return switch (me.faction) {
			case ILLAGER -> losing ? "Enough. We will stop the raids - for now." : "This war bores us. Let us end it.";
			case PIGLIN -> "Fighting costs gold. Peace saves gold. Peace?";
			default -> losing ? "Too many have died. We ask for peace." : "This war has gone on long enough. Let there be peace between us.";
		};
	}

	private static String tributeText(NationData me, int amount) {
		return switch (me.faction) {
			case PIGLIN -> amount + " emeralds. Shiny. Give them, or we come and take everything.";
			default -> "Pay us " + amount + " emeralds and your villages will be spared. Refuse, and you will regret it.";
		};
	}

	private static String tradeText(NationData me) {
		return switch (me.faction) {
			case ILLAGER -> "We have goods taken... found. Trade with us.";
			case PIGLIN -> "You have shiny things? We trade.";
			default -> "Our merchants would welcome trade with your people.";
		};
	}

	private static String allianceText(NationData me, NationData other) {
		return "Our peoples are close. Let " + me.name + " and " + other.name + " stand together against any enemy.";
	}

	// ---------------------------------------------------------------- once a day: raising armies

	private static void military(MinecraftServer server, NationData n, long day) {
		List<DivisionData> mine = WarsWar.divisionsOf(n.id);
		List<NationData> enemies = WarsDiplomacy.enemiesOf(n);
		int provinces = 0;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation)) {
				provinces++;
			}
		}

		// the dead rise for free
		if (n.faction == Faction.UNDEAD) {
			int want = Math.min(4, 1 + provinces / 2);

			if (mine.size() < want && day - LAST_RISE.getOrDefault(n.id, -100L) >= 2) {
				ProvinceData p = raiseSite(n, enemies, true);

				if (p != null) {
					n.treasury += DivisionData.Kind.INFANTRY.cost; // the raising is paid by dark magic
					WarsWar.raiseDivision(n, p, DivisionData.Kind.INFANTRY);
					LAST_RISE.put(n.id, day);
					WarsDiplomacy.news(server, "☠ The dead rise at " + p.name + ": " + n.name + " has a new horde.");
				}
			}

			return;
		}

		int want = enemies.isEmpty() ? (n.treasury > 220 ? 1 : 0) : Math.min(WarsWar.maxDivisions(n), 1 + enemies.size() * 2 + provinces / 2);
		int raised = 0;

		while (mine.size() + raised < want && raised < 2) {
			int index = mine.size() + raised;
			DivisionData.Kind kind = index % 4 == 3 ? DivisionData.Kind.SIEGE
					: index % 3 == 1 ? DivisionData.Kind.CAVALRY : index % 3 == 2 ? DivisionData.Kind.ARCHERS : DivisionData.Kind.INFANTRY;

			if (n.treasury < kind.cost + 25) {
				kind = DivisionData.Kind.INFANTRY;
			}

			ProvinceData p = raiseSite(n, enemies, false);

			if (p == null || n.treasury < kind.cost + 25 || WarsWar.raiseProblem(n, p, kind) != null) {
				break;
			}

			WarsWar.raiseDivision(n, p, kind);
			raised++;
		}

		// at peace and short of money: send soldiers home to save their pay
		if (enemies.isEmpty() && n.treasury < 15 && !mine.isEmpty()) {
			DivisionData weakest = mine.get(0);

			for (DivisionData d : mine) {
				if (d.strength < weakest.strength) {
					weakest = d;
				}
			}

			WarsWar.disbandQuietly(server, weakest);
		}
	}

	/** The province to raise an army at: the one closest to the enemy. */
	private static ProvinceData raiseSite(NationData n, List<NationData> enemies, boolean undead) {
		ProvinceData best = null;
		double bestScore = Double.MAX_VALUE;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (!n.id.equals(p.nation) || WarsWar.underSiege(p)) {
				continue;
			}

			if (!undead && (p.abandoned || (p.type == ProvinceData.Type.VILLAGE && p.population < 3))) {
				continue;
			}

			double score = Double.MAX_VALUE;

			for (NationData e : enemies) {
				ProvinceData ec = capital(e.id);

				if (ec != null && ec.dimension.equals(p.dimension)) {
					score = Math.min(score, Math.hypot(ec.x - p.x, ec.z - p.z));
				}
			}

			if (score == Double.MAX_VALUE) {
				score = p.capital ? 0 : 1; // no enemy around: the capital
			}

			if (score < bestScore) {
				bestScore = score;
				best = p;
			}
		}

		return best;
	}

	// ---------------------------------------------------------------- every 10 seconds: the generals

	private static boolean controlled(DivisionData d, long now) {
		return d.commander == null || now - d.orderTime > PLAYER_ORDER_TICKS;
	}

	private static void generals(MinecraftServer server) {
		long now = server.overworld().getGameTime();

		for (NationData n : new ArrayList<>(ServerNations.allNations())) {
			if (!n.aiRuled()) {
				continue;
			}

			List<DivisionData> mine = new ArrayList<>();

			for (DivisionData d : WarsWar.divisionsOf(n.id)) {
				if (controlled(d, now)) {
					mine.add(d);
				}
			}

			if (!mine.isEmpty()) {
				try {
					command(n, mine);
				} catch (Exception e) {
					MapNationsMod.LOGGER.warn("Map Nations WARS: generals of {} failed", n.name, e);
				}
			}
		}
	}

	private static boolean fresh(DivisionData d) {
		return d.morale >= 45 && d.strength >= d.kind.maxStrength * 0.45;
	}

	private static void command(NationData n, List<DivisionData> mine) {
		List<NationData> enemies = WarsDiplomacy.enemiesOf(n);
		Set<DivisionData> busy = new HashSet<>();

		for (DivisionData d : mine) {
			if (d.state == DivisionData.State.FIGHTING || d.state == DivisionData.State.RETREATING) {
				busy.add(d);
			}
		}

		// tired armies go home to rest
		for (DivisionData d : mine) {
			if (!busy.contains(d) && !fresh(d) && d.state != DivisionData.State.SIEGING && !atHome(d)) {
				WarsWar.goHome(d);
				busy.add(d);
			}
		}

		if (enemies.isEmpty()) {
			// at peace: armies outside our land come back
			for (DivisionData d : mine) {
				if (!busy.contains(d) && d.state == DivisionData.State.IDLE && !inOwnLand(d)) {
					WarsWar.goHome(d);
				}
			}

			return;
		}

		// 1. relieve our besieged provinces
		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation) && WarsWar.underSiege(p)) {
				DivisionData nearest = nearestFree(mine, busy, p.dimension, p.x, p.z);

				if (nearest != null) {
					WarsWar.order(nearest, p, p.x, p.z);
					busy.add(nearest);
				}
			}
		}

		// 2. intercept enemy armies in or near our land
		for (DivisionData e : WarsWar.divisions()) {
			if (!enemiesContain(enemies, e.nation)) {
				continue;
			}

			ProvinceData threatened = nearestOwnProvince(n, e.dimension, e.x, e.z);

			if (threatened == null || Math.hypot(threatened.x - e.x, threatened.z - e.z) > 300) {
				continue;
			}

			DivisionData nearest = nearestFree(mine, busy, e.dimension, e.x, e.z);

			if (nearest != null && nearest.strength * nearest.kind.attack >= e.strength * e.kind.attack * 0.6) {
				WarsWar.order(nearest, null, e.x, e.z);
				busy.add(nearest);
			}
		}

		// 3. attack: everyone else marches on one enemy province together
		for (DivisionData d : mine) {
			if (busy.contains(d) || !fresh(d)) {
				continue;
			}

			ProvinceData current = WarsWorld.province(d.target);

			if (current != null && current.nation != null && enemiesContain(enemies, current.nation)
					&& (d.state == DivisionData.State.MARCHING || d.state == DivisionData.State.SIEGING)) {
				continue; // already on its way
			}

			ProvinceData target = attackTarget(n, enemies, d);

			if (target != null) {
				WarsWar.order(d, target, target.x, target.z);
			}
		}
	}

	private static boolean enemiesContain(List<NationData> enemies, UUID id) {
		for (NationData e : enemies) {
			if (e.id.equals(id)) {
				return true;
			}
		}

		return false;
	}

	/** The enemy province to go for: close to our capital, weakly defended. The same for all our armies. */
	private static ProvinceData attackTarget(NationData n, List<NationData> enemies, DivisionData d) {
		ProvinceData home = capital(n.id);
		double fromX = home != null && home.dimension.equals(d.dimension) ? home.x : d.x;
		double fromZ = home != null && home.dimension.equals(d.dimension) ? home.z : d.z;
		ProvinceData best = null;
		double bestScore = Double.MAX_VALUE;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (p.nation == null || !p.dimension.equals(d.dimension) || !enemiesContain(enemies, p.nation)) {
				continue;
			}

			double score = Math.hypot(p.x - fromX, p.z - fromZ) + WarsWar.garrison(p) * 6 - WarsWar.siegeProgress(p) * 8;

			if (score < bestScore) {
				bestScore = score;
				best = p;
			}
		}

		return best;
	}

	private static DivisionData nearestFree(List<DivisionData> mine, Set<DivisionData> busy, String dim, double x, double z) {
		DivisionData best = null;
		double bestD = Double.MAX_VALUE;

		for (DivisionData d : mine) {
			if (busy.contains(d) || !d.dimension.equals(dim) || !fresh(d)) {
				continue;
			}

			double dist = Math.hypot(d.x - x, d.z - z);

			if (dist < bestD) {
				bestD = dist;
				best = d;
			}
		}

		return best;
	}

	private static ProvinceData nearestOwnProvince(NationData n, String dim, double x, double z) {
		ProvinceData best = null;
		double bestD = Double.MAX_VALUE;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation) && p.dimension.equals(dim)) {
				double dist = Math.hypot(p.x - x, p.z - z);

				if (dist < bestD) {
					bestD = dist;
					best = p;
				}
			}
		}

		return best;
	}

	private static boolean inOwnLand(DivisionData d) {
		NationData land = ServerNations.nationAt(d.dimension, ((int) Math.floor(d.x)) >> 4, ((int) Math.floor(d.z)) >> 4);
		return land != null && land.id.equals(d.nation);
	}

	private static boolean atHome(DivisionData d) {
		ProvinceData p = WarsWorld.province(d.target);
		return inOwnLand(d) && (d.state == DivisionData.State.IDLE || p != null && d.nation.equals(p.nation));
	}

	// ---------------------------------------------------------------- saving

	private static void load(MinecraftServer server) {
		LAST_WAR.clear();
		LAST_LETTER.clear();
		WAR_START.clear();
		CAPTURES.clear();
		LAST_RISE.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("mapnationswars_ai.json");

		if (!Files.exists(file)) {
			return;
		}

		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			readLongs(root.getAsJsonObject("lastWar"), LAST_WAR);
			readLongs(root.getAsJsonObject("lastRise"), LAST_RISE);
			JsonObject letters = root.getAsJsonObject("lastLetter");

			for (String k : letters.keySet()) {
				LAST_LETTER.put(k, letters.get(k).getAsLong());
			}

			JsonObject starts = root.getAsJsonObject("warStart");

			for (String k : starts.keySet()) {
				WAR_START.put(k, starts.get(k).getAsLong());
			}

			JsonObject captures = root.getAsJsonObject("captures");

			for (String k : captures.keySet()) {
				Map<UUID, Integer> m = new HashMap<>();
				JsonObject o = captures.getAsJsonObject(k);

				for (String n : o.keySet()) {
					m.put(UUID.fromString(n), o.get(n).getAsInt());
				}

				CAPTURES.put(k, m);
			}
		} catch (Exception e) {
			MapNationsMod.LOGGER.error("Could not read {}", file, e);
		}
	}

	private static void readLongs(JsonObject o, Map<UUID, Long> into) {
		if (o == null) {
			return;
		}

		for (String k : o.keySet()) {
			into.put(UUID.fromString(k), o.get(k).getAsLong());
		}
	}

	private static JsonObject writeLongs(Map<UUID, Long> map) {
		JsonObject o = new JsonObject();

		for (Map.Entry<UUID, Long> e : map.entrySet()) {
			o.addProperty(e.getKey().toString(), e.getValue());
		}

		return o;
	}

	private static void save() {
		if (file == null) {
			return;
		}

		JsonObject root = new JsonObject();
		root.add("lastWar", writeLongs(LAST_WAR));
		root.add("lastRise", writeLongs(LAST_RISE));
		JsonObject letters = new JsonObject();

		for (Map.Entry<String, Long> e : LAST_LETTER.entrySet()) {
			letters.addProperty(e.getKey(), e.getValue());
		}

		root.add("lastLetter", letters);
		JsonObject starts = new JsonObject();

		for (Map.Entry<String, Long> e : WAR_START.entrySet()) {
			starts.addProperty(e.getKey(), e.getValue());
		}

		root.add("warStart", starts);
		JsonObject captures = new JsonObject();

		for (Map.Entry<String, Map<UUID, Integer>> e : CAPTURES.entrySet()) {
			JsonObject o = new JsonObject();

			for (Map.Entry<UUID, Integer> c : e.getValue().entrySet()) {
				o.addProperty(c.getKey().toString(), c.getValue());
			}

			captures.add(e.getKey(), o);
		}

		root.add("captures", captures);

		try {
			Path tmp = file.resolveSibling("mapnationswars_ai.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			MapNationsMod.LOGGER.error("Could not save {}", file, e);
		}
	}
}
