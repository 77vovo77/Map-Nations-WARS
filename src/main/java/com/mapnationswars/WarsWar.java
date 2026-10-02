package com.mapnationswars;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import com.mapnationswars.nation.DivisionData;
import com.mapnationswars.nation.Faction;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.PortalSite;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.nation.Ranks;
import com.mapnationswars.network.ArmyActionPayload;
import com.mapnationswars.network.WarSyncPayload;

/**
 * Map Nations WARS stage 5: war.
 * Nations raise divisions at their provinces, march them across the map, fight battles against enemy divisions
 * and besiege enemy provinces until they fall. It all happens on the map - but when a player is near a battle or a siege,
 * real soldiers of the enemy appear there, and every one the player kills weakens that army.
 * Saved in the world folder as mapnationswars_war.json.
 */
public final class WarsWar {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Divisions closer than this (blocks) fight. */
	static final double ENGAGE = 40;
	/** A division this close to a province's centre is at the province. */
	static final double AT_PROVINCE = 48;
	/** Players this close to a battle help their side / meet the enemy's soldiers. */
	private static final double HELP_RANGE = 96;
	private static final String SOLDIER_PREFIX = "⚔ ";

	private static final Map<UUID, DivisionData> DIVISIONS = new LinkedHashMap<>();
	private static final Map<UUID, Siege> SIEGES = new LinkedHashMap<>();
	/** How many divisions each nation has raised (for names like "3rd Militia"). */
	private static final Map<UUID, Integer> RAISED = new HashMap<>();
	private static final Set<String> BATTLE_KEYS = new HashSet<>();
	private static List<WarSyncPayload.Battle> battles = new ArrayList<>();
	private static final Random RANDOM = new Random();
	private static Path file;
	private static int ticks = 0;
	private static boolean sentEmpty = true;

	/** A province under siege. */
	static final class Siege {
		UUID attacker;
		double progress;
		/** Defenders killed by players: makes the garrison weaker. */
		double garrisonLoss;
		boolean active;

		Siege(UUID attacker) {
			this.attacker = attacker;
		}
	}

	/** A real mob standing in for an army's soldiers near a player. */

	private WarsWar() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(WarsWar::load);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save();
			DIVISIONS.clear();
			SIEGES.clear();
			RAISED.clear();
			BATTLE_KEYS.clear();
			battles = new ArrayList<>();
			file = null;
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (ServerPlayNetworking.canSend(handler.player, WarSyncPayload.TYPE)) {
				sender.sendPacket(syncPayload());
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 20 == 0 && WarsWorld.isGenerated()) {
				step(server);
			}
		});

		// soldiers left over from before a restart are not part of any army any more
		// (removed one tick later: removing entities while the game is loading them is not safe)
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (!LIVE.contains(entity.getUUID()) && entity.hasCustomName() && entity.getCustomName() != null
					&& entity.getCustomName().getString().startsWith(SOLDIER_PREFIX)) {
				LEFTOVERS.add(entity);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!LEFTOVERS.isEmpty()) {
				List<Entity> list = new ArrayList<>(LEFTOVERS);
				LEFTOVERS.clear();

				for (Entity e : list) {
					if (!LIVE.contains(e.getUUID()) && e.isAlive()) {
						e.discard();
					}
				}
			}
		});


		ServerPlayNetworking.registerGlobalReceiver(ArmyActionPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.level().getServer();

			if (server != null) {
				server.execute(() -> handle(server, player, payload));
			}
		});
	}

	// ---------------------------------------------------------------- queries

	static java.util.Collection<DivisionData> divisions() {
		return DIVISIONS.values();
	}

	static List<DivisionData> divisionsOf(UUID nation) {
		List<DivisionData> list = new ArrayList<>();

		for (DivisionData d : DIVISIONS.values()) {
			if (d.nation.equals(nation)) {
				list.add(d);
			}
		}

		return list;
	}

	/** Provinces being besieged right now (and kept up by an army). */
	static List<ProvinceData> activeSieges() {
		List<ProvinceData> list = new ArrayList<>();

		for (Map.Entry<UUID, Siege> e : SIEGES.entrySet()) {
			ProvinceData p = WarsWorld.province(e.getKey());

			if (p != null && e.getValue().active) {
				list.add(p);
			}
		}

		return list;
	}

	static UUID besieger(ProvinceData p) {
		Siege s = SIEGES.get(p.id);
		return s == null ? null : s.attacker;
	}

	/** A defender of a besieged province was killed in the world. */
	static void garrisonKilled(UUID province) {
		Siege siege = SIEGES.get(province);

		if (siege != null) {
			siege.garrisonLoss += 4;
			siege.progress = Math.min(99, siege.progress + 2);
		}
	}

	static DivisionData division(UUID id) {
		return id == null ? null : DIVISIONS.get(id);
	}

	static boolean underSiege(ProvinceData p) {
		Siege s = SIEGES.get(p.id);
		return s != null && s.progress > 0;
	}

	static double siegeProgress(ProvinceData p) {
		Siege s = SIEGES.get(p.id);
		return s == null ? 0 : s.progress;
	}

	/** How many divisions a nation may have. */
	static int maxDivisions(NationData n) {
		int provinces = 0;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation)) {
				provinces++;
			}
		}

		return 2 + provinces;
	}

	/** Defenders of a province without an army: villagers with pitchforks, or the illagers / piglins living there. */
	static double garrison(ProvinceData p) {
		double g = switch (p.type) {
			case VILLAGE -> 10 + p.population * 2 + p.houses;
			case OUTPOST -> 30 + p.population * 3;
			case MANSION, BASTION -> 60 + p.population * 3;
		};

		if (p.capital) {
			g *= 1.5;
		}

		Siege s = SIEGES.get(p.id);
		return Math.max(5, g - (s != null ? s.garrisonLoss : 0));
	}

	/** Military strength: soldiers in the field plus the garrisons. */
	static int armyStrength(UUID nation) {
		double s = 0;

		for (DivisionData d : DIVISIONS.values()) {
			if (d.nation.equals(nation)) {
				s += d.strength * (d.kind.attack + d.kind.defence) / 2;
			}
		}

		for (ProvinceData p : WarsWorld.provinces()) {
			if (nation.equals(p.nation)) {
				s += garrison(p) * 0.2;
			}
		}

		return (int) s;
	}

	static boolean canCommand(NationData n, UUID player) {
		return n != null && (n.leader.equals(player) || (n.isMember(player) && n.rankOf(player) >= Ranks.OFFICER));
	}

	static boolean canRaise(NationData n, UUID player) {
		return n != null && (n.leader.equals(player) || (n.isMember(player) && n.rankOf(player) >= Ranks.MINISTER));
	}

	private static boolean friendly(UUID a, UUID b) {
		if (a.equals(b)) {
			return true;
		}

		NationData na = ServerNations.nation(a);
		return na != null && na.allies.contains(b);
	}

	static boolean hostile(UUID a, UUID b) {
		return !a.equals(b) && WarsDiplomacy.atWar(a, b);
	}

	private static String place(double x, double z) {
		ProvinceData best = null;
		double bestD = Double.MAX_VALUE;

		for (ProvinceData p : WarsWorld.provinces()) {
			double d = Math.hypot(p.x - x, p.z - z);

			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}

		return best != null && bestD < 600 ? "near " + best.name : "at " + (int) x + ", " + (int) z;
	}

	// ---------------------------------------------------------------- orders

	private static void handle(MinecraftServer server, ServerPlayer player, ArmyActionPayload a) {
		UUID me = player.getUUID();

		if (a.action() == ArmyActionPayload.RAISE) {
			ProvinceData p;

			try {
				p = WarsWorld.province(UUID.fromString(a.id()));
			} catch (IllegalArgumentException e) {
				p = null;
			}

			if (p != null) {
				raise(server, player, p, DivisionData.Kind.byName(a.argument()));
			}

			return;
		}

		DivisionData d;

		try {
			d = DIVISIONS.get(UUID.fromString(a.id()));
		} catch (IllegalArgumentException e) {
			d = null;
		}

		if (d == null) {
			return;
		}

		NationData n = ServerNations.nation(d.nation);

		if (!canCommand(n, me)) {
			ServerNations.status(player, "Only Officers, Ministers and the leader of " + (n != null ? n.name : "its nation") + " command its armies.", false);
			return;
		}

		switch (a.action()) {
			case ArmyActionPayload.MOVE -> {
				if ("portal".equals(a.argument())) {
					PortalSite site = WarsPortals.nearestUsable(d.dimension, d.x, d.z);

					if (site == null) {
						ServerNations.status(player, "No portal to march through: build one, or wait for a ruined portal to tear open.", false);
						return;
					}

					order(d, null, site.xIn(d.dimension), site.zIn(d.dimension));
					d.portal = site.id;
					d.commander = me;
					d.orderTime = server.overworld().getGameTime();
					ServerNations.status(player, d.name + " marches to the " + site.name + " to go through it.", true);
					break;
				}

				d.portal = null;
				UUID target = null;

				try {
					target = a.argument().isEmpty() ? null : UUID.fromString(a.argument());
				} catch (IllegalArgumentException ignored) {
				}

				ProvinceData tp = target != null ? WarsWorld.province(target) : null;

				if (tp != null && !tp.dimension.equals(d.dimension)) {
					ServerNations.status(player, "Armies can't march into another dimension (yet).", false);
					return;
				}

				order(d, tp, tp != null ? tp.x : a.x(), tp != null ? tp.z : a.z());
				d.commander = me;
				d.orderTime = server.overworld().getGameTime();
				String where = tp != null ? tp.name : (a.x() + ", " + a.z());
				boolean attack = tp != null && tp.nation != null && hostile(d.nation, tp.nation);
				ServerNations.status(player, d.name + (attack ? " marches to besiege " : " marches to ") + where + ".", true);
			}
			case ArmyActionPayload.HALT -> {
				d.portal = null;
				d.state = DivisionData.State.IDLE;
				d.goalX = d.x;
				d.goalZ = d.z;
				d.target = null;
				d.commander = me;
				d.orderTime = server.overworld().getGameTime();
				ServerNations.status(player, d.name + " stops and holds its ground.", true);
			}
			case ArmyActionPayload.HIRE -> {
				if (!canRaise(n, me)) {
					ServerNations.status(player, "Only Ministers and the leader can hire soldiers.", false);
					return;
				}

				String problem = hire(n, d, Math.max(1, a.x()));

				if (problem != null) {
					ServerNations.status(player, problem, false);
					return;
				}

				ServerNations.status(player, d.name + " now has " + d.soldiers() + " soldiers.", true);
				ServerNations.saveNow(server);
				ServerNations.broadcast(server);
			}
			case ArmyActionPayload.SPLIT -> {
				if (d.state == DivisionData.State.FIGHTING || d.strength < 4) {
					ServerNations.status(player, d.strength < 4 ? "Too few soldiers to split." : "Not in the middle of a battle!", false);
					return;
				}

				DivisionData half = split(n, d);
				ServerNations.status(player, d.name + " split: " + half.name + " (" + half.soldiers() + " soldiers) is ready for orders.", true);
			}
			case ArmyActionPayload.MERGE -> {
				DivisionData other = null;

				for (DivisionData o : DIVISIONS.values()) {
					if (o != d && o.nation.equals(d.nation) && o.kind == d.kind && o.dimension.equals(d.dimension)
							&& Math.hypot(o.x - d.x, o.z - d.z) < 32 && (other == null
							|| Math.hypot(o.x - d.x, o.z - d.z) < Math.hypot(other.x - d.x, other.z - d.z))) {
						other = o;
					}
				}

				if (other == null) {
					ServerNations.status(player, "No other " + d.kind.displayName.toLowerCase() + " of yours within 32 blocks to merge with.", false);
					return;
				}

				if (d.state == DivisionData.State.FIGHTING || other.state == DivisionData.State.FIGHTING) {
					ServerNations.status(player, "Not in the middle of a battle!", false);
					return;
				}

				merge(server, d, other);
				ServerNations.status(player, other.name + " joined " + d.name + ": " + d.soldiers() + " soldiers.", true);
			}
			case ArmyActionPayload.DISBAND -> {
				if (!canRaise(n, me)) {
					ServerNations.status(player, "Only Ministers and the leader can disband an army.", false);
					return;
				}

				removeDivision(server, d);
				ProvinceData home = WarsWorld.province(d.home);

				if (home != null && d.nation.equals(home.nation)) {
					home.happiness = Math.min(100, home.happiness + 3); // the soldiers come home
				}

				ServerNations.status(player, d.name + " was disbanded. The soldiers go home.", true);
			}
			default -> {
			}
		}

		save();
		broadcast(server);
	}

	/** Sends a division somewhere (used by players and by the game's own generals). */
	static void order(DivisionData d, ProvinceData target, double x, double z) {
		if (d.state == DivisionData.State.FIGHTING) {
			d.morale = Math.max(0, d.morale - 10); // pulling out of a battle costs morale
		}

		d.portal = null;
		d.target = target != null ? target.id : null;
		d.goalX = x;
		d.goalZ = z;
		d.state = Math.hypot(d.x - x, d.z - z) < 2 ? DivisionData.State.IDLE : DivisionData.State.MARCHING;
	}

	static void raise(MinecraftServer server, ServerPlayer player, ProvinceData p, DivisionData.Kind kind) {
		NationData n = ServerNations.nation(p.nation);

		if (!canRaise(n, player.getUUID())) {
			ServerNations.status(player, "Only Ministers and the leader of " + (n != null ? n.name : "this province's nation") + " can raise armies.", false);
			return;
		}

		String problem = raiseProblem(n, p, kind);

		if (problem != null) {
			ServerNations.status(player, problem, false);
			return;
		}

		DivisionData d = raiseDivision(n, p, kind);
		ServerNations.status(player, d.name + " was raised at " + p.name + " with " + d.soldiers() + " soldiers for " + kind.cost
				+ " emeralds. Hire more in the War tab (" + kind.hirePrice + " each, up to " + kind.maxStrength + ").", true);
		WarsWorld.saveNow();
		ServerNations.saveNow(server);
		save();
		ServerNations.broadcast(server);
		WarsWorld.broadcast(server);
		broadcast(server);
	}

	static String raiseProblem(NationData n, ProvinceData p, DivisionData.Kind kind) {
		if (n == null) {
			return p.name + " belongs to no nation.";
		}

		if (p.abandoned) {
			return "Nobody lives in " + p.name + " to be a soldier.";
		}

		if (underSiege(p)) {
			return p.name + " is under siege!";
		}

		if (p.type == ProvinceData.Type.VILLAGE && p.population < 3) {
			return p.name + " has too few villagers to give soldiers (3 needed).";
		}

		if (divisionsOf(n.id).size() >= maxDivisions(n)) {
			return n.name + " can't keep more than " + maxDivisions(n) + " armies (2 + one per province).";
		}

		if (n.treasury < kind.cost) {
			return "The treasury has " + n.treasury + " emeralds; " + kind.displayName.toLowerCase() + " costs " + kind.cost + ".";
		}

		return null;
	}

	/** Raises a division (the checks were done before). */
	static DivisionData raiseDivision(NationData n, ProvinceData p, DivisionData.Kind kind) {
		n.treasury -= kind.cost;
		p.happiness = Math.max(0, p.happiness - 4); // nobody likes being called up
		int number = RAISED.merge(n.id, 1, Integer::sum);
		DivisionData d = new DivisionData(UUID.randomUUID());
		d.nation = n.id;
		d.kind = kind;
		d.name = ordinal(number) + " " + kind.unitName(n.faction);
		d.strength = kind.startStrength();
		d.morale = 75;
		d.dimension = p.dimension;
		d.x = p.x + RANDOM.nextInt(17) - 8;
		d.z = p.z + RANDOM.nextInt(17) - 8;
		d.goalX = d.x;
		d.goalZ = d.z;
		d.home = p.id;
		DIVISIONS.put(d.id, d);
		return d;
	}

	/** An army that appears out of nowhere (invaders from a portal): no province raised it. */
	static DivisionData spawnDivision(NationData n, DivisionData.Kind kind, String dimension, double x, double z, String prefix) {
		int number = RAISED.merge(n.id, 1, Integer::sum);
		DivisionData d = new DivisionData(UUID.randomUUID());
		d.nation = n.id;
		d.kind = kind;
		d.name = ordinal(number) + " " + kind.unitName(n.faction) + (prefix.isEmpty() ? "" : " (" + prefix + ")");
		d.strength = kind.maxStrength;
		d.morale = 80;
		d.dimension = dimension;
		d.x = x;
		d.z = z;
		d.goalX = x;
		d.goalZ = z;
		DIVISIONS.put(d.id, d);
		return d;
	}

	private static String ordinal(int n) {
		int mod100 = n % 100;
		String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : switch (n % 10) {
			case 1 -> "st";
			case 2 -> "nd";
			case 3 -> "rd";
			default -> "th";
		};
		return n + suffix;
	}

	private static void removeDivision(MinecraftServer server, DivisionData d) {
		DIVISIONS.remove(d.id);
		WarsTroops.forget(server, d.id);
	}

	// ---------------------------------------------------------------- war and peace

	/**
	 * Hires soldiers into a division: they cost emeralds from the treasury and must come from your own land
	 * (the division must be in your land). Returns why it can't, or null.
	 */
	static String hire(NationData n, DivisionData d, int count) {
		int room = d.kind.maxStrength - d.soldiers();

		if (room <= 0) {
			return d.name + " is full (" + d.kind.maxStrength + " soldiers).";
		}

		NationData land = ServerNations.nationAt(d.dimension, ((int) Math.floor(d.x)) >> 4, ((int) Math.floor(d.z)) >> 4);

		if (land == null || !land.id.equals(n.id)) {
			return "Soldiers are hired in your own land. Bring " + d.name + " home first.";
		}

		int want = Math.min(room, count);
		int afford = (int) Math.min(want, n.treasury / d.kind.hirePrice);

		if (afford <= 0) {
			return "The treasury can't pay: " + d.kind.hirePrice + " emeralds a soldier (it has " + n.treasury + ").";
		}

		n.treasury -= (long) afford * d.kind.hirePrice;
		n.book("Hired soldiers", -afford * d.kind.hirePrice);
		d.strength = Math.min(d.kind.maxStrength, d.strength + afford);
		d.morale = Math.max(d.morale, 60);

		// the recruits come from the nearest village
		ProvinceData from = null;
		double best = Double.MAX_VALUE;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation) && p.dimension.equals(d.dimension)) {
				double dist = Math.hypot(p.x - d.x, p.z - d.z);

				if (dist < best) {
					best = dist;
					from = p;
				}
			}
		}

		if (from != null) {
			from.happiness = Math.max(0, from.happiness - Math.max(1, afford / 4));
		}

		save();
		return null;
	}

	/** Splits a division in two halves; returns the new one. */
	static DivisionData split(NationData n, DivisionData d) {
		DivisionData half = spawnDivision(n, d.kind, d.dimension, d.x + 4, d.z + 4, "");
		half.strength = Math.floor(d.strength / 2);
		d.strength -= half.strength;
		half.morale = d.morale;
		half.home = d.home;
		save();
		return half;
	}

	/** Puts the soldiers of "other" into "into" (what doesn't fit stays in other). */
	static void merge(MinecraftServer server, DivisionData into, DivisionData other) {
		double moved = Math.min(other.strength, into.kind.maxStrength - into.strength);
		into.morale = (into.morale * into.strength + other.morale * moved) / Math.max(1, into.strength + moved);
		into.strength += moved;
		other.strength -= moved;

		if (other.strength < 0.5) {
			removeDivision(server, other);
		}

		save();
	}

	/** Removes an army without a word (the game's own rulers saving money). */
	static void disbandQuietly(MinecraftServer server, DivisionData d) {
		removeDivision(server, d);
	}

	static void onWarStarted(MinecraftServer server, NationData from, NationData to) {
		WarsAI.onWarStarted(server, from, to);
		WarsDiplomacy.news(server, "⚔ " + from.name + " declared war on " + to.name + "!");
	}

	static void onPeace(MinecraftServer server, NationData a, NationData b) {
		// sieges between them end; armies in each other's land go home
		for (Map.Entry<UUID, Siege> e : SIEGES.entrySet()) {
			ProvinceData p = WarsWorld.province(e.getKey());

			if (p != null && p.nation != null && (e.getValue().attacker.equals(a.id) && p.nation.equals(b.id)
					|| e.getValue().attacker.equals(b.id) && p.nation.equals(a.id))) {
				e.getValue().progress = 0;
			}
		}

		for (DivisionData d : DIVISIONS.values()) {
			if (d.nation.equals(a.id) || d.nation.equals(b.id)) {
				ProvinceData t = WarsWorld.province(d.target);

				if (t != null && t.nation != null && !t.nation.equals(d.nation)) {
					goHome(d);
				}
			}
		}

		WarsDiplomacy.news(server, "☘ " + a.name + " and " + b.name + " made peace.");
	}

	// ---------------------------------------------------------------- once a second

	private static void step(MinecraftServer server) {
		if (DIVISIONS.isEmpty() && SIEGES.isEmpty()) {
			if (!sentEmpty) {
				battles = new ArrayList<>();
				broadcast(server);
				sentEmpty = true;
			}

			return;
		}

		sentEmpty = false;

		// armies of nations that no longer exist go away
		DIVISIONS.values().removeIf(d -> ServerNations.nation(d.nation) == null);

		move(server);
		Map<DivisionData, DivisionData> opponents = findBattles(server);
		fight(server, opponents);
		sieges(server);
		recover();
		WarsTroops.tick(server);

		if (ticks % 600 == 0) {
			save();
		}

		broadcast(server);
	}

	private static void move(MinecraftServer server) {
		for (DivisionData d : DIVISIONS.values()) {
			if (d.state != DivisionData.State.MARCHING && d.state != DivisionData.State.RETREATING) {
				continue;
			}

			double speed = d.kind.speed * (d.state == DivisionData.State.RETREATING ? 1.3 : 1.0);
			double dx = d.goalX - d.x;
			double dz = d.goalZ - d.z;
			double dist = Math.hypot(dx, dz);

			if (dist <= speed) {
				d.x = d.goalX;
				d.z = d.goalZ;
				arrive(server, d);
			} else {
				d.x += dx / dist * speed;
				d.z += dz / dist * speed;
			}
		}
	}

	/** A division reached its goal: besiege an enemy province, or hold. */
	private static void arrive(MinecraftServer server, DivisionData d) {
		if (d.portal != null) {
			WarsPortals.cross(server, d);
			return;
		}

		ProvinceData t = WarsWorld.province(d.target);

		if (d.state != DivisionData.State.RETREATING && t != null && t.nation != null && hostile(d.nation, t.nation)) {
			d.state = DivisionData.State.SIEGING;
		} else {
			d.state = DivisionData.State.IDLE;
		}
	}

	/** Pairs every division with the nearest enemy division in reach. */
	private static Map<DivisionData, DivisionData> findBattles(MinecraftServer server) {
		Map<DivisionData, DivisionData> opponents = new HashMap<>();
		List<DivisionData> all = new ArrayList<>(DIVISIONS.values());
		Set<String> keys = new HashSet<>();
		List<WarSyncPayload.Battle> found = new ArrayList<>();

		for (DivisionData d : all) {
			if (d.state == DivisionData.State.RETREATING) {
				continue;
			}

			DivisionData best = null;
			double bestD = ENGAGE;

			for (DivisionData e : all) {
				if (e == d || !e.dimension.equals(d.dimension) || !hostile(d.nation, e.nation)) {
					continue;
				}

				double dist = Math.hypot(e.x - d.x, e.z - d.z);

				// a retreating enemy can still be caught, but it doesn't fight back
				if (dist < bestD) {
					bestD = dist;
					best = e;
				}
			}

			if (best != null) {
				opponents.put(d, best);

				if (d.state != DivisionData.State.FIGHTING) {
					d.state = DivisionData.State.FIGHTING;
				}

				String key = d.nation.compareTo(best.nation) < 0 ? d.nation + "|" + best.nation + "|" + (int) (d.x / 128) + "|" + (int) (d.z / 128)
						: best.nation + "|" + d.nation + "|" + (int) (best.x / 128) + "|" + (int) (best.z / 128);

				if (keys.add(key)) {
					found.add(new WarSyncPayload.Battle(d.dimension, (float) ((d.x + best.x) / 2), (float) ((d.z + best.z) / 2), d.nation, best.nation));

					if (!BATTLE_KEYS.contains(key)) {
						announceBattle(server, d, best);
					}
				}
			} else if (d.state == DivisionData.State.FIGHTING) {
				// the enemy is gone: carry on
				if (Math.hypot(d.goalX - d.x, d.goalZ - d.z) > 2) {
					d.state = DivisionData.State.MARCHING;
				} else {
					arrive(server, d);
				}
			}
		}

		BATTLE_KEYS.clear();
		BATTLE_KEYS.addAll(keys);
		battles = found;
		return opponents;
	}

	private static void announceBattle(MinecraftServer server, DivisionData a, DivisionData b) {
		NationData na = ServerNations.nation(a.nation);
		NationData nb = ServerNations.nation(b.nation);

		if (na == null || nb == null) {
			return;
		}

		String where = place((a.x + b.x) / 2, (a.z + b.z) / 2);
		String text = "⚔ Battle " + where + ": " + a.name + " (" + na.name + ") against " + b.name + " (" + nb.name + ")!";

		for (ServerPlayer p : PlayerLookup.all(server)) {
			boolean involved = na.isMember(p.getUUID()) || nb.isMember(p.getUUID());
			boolean near = p.level().dimension().identifier().toString().equals(a.dimension) && Math.hypot(p.getX() - a.x, p.getZ() - a.z) < 400;

			if (involved || near) {
				p.sendSystemMessage(Component.literal(text + " (" + (int) a.x + ", " + (int) a.z + ")").withColor(0xFF8A65));
			}
		}
	}

	private static void fight(MinecraftServer server, Map<DivisionData, DivisionData> opponents) {
		Map<DivisionData, Double> damage = new HashMap<>();

		for (Map.Entry<DivisionData, DivisionData> e : opponents.entrySet()) {
			DivisionData att = e.getKey();
			DivisionData def = e.getValue();
			double moraleFactor = 0.5 + att.morale / 200.0;
			double dmg = att.strength * att.kind.attack * moraleFactor * 0.012 * (0.75 + RANDOM.nextDouble() * 0.5);
			dmg *= 1 + 0.3 * helpers(server, att);
			dmg /= def.kind.defence;

			// defending your own land is easier
			NationData land = ServerNations.nationAt(def.dimension, ((int) Math.floor(def.x)) >> 4, ((int) Math.floor(def.z)) >> 4);

			if (land != null && friendly(def.nation, land.id)) {
				dmg /= 1.2;
			}

			damage.merge(def, dmg, Double::sum);
		}

		for (Map.Entry<DivisionData, Double> e : damage.entrySet()) {
			DivisionData d = e.getKey();
			d.strength -= e.getValue();
			d.morale -= e.getValue() / d.kind.maxStrength * 120 + 0.3; // losing a share of the men breaks spirits
		}

		for (DivisionData d : new ArrayList<>(DIVISIONS.values())) {
			if (d.strength < 0.5) {
				NationData n = ServerNations.nation(d.nation);
				WarsDiplomacy.news(server, "☠ " + d.name + " of " + (n != null ? n.name : "?") + " was destroyed " + place(d.x, d.z) + ".");
				removeDivision(server, d);
			} else if (d.morale < 15 && d.state != DivisionData.State.RETREATING) {
				if (!goHome(d)) {
					NationData n = ServerNations.nation(d.nation);
					WarsDiplomacy.news(server, "⚑ " + d.name + " of " + (n != null ? n.name : "?") + " has nowhere to run and surrendered.");
					removeDivision(server, d);
				} else {
					d.state = DivisionData.State.RETREATING;
				}
			}
		}
	}

	/** Players of the division's side (or its allies) near it, up to 3. */
	private static int helpers(MinecraftServer server, DivisionData d) {
		int count = 0;

		for (ServerPlayer p : PlayerLookup.all(server)) {
			NationData pn = ServerNations.nationOf(p.getUUID());

			if (pn != null && friendly(d.nation, pn.id) && p.level().dimension().identifier().toString().equals(d.dimension)
					&& Math.hypot(p.getX() - d.x, p.getZ() - d.z) < HELP_RANGE) {
				count++;
			}
		}

		return Math.min(3, count);
	}

	/** Sends a division back to the nearest province of its own nation. False if it has none. */
	static boolean goHome(DivisionData d) {
		ProvinceData best = null;
		double bestD = Double.MAX_VALUE;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (d.nation.equals(p.nation) && p.dimension.equals(d.dimension)) {
				double dist = Math.hypot(p.x - d.x, p.z - d.z);

				if (dist < bestD) {
					bestD = dist;
					best = p;
				}
			}
		}

		if (best == null) {
			return false;
		}

		d.portal = null;
		d.target = best.id;
		d.goalX = best.x + RANDOM.nextInt(17) - 8;
		d.goalZ = best.z + RANDOM.nextInt(17) - 8;
		d.state = DivisionData.State.RETREATING;
		return true;
	}

	private static void sieges(MinecraftServer server) {
		for (Siege s : SIEGES.values()) {
			s.active = false;
		}

		Map<UUID, Map<UUID, Double>> power = new HashMap<>(); // province -> nation -> siege power

		for (DivisionData d : DIVISIONS.values()) {
			if (d.state != DivisionData.State.SIEGING) {
				continue;
			}

			ProvinceData p = WarsWorld.province(d.target);

			if (p == null || p.nation == null || !hostile(d.nation, p.nation)) {
				d.state = DivisionData.State.IDLE; // peace, or it changed hands
				continue;
			}

			double pw = d.strength * d.kind.siege * (0.5 + d.morale / 200.0) * (1 + 0.3 * helpers(server, d));
			power.computeIfAbsent(p.id, k -> new HashMap<>()).merge(d.nation, pw, Double::sum);
			// the defenders shoot back
			d.strength -= garrison(p) * 0.0006;
			d.morale -= 0.02;
		}

		for (Map.Entry<UUID, Map<UUID, Double>> e : power.entrySet()) {
			ProvinceData p = WarsWorld.province(e.getKey());
			UUID leader = null;
			double total = 0;
			double most = -1;

			for (Map.Entry<UUID, Double> n : e.getValue().entrySet()) {
				total += n.getValue();

				if (n.getValue() > most) {
					most = n.getValue();
					leader = n.getKey();
				}
			}

			Siege s = SIEGES.get(p.id);

			if (s == null) {
				s = new Siege(leader);
				SIEGES.put(p.id, s);
				announceSiege(server, p, leader);
			}

			s.attacker = leader;
			s.active = true;
			s.progress += total / (garrison(p) * 1.5);

			if (s.progress >= 100) {
				capture(server, p, leader);
			}
		}

		// sieges nobody keeps up slowly fall apart; the defenders recover
		for (Iterator<Map.Entry<UUID, Siege>> it = SIEGES.entrySet().iterator(); it.hasNext(); ) {
			Siege s = it.next().getValue();

			if (!s.active) {
				s.progress -= 1;
				s.garrisonLoss = Math.max(0, s.garrisonLoss - 0.2);

				if (s.progress <= 0) {
					it.remove();
				}
			}
		}
	}

	private static void announceSiege(MinecraftServer server, ProvinceData p, UUID attacker) {
		NationData a = ServerNations.nation(attacker);
		NationData d = ServerNations.nation(p.nation);

		if (a == null || d == null) {
			return;
		}

		for (NationData.Member m : d.members) {
			ServerNations.notifyPlayer(server, m.id(), "⚠ " + a.name + " is besieging " + p.name + " (" + p.x + ", " + p.z + ")! Go and defend it.");
		}
	}

	/** A province falls to the besiegers. */
	static void capture(MinecraftServer server, ProvinceData p, UUID winner) {
		NationData oldNation = ServerNations.nation(p.nation);
		NationData newNation = ServerNations.nation(winner);
		SIEGES.remove(p.id);

		if (newNation == null) {
			return;
		}

		String oldName = oldNation != null ? oldNation.name : "nobody";
		p.happiness = Math.max(5, p.happiness - 30);
		WarsAI.onCapture(newNation, p.nation);
		p.conqueredDay = server.overworld().getGameTime() / WarsEconomy.DAY_TICKS;
		p.unrest = Math.max(p.unrest, 35); // nobody likes new masters

		if (p.type == ProvinceData.Type.VILLAGE) {
			if (newNation.faction == Faction.UNDEAD && !p.abandoned) {
				// the dead leave nobody alive
				p.abandoned = true;
				p.population = 0;
				p.mayorName = "";
			} else if (newNation.faction != Faction.UNDEAD && p.abandoned) {
				// taken back from the dead: refugees come home
				p.abandoned = false;
				p.population = Math.max(2, p.population);
				p.mayorName = Names.person(RANDOM);
				p.happiness = 40;
			}
		}

		p.funds = p.funds / 2; // plundered
		newNation.treasury += p.funds;
		boolean fell = WarsWorld.transferProvince(p, winner);

		if (oldNation != null) {
			WarsDiplomacy.changeOpinion(oldNation.id, newNation.id, -15);
		}

		WarsDiplomacy.news(server, "🏳 " + newNation.name + " captured " + p.name + " from " + oldName + "!");

		if (fell && oldNation != null) {
			WarsDiplomacy.news(server, "☠ " + oldNation.name + " has fallen. Its last province was taken by " + newNation.name + ".");
		}

		// the besiegers rest in their new province
		for (DivisionData d : DIVISIONS.values()) {
			if (p.id.equals(d.target) && d.state == DivisionData.State.SIEGING) {
				d.state = DivisionData.State.IDLE;
			}
		}

		// players who helped take it earn merit
		for (ServerPlayer pl : PlayerLookup.all(server)) {
			if (newNation.isMember(pl.getUUID()) && pl.level().dimension().identifier().toString().equals(p.dimension)
					&& Math.hypot(pl.getX() - p.x, pl.getZ() - p.z) < 160) {
				WarsPolitics.addMerit(server, newNation, pl.getUUID(), 20);
			}
		}

		WarsTroops.forget(server, p.id);
		WarsWorld.saveNow();
		ServerNations.saveNow(server);
		save();
		ServerNations.broadcast(server);
		WarsWorld.broadcast(server);
	}

	/** Resting divisions in friendly land get their spirit back. */
	private static void recover() {
		for (DivisionData d : DIVISIONS.values()) {
			if (d.state == DivisionData.State.IDLE || d.state == DivisionData.State.RETREATING) {
				NationData land = ServerNations.nationAt(d.dimension, ((int) Math.floor(d.x)) >> 4, ((int) Math.floor(d.z)) >> 4);
				boolean home = land != null && friendly(d.nation, land.id);
				d.morale = Math.min(100, d.morale + (home ? 0.5 : 0.1));
			}

			if (d.state == DivisionData.State.RETREATING && d.morale > 40 && Math.hypot(d.goalX - d.x, d.goalZ - d.z) < 2) {
				d.state = DivisionData.State.IDLE;
			}
		}
	}

	// ---------------------------------------------------------------- once a day

	static void runDay(MinecraftServer server) {

		for (DivisionData d : new ArrayList<>(DIVISIONS.values())) {
			NationData n = ServerNations.nation(d.nation);

			if (n == null) {
				continue;
			}

			int upkeep = d.kind.upkeep(d.strength);

			if (n.treasury >= upkeep) {
				n.treasury -= upkeep;
				n.book("Army upkeep", -upkeep);
			} else {
				// unpaid soldiers lose heart, some go home
				d.morale = Math.max(0, d.morale - 25);
				d.strength *= 0.9;
				ServerNations.notifyPlayer(server, n.leader, d.name + " was not paid! Morale is falling.");
			}

			// resting at home: new recruits fill the ranks
			NationData land = ServerNations.nationAt(d.dimension, ((int) Math.floor(d.x)) >> 4, ((int) Math.floor(d.z)) >> 4);

			// (the game's own nations hire by themselves; players hire in the War tab)
			if (n.aiRuled() && d.state == DivisionData.State.IDLE && land != null && land.id.equals(d.nation) && d.strength < d.kind.maxStrength) {
				double add = Math.min(d.kind.maxStrength - d.strength, Math.max(1, d.kind.maxStrength * 0.25));
				int cost = (int) Math.ceil(add * d.kind.hirePrice);

				if (n.treasury >= cost) {
					n.treasury -= cost;
					d.strength += add;
					n.book("Army recruits", -cost);
				}
			}
		}


		save();
	}

	// ---------------------------------------------------------------- mobs of the war (troops, guards, kings, rift creatures)

	/** Every mob the war spawned and still knows about; others with the war's name prefix are left-overs and go away. */
	private static final java.util.Set<UUID> LIVE = new java.util.HashSet<>();
	private static final List<Entity> LEFTOVERS = new ArrayList<>();
	private static final Map<String, EntityType<?>> TYPES = new HashMap<>();

	static void untrack(UUID id) {
		LIVE.remove(id);
	}

	/**
	 * Finds a vanilla entity type by its constant name. Looked up by name so it works
	 * wherever this Minecraft version keeps its entity type constants (26.x: EntityTypes).
	 */
	private static EntityType<?> entityType(String constant) {
		if (TYPES.containsKey(constant)) {
			return TYPES.get(constant);
		}

		EntityType<?> found = null;

		for (String cls : new String[] {"net.minecraft.world.entity.EntityTypes", "net.minecraft.world.entity.EntityType"}) {
			try {
				Object value = Class.forName(cls).getField(constant).get(null);

				if (value instanceof EntityType<?> t) {
					found = t;
					break;
				}
			} catch (ReflectiveOperationException | LinkageError ignored) {
				// not in this class
			}
		}

		if (found == null) {
			MapNationsMod.LOGGER.warn("Map Nations WARS: no entity type {}", constant);
		}

		TYPES.put(constant, found);
		return found;
	}

	/** Spawns a vanilla mob by its entity type constant name (e.g. "PILLAGER"). Null if it couldn't. */
	static Mob spawnMob(ServerLevel level, String typeConstant, int x, int y, int z) {
		EntityType<?> type = entityType(typeConstant);

		if (type == null) {
			return null;
		}

		Entity spawned;

		try {
			spawned = type.spawn(level, new BlockPos(x, y, z), EntitySpawnReason.EVENT);
		} catch (Exception e) {
			MapNationsMod.LOGGER.warn("Map Nations WARS: could not spawn {}", typeConstant, e);
			return null;
		}

		if (spawned instanceof Mob mob) {
			LIVE.add(mob.getUUID());
			return mob;
		}

		if (spawned != null) {
			spawned.discard();
		}

		return null;
	}

	/** Spawns any vanilla entity (a boat...) by its entity type constant name. Tracked like the war's mobs. */
	static Entity spawnEntity(ServerLevel level, String typeConstant, int x, int y, int z) {
		EntityType<?> type = entityType(typeConstant);

		if (type == null) {
			return null;
		}

		try {
			Entity e = type.spawn(level, new BlockPos(x, y, z), EntitySpawnReason.EVENT);

			if (e != null) {
				LIVE.add(e.getUUID());
				e.setCustomName(Component.literal(SOLDIER_PREFIX + "boat"));
			}

			return e;
		} catch (Exception e) {
			MapNationsMod.LOGGER.warn("Map Nations WARS: could not spawn {}", typeConstant, e);
			return null;
		}
	}

	/** Mobs with this name prefix belong to the war; left-over ones are removed when their chunk loads again. */
	static String soldierPrefix() {
		return SOLDIER_PREFIX;
	}

	// ---------------------------------------------------------------- sync and saving

	private static WarSyncPayload syncPayload() {
		List<WarSyncPayload.Siege> sieges = new ArrayList<>();

		for (Map.Entry<UUID, Siege> e : SIEGES.entrySet()) {
			sieges.add(new WarSyncPayload.Siege(e.getKey(), e.getValue().attacker, (float) e.getValue().progress));
		}

		return new WarSyncPayload(new ArrayList<>(DIVISIONS.values()), sieges, new ArrayList<>(battles));
	}

	static void broadcast(MinecraftServer server) {
		WarSyncPayload payload = syncPayload();

		for (ServerPlayer p : PlayerLookup.all(server)) {
			if (ServerPlayNetworking.canSend(p, WarSyncPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	private static void load(MinecraftServer server) {
		DIVISIONS.clear();
		SIEGES.clear();
		RAISED.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("mapnationswars_war.json");

		if (!Files.exists(file)) {
			return;
		}

		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

			for (JsonElement el : root.getAsJsonArray("divisions")) {
				JsonObject o = el.getAsJsonObject();
				DivisionData d = new DivisionData(UUID.fromString(o.get("id").getAsString()));
				d.nation = UUID.fromString(o.get("nation").getAsString());
				d.name = o.get("name").getAsString();
				d.kind = DivisionData.Kind.byName(o.get("kind").getAsString());
				d.strength = o.get("strength").getAsDouble();
				d.kind = DivisionData.Kind.byName(o.get("kind").getAsString());
				d.strength = Math.min(d.strength, d.kind.maxStrength); // divisions became smaller in 2.0
				d.morale = o.get("morale").getAsDouble();
				d.dimension = o.get("dimension").getAsString();
				d.x = o.get("x").getAsDouble();
				d.z = o.get("z").getAsDouble();
				d.goalX = o.get("goalX").getAsDouble();
				d.goalZ = o.get("goalZ").getAsDouble();
				d.target = o.has("target") ? UUID.fromString(o.get("target").getAsString()) : null;
				d.state = DivisionData.State.valueOf(o.get("state").getAsString());
				d.home = o.has("home") ? UUID.fromString(o.get("home").getAsString()) : null;
				d.commander = o.has("commander") ? UUID.fromString(o.get("commander").getAsString()) : null;
				d.orderTime = o.has("orderTime") ? o.get("orderTime").getAsLong() : 0;
				d.portal = o.has("portal") ? UUID.fromString(o.get("portal").getAsString()) : null;
				DIVISIONS.put(d.id, d);
			}

			for (JsonElement el : root.getAsJsonArray("sieges")) {
				JsonObject o = el.getAsJsonObject();
				Siege s = new Siege(UUID.fromString(o.get("attacker").getAsString()));
				s.progress = o.get("progress").getAsDouble();
				s.garrisonLoss = o.get("garrisonLoss").getAsDouble();
				SIEGES.put(UUID.fromString(o.get("province").getAsString()), s);
			}

			JsonObject raised = root.getAsJsonObject("raised");

			for (String key : raised.keySet()) {
				RAISED.put(UUID.fromString(key), raised.get(key).getAsInt());
			}
		} catch (Exception e) {
			MapNationsMod.LOGGER.error("Could not read {}", file, e);
		}
	}

	static void saveNow() {
		save();
	}

	private static void save() {
		if (file == null) {
			return;
		}

		JsonArray divisions = new JsonArray();

		for (DivisionData d : DIVISIONS.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", d.id.toString());
			o.addProperty("nation", d.nation.toString());
			o.addProperty("name", d.name);
			o.addProperty("kind", d.kind.name());
			o.addProperty("strength", d.strength);
			o.addProperty("morale", d.morale);
			o.addProperty("dimension", d.dimension);
			o.addProperty("x", d.x);
			o.addProperty("z", d.z);
			o.addProperty("goalX", d.goalX);
			o.addProperty("goalZ", d.goalZ);

			if (d.target != null) {
				o.addProperty("target", d.target.toString());
			}

			o.addProperty("state", d.state.name());

			if (d.home != null) {
				o.addProperty("home", d.home.toString());
			}

			if (d.commander != null) {
				o.addProperty("commander", d.commander.toString());
			}

			o.addProperty("orderTime", d.orderTime);

			if (d.portal != null) {
				o.addProperty("portal", d.portal.toString());
			}
			divisions.add(o);
		}

		JsonArray sieges = new JsonArray();

		for (Map.Entry<UUID, Siege> e : SIEGES.entrySet()) {
			JsonObject o = new JsonObject();
			o.addProperty("province", e.getKey().toString());
			o.addProperty("attacker", e.getValue().attacker.toString());
			o.addProperty("progress", e.getValue().progress);
			o.addProperty("garrisonLoss", e.getValue().garrisonLoss);
			sieges.add(o);
		}

		JsonObject raised = new JsonObject();

		for (Map.Entry<UUID, Integer> e : RAISED.entrySet()) {
			raised.addProperty(e.getKey().toString(), e.getValue());
		}

		JsonObject root = new JsonObject();
		root.add("divisions", divisions);
		root.add("sieges", sieges);
		root.add("raised", raised);

		try {
			Path tmp = file.resolveSibling("mapnationswars_war.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			MapNationsMod.LOGGER.error("Could not save {}", file, e);
		}
	}
}
