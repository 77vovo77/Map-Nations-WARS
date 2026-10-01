package com.mapnationswars;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.storage.LevelResource;

import com.mapnationswars.nation.Charter;
import com.mapnationswars.nation.DivisionData;
import com.mapnationswars.nation.Faction;
import com.mapnationswars.nation.Ideology;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.nation.Ranks;
import com.mapnationswars.network.SupportSyncPayload;

/**
 * Map Nations WARS stage 7: revolts, coups and founding your own nation.
 * <ul>
 * <li>Every province has unrest. Hunger, misery, foreign rule, endless wars and nations that grew too big make it rise;
 * happy villages and armies keeping order calm it. At 100% the province rises up and proclaims itself free.</li>
 * <li>A nation whose villages all hate their player leader overthrows that leader.</li>
 * <li>Officers and Ministers can try a coup - bribes, merit and an unhappy people make it likelier; failing means exile.</li>
 * <li>Anyone without a nation can found one at a discontented village whose people back them (support),
 * for a charter of 200 emeralds. The old rulers won't let the village go without a fight.</li>
 * </ul>
 * Saved in the world folder as mapnationswars_revolts.json.
 */
public final class WarsRevolts {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int CHARTER_COST = Charter.COST;
	private static final int COUP_MERIT = Charter.COUP_MERIT;
	private static final int COUP_COOLDOWN_DAYS = 3;
	/** Within this many blocks of a province's centre you are "in" it (for support). */
	private static final double PROVINCE_REACH = 90;

	private static final Random RANDOM = new Random();
	/** player -> province -> support (0 .. 300) */
	private static final Map<UUID, Map<UUID, Integer>> SUPPORT = new HashMap<>();
	/** player -> day of the last coup attempt */
	private static final Map<UUID, Long> LAST_COUP = new HashMap<>();
	/** nation -> days in a row its people hated its player leader */
	private static final Map<UUID, Integer> HATED_DAYS = new HashMap<>();
	private static Path file;
	private static int ticks = 0;

	private WarsRevolts() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(WarsRevolts::load);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save();
			SUPPORT.clear();
			LAST_COUP.clear();
			HATED_DAYS.clear();
			file = null;
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendSupport(handler.player));

		// spending time in a village makes its people know you
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 6000 == 0 && WarsWorld.isGenerated()) {
				for (ServerPlayer p : PlayerLookup.all(server)) {
					ProvinceData here = provinceNear(p.level().dimension().identifier().toString(), p.getX(), p.getZ());

					if (here != null) {
						addSupport(p, here, 1);
					}
				}
			}
		});

		// protecting a village from monsters wins its heart
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof Enemy && source.getEntity() instanceof ServerPlayer player) {
				ProvinceData p = provinceNear(entity.level().dimension().identifier().toString(), entity.getX(), entity.getZ());

				if (p != null) {
					addSupport(player, p, 2);
				}
			}
		});
	}

	// ---------------------------------------------------------------- support

	/** The living village whose centre is closest (within reach), or null. */
	static ProvinceData provinceNear(String dimension, double x, double z) {
		ProvinceData best = null;
		double bestD = PROVINCE_REACH;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (p.type == ProvinceData.Type.VILLAGE && !p.abandoned && p.dimension.equals(dimension)) {
				double d = Math.hypot(p.x - x, p.z - z);

				if (d < bestD) {
					bestD = d;
					best = p;
				}
			}
		}

		return best;
	}

	static int support(UUID player, UUID province) {
		Map<UUID, Integer> m = SUPPORT.get(player);
		return m == null ? 0 : m.getOrDefault(province, 0);
	}

	static void addSupport(ServerPlayer player, ProvinceData p, int amount) {
		if (amount <= 0) {
			return;
		}

		SUPPORT.computeIfAbsent(player.getUUID(), k -> new HashMap<>()).merge(p.id, amount, (a, b) -> Math.min(300, a + b));
		sendSupport(player);
	}

	private static void sendSupport(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, SupportSyncPayload.TYPE)) {
			return;
		}

		List<SupportSyncPayload.Entry> list = new ArrayList<>();
		Map<UUID, Integer> m = SUPPORT.get(player.getUUID());

		if (m != null) {
			for (Map.Entry<UUID, Integer> e : m.entrySet()) {
				if (e.getValue() > 0) {
					list.add(new SupportSyncPayload.Entry(e.getKey(), e.getValue()));
				}
			}
		}

		ServerPlayNetworking.send(player, new SupportSyncPayload(list));
	}

	// ---------------------------------------------------------------- founding a nation (the charter)

	/** Why this player can't found a nation right here (null = they can), and the province it would be at. */
	static String charterProblem(ServerPlayer player, ProvinceData[] where) {
		if (ServerNations.nationOf(player.getUUID()) != null) {
			return "Leave your nation first: a founder belongs to no one.";
		}

		ProvinceData p = provinceNear(player.level().dimension().identifier().toString(), player.getX(), player.getZ());

		if (p == null) {
			return "Stand in the village where you want to found your nation.";
		}

		where[0] = p;
		return Charter.problem(p, support(player.getUUID(), p.id), WarsItems.countEmeralds(player));
	}

	/** The player founded a nation (ServerNations made it): the village breaks away. */
	static void founded(MinecraftServer server, ServerPlayer player, NationData n, ProvinceData p) {
		WarsItems.takeEmeralds(player, CHARTER_COST);
		NationData old = ServerNations.nation(p.nation);
		n.ai = true; // it owns provinces like the nations of the world
		n.faction = Faction.PLAYER;
		n.rulerName = player.getName().getString();
		n.treasury = 40;
		ServerNations.addGeneratedNation(n);
		WarsWorld.transferProvince(p, n.id);
		p.unrest = 0;
		p.happiness = Math.max(p.happiness, 65);
		SUPPORT.getOrDefault(player.getUUID(), new HashMap<>()).put(p.id, 0);
		freeMilitia(n, p, "Free Militia");
		WarsDiplomacy.news(server, "⚑ " + player.getName().getString() + " founded " + n.name + " at " + p.name
				+ (old != null ? ", breaking free from " + old.name + "!" : "!"));

		if (old != null) {
			// the old rulers won't let it go
			WarsDiplomacy.startWar(server, old, n, false);
		}

		sendSupport(player);
		afterChange(server);
	}

	/** A free division of militia for a new nation or rebels. */
	private static void freeMilitia(NationData n, ProvinceData p, String name) {
		n.treasury += DivisionData.Kind.INFANTRY.cost;
		DivisionData d = WarsWar.raiseDivision(n, p, DivisionData.Kind.INFANTRY);
		d.strength = Math.min(DivisionData.Kind.INFANTRY.maxStrength, 8 + p.population);
		d.morale = 90;
		d.name = name + " of " + p.name;
	}

	// ---------------------------------------------------------------- coups

	// ---------------------------------------------------------------- the conspiracy (2.1)

	/** player -> how far their plot got (0..100) */
	private static final Map<UUID, Integer> CONSPIRACY = new HashMap<>();
	private static final Map<UUID, Long> LAST_ARMY = new HashMap<>();
	private static final Map<UUID, Long> LAST_BRIBE = new HashMap<>();

	static int conspiracy(UUID player) {
		return CONSPIRACY.getOrDefault(player, 0);
	}

	/**
	 * Building a conspiracy before a coup: BRIBE officials (20 emeralds, +15, they may talk),
	 * or win the ARMY over (Ministers, or whoever commanded one of the nation's armies; +20, once a day).
	 * An unhappy people makes the plot grow by itself every day.
	 */
	static void plot(MinecraftServer server, ServerPlayer player, String how) {
		UUID me = player.getUUID();
		NationData n = ServerNations.nationOf(me);

		if (n == null || n.leader.equals(me)) {
			ServerNations.status(player, n == null ? "You are in no nation." : "You already rule.", false);
			return;
		}

		if (n.rankOf(me) < Ranks.OFFICER) {
			ServerNations.status(player, "Only Officers and Ministers know the right people. Earn merit first.", false);
			return;
		}

		long now = server.overworld().getGameTime();
		long day = now / WarsEconomy.DAY_TICKS;

		if ("ARMY".equals(how)) {
			boolean commands = n.rankOf(me) >= Ranks.MINISTER;

			for (com.mapnationswars.nation.DivisionData d : WarsWar.divisionsOf(n.id)) {
				commands |= me.equals(d.commander);
			}

			if (!commands) {
				ServerNations.status(player, "The soldiers don't know you. Command one of the nation's armies first (War tab / map).", false);
				return;
			}

			if (LAST_ARMY.getOrDefault(me, -1L) == day) {
				ServerNations.status(player, "You already spoke to the soldiers today.", false);
				return;
			}

			LAST_ARMY.put(me, day);
			int v = CONSPIRACY.merge(me, 20, (a, b) -> Math.min(100, a + b));
			ServerNations.status(player, "Soldiers of " + n.name + " swear to follow you. Conspiracy " + v + "%.", true);
		} else {
			if (now - LAST_BRIBE.getOrDefault(me, -10000L) < 1200) {
				ServerNations.status(player, "Bribing too fast draws attention. Wait a minute.", false);
				return;
			}

			if (WarsItems.countEmeralds(player) < Charter.BRIBE_COST) {
				ServerNations.status(player, "A bribe costs " + Charter.BRIBE_COST + " emeralds (carry them).", false);
				return;
			}

			WarsItems.takeEmeralds(player, Charter.BRIBE_COST);
			LAST_BRIBE.put(me, now);

			if (RANDOM.nextDouble() < 0.15) {
				// someone talked
				int v = CONSPIRACY.getOrDefault(me, 0) / 2;
				CONSPIRACY.put(me, v);
				n.merit.put(me, Math.max(0, n.merit.getOrDefault(me, 0) - 30));
				ServerNations.status(player, "\u26A0 An official took your emeralds and talked! The plot is half exposed (" + v + "%), -30 merit.", false);
				ServerNations.notifyPlayer(server, n.leader, "Rumours of a plot against you in " + n.name + "...");
			} else {
				int v = CONSPIRACY.merge(me, 15, (a, b) -> Math.min(100, a + b));
				ServerNations.status(player, "An official of " + n.name + " joins your plot. Conspiracy " + v + "%.", true);
			}
		}

		WarsPeople.sync(player);
		save();
	}

	static void coup(MinecraftServer server, ServerPlayer player) {
		UUID me = player.getUUID();
		NationData n = ServerNations.nationOf(me);
		long day = server.overworld().getGameTime() / WarsEconomy.DAY_TICKS;

		if (n == null || n.leader.equals(me)) {
			ServerNations.status(player, n == null ? "You are in no nation." : "You already rule " + n.name + ".", false);
			return;
		}

		if (n.rankOf(me) < Ranks.OFFICER) {
			ServerNations.status(player, "Only Officers and Ministers have the friends for a coup.", false);
			return;
		}

		if (n.merit.getOrDefault(me, 0) < COUP_MERIT) {
			ServerNations.status(player, "You need " + COUP_MERIT + " merit for anyone to follow you.", false);
			return;
		}

		if (day - LAST_COUP.getOrDefault(me, -100L) < COUP_COOLDOWN_DAYS) {
			ServerNations.status(player, "The guards are still watching you. Wait a few days.", false);
			return;
		}

		int plot = CONSPIRACY.getOrDefault(me, 0);

		if (plot < Charter.COUP_READY) {
			ServerNations.status(player, "Your conspiracy is too small (" + plot + "/" + Charter.COUP_READY + "). Bribe officials, win the army.", false);
			return;
		}

		LAST_COUP.put(me, day);
		CONSPIRACY.remove(me);
		boolean leaderOnline = false;

		for (ServerPlayer p : PlayerLookup.all(server)) {
			leaderOnline |= p.getUUID().equals(n.leader);
		}

		double chance = Charter.coupChance(n, me, averageHappiness(n), leaderOnline, plot);
		String name = player.getName().getString();

		if (RANDOM.nextDouble() < chance) {
			UUID oldLeader = n.leader;
			String oldName = n.leaderName();
			n.leader = me;
			n.ranks.put(me, Ranks.MINISTER);
			n.candidates.clear();

			if (n.isMember(oldLeader)) {
				n.ranks.put(oldLeader, Ranks.CITIZEN);
				ServerNations.notifyPlayer(server, oldLeader, name + " overthrew you in a coup!");
			}

			ServerNations.syncOfficers(n);
			n.lastElection = name + " seized power from " + oldName + ".";

			for (ProvinceData p : WarsWorld.provinces()) {
				if (n.id.equals(p.nation)) {
					p.unrest = Math.min(100, p.unrest + 15); // nobody is sure who is in charge
				}
			}

			WarsDiplomacy.news(server, "♛ " + name + " seized power in " + n.name + "! " + oldName + " was overthrown.");
		} else {
			n.members.removeIf(m -> m.id().equals(me));
			ServerNations.removeMemberData(n, me);
			ServerNations.syncOfficers(n);
			WarsDiplomacy.news(server, "☠ " + name + "'s coup in " + n.name + " failed. " + name + " fled into exile.");
		}

		afterChange(server);
	}

	static double averageHappiness(NationData n) {
		int total = 0;
		int count = 0;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation) && !p.abandoned) {
				total += p.happiness;
				count++;
			}
		}

		return count == 0 ? 50 : total / (double) count;
	}

	// ---------------------------------------------------------------- once a day: unrest and revolts

	static void runDay(MinecraftServer server) {
		long day = server.overworld().getGameTime() / WarsEconomy.DAY_TICKS;
		Map<UUID, Integer> provincesOf = new HashMap<>();

		for (ProvinceData p : WarsWorld.provinces()) {
			if (p.nation != null) {
				provincesOf.merge(p.nation, 1, Integer::sum);
			}
		}

		// support slowly fades
		for (Map<UUID, Integer> m : SUPPORT.values()) {
			m.replaceAll((k, v) -> Math.max(0, v - 1));
			m.values().removeIf(v -> v <= 0);
		}

		// unhappy peoples feed every plot against their rulers
		for (Map.Entry<UUID, Integer> e : CONSPIRACY.entrySet()) {
			NationData n = ServerNations.nationOf(e.getKey());

			if (n != null) {
				double avg = averageHappiness(n);

				if (avg < 50) {
					e.setValue(Math.min(100, e.getValue() + (int) ((50 - avg) / 5)));
				}
			}
		}

		List<ProvinceData> rising = new ArrayList<>();

		for (ProvinceData p : WarsWorld.provinces()) {
			NationData n = ServerNations.nation(p.nation);

			if (n == null || p.abandoned || n.faction == Faction.UNDEAD) {
				p.unrest = 0;
				continue;
			}

			p.unrest = Math.max(0, Math.min(100, p.unrest + unrestChange(p, n, provincesOf.getOrDefault(n.id, 1), day)));

			if (p.unrest >= 100 && !p.capital) {
				rising.add(p);
			} else if (p.unrest >= 85) {
				for (NationData.Member m : n.members) {
					if (n.leader.equals(m.id()) || n.rankOf(m.id()) >= Ranks.MINISTER) {
						ServerNations.notifyPlayer(server, m.id(), "🔥 " + p.name + " is close to revolt (" + p.unrest + "% unrest)!");
					}
				}
			}
		}

		for (ProvinceData p : rising) {
			if (p.unrest >= 100) {
				revolt(server, p);
			}
		}

		// a people that hates its player leader overthrows them
		for (NationData n : new ArrayList<>(ServerNations.allNations())) {
			if (!n.ai || n.aiRuled() || provincesOf.getOrDefault(n.id, 0) == 0) {
				HATED_DAYS.remove(n.id);
				continue;
			}

			if (averageHappiness(n) < 25) {
				int days = HATED_DAYS.merge(n.id, 1, Integer::sum);

				if (days >= 3) {
					depose(server, n);
					HATED_DAYS.remove(n.id);
				} else {
					ServerNations.notifyPlayer(server, n.leader, "The people of " + n.name + " hate your rule! Make them happier, or they will overthrow you ("
							+ days + "/3 days).");
				}
			} else {
				HATED_DAYS.remove(n.id);
			}
		}

		save();
	}

	/** How much a province's unrest changes today. */
	static int unrestChange(ProvinceData p, NationData n, int nationProvinces, long day) {
		int change = 0;

		// misery
		if (p.happiness < 30) {
			change += Math.min(15, (30 - p.happiness) / 2 + 2);
		}

		if (p.food <= 0 && p.lastFood < 0) {
			change += 8; // hunger
		}

		// new masters
		if (day - p.conqueredDay < 10) {
			change += 5;
		}

		// foreign rule: villagers ruled by illagers or piglins
		if (p.type == ProvinceData.Type.VILLAGE && (n.faction == Faction.ILLAGER || n.faction == Faction.PIGLIN)) {
			change += 4;
		}

		// nations that grew too big can't hold everything ("you can't just claim all the land")
		int limit = n.aiRuled() ? 9 : 6;

		if (nationProvinces > limit) {
			change += nationProvinces - limit;
		}

		// endless wars
		if (!WarsDiplomacy.enemiesOf(n).isEmpty()) {
			change += 2;
		}

		// calm: happy villages and soldiers keeping order
		if (p.happiness >= 60) {
			change -= 6;
		} else if (p.happiness >= 45) {
			change -= 3;
		}

		for (DivisionData d : WarsWar.divisionsOf(n.id)) {
			if (d.dimension.equals(p.dimension) && Math.hypot(d.x - p.x, d.z - p.z) < 64) {
				change -= 5;
				break;
			}
		}

		return change;
	}

	/** A province rises up and proclaims itself free. Restless neighbours may join. */
	static void revolt(MinecraftServer server, ProvinceData p) {
		revolt(server, p, null);
	}

	/**
	 * A player raises the banner of revolt at a village that is ready (2.1): no nation, the people back them (60+),
	 * unrest 70%+. The village rises at once and the player leads it.
	 */
	static void leadRevolt(MinecraftServer server, ServerPlayer player, ProvinceData p) {
		String problem = leadProblem(player, p);

		if (problem != null) {
			ServerNations.status(player, problem, false);
			return;
		}

		p.unrest = 100;
		revolt(server, p, player);
	}

	static String leadProblem(ServerPlayer player, ProvinceData p) {
		if (ServerNations.nationOf(player.getUUID()) != null) {
			return "A rebel leader belongs to no nation. Leave yours first.";
		}

		if (p.nation == null || p.abandoned || p.type != ProvinceData.Type.VILLAGE) {
			return "There is nobody here to lead.";
		}

		if (p.capital) {
			return p.name + " is a capital - it won't rise.";
		}

		if (!player.level().dimension().identifier().toString().equals(p.dimension) || Math.hypot(player.getX() - p.x, player.getZ() - p.z) > 96) {
			return "Stand in " + p.name + " to raise the banner.";
		}

		int s = support(player.getUUID(), p.id);

		if (s < 60) {
			return "The people don't trust you enough yet (support " + s + "/60).";
		}

		if (p.unrest < 70) {
			return p.name + " isn't angry enough yet (unrest " + p.unrest + "/70). Stir it up first.";
		}

		return null;
	}

	static void revolt(MinecraftServer server, ProvinceData p, ServerPlayer chosen) {
		NationData old = ServerNations.nation(p.nation);

		if (old == null) {
			return;
		}

		Faction faction = switch (p.type) {
			case OUTPOST, MANSION -> Faction.ILLAGER;
			case BASTION -> Faction.PIGLIN;
			default -> Faction.VILLAGER;
		};

		NationData rebels = new NationData(UUID.randomUUID());
		rebels.ai = true;
		rebels.faction = faction;
		rebels.ideology = faction == Faction.VILLAGER
				? new Ideology[] {Ideology.LIBERALISM, Ideology.ANARCHISM, Ideology.AGRARIANISM, Ideology.SOCIALISM}[RANDOM.nextInt(4)]
				: Ideology.ANARCHISM;
		rebels.name = (faction == Faction.VILLAGER ? "Free " : "Rebels of ") + p.name;
		rebels.color = Names.color(RANDOM, faction);
		rebels.leader = UUID.randomUUID();
		rebels.rulerName = p.mayorName.isEmpty() ? Names.person(RANDOM) : p.mayorName;
		rebels.treasury = p.funds / 2;
		p.funds -= p.funds / 2;

		// a player the people love may lead the revolt
		ServerPlayer hero = chosen;
		int best = chosen != null ? Integer.MAX_VALUE : 59;

		for (ServerPlayer pl : PlayerLookup.all(server)) {
			int s = support(pl.getUUID(), p.id);

			if (s > best && ServerNations.nationOf(pl.getUUID()) == null) {
				best = s;
				hero = pl;
			}
		}

		if (hero != null) {
			rebels.leader = hero.getUUID();
			rebels.members.add(new NationData.Member(hero.getUUID(), hero.getName().getString()));
			rebels.ranks.put(hero.getUUID(), Ranks.MINISTER);
			ServerNations.status(hero, "The people of " + p.name + " rose up - and chose you to lead them! You rule " + rebels.name + ".", true);
		}

		ServerNations.addGeneratedNation(rebels);
		WarsWorld.transferProvince(p, rebels.id);
		p.unrest = 0;
		p.happiness = Math.max(p.happiness, 55);
		freeMilitia(rebels, p, "Rebels");

		if (hero != null) {
			// those who back you take up arms too
			for (com.mapnationswars.nation.DivisionData d : WarsWar.divisionsOf(rebels.id)) {
				d.strength = Math.min(d.kind.maxStrength, d.strength + support(hero.getUUID(), p.id) / 20.0);
			}
		}
		WarsDiplomacy.news(server, "🔥 " + p.name + " rose up against " + old.name + "! The rebels proclaim " + rebels.name
				+ (hero != null ? ", led by " + hero.getName().getString() : "") + ".");

		// angry neighbours of the same nation join in
		for (ProvinceData other : new ArrayList<>(WarsWorld.provinces())) {
			if (other != p && old.id.equals(other.nation) && !other.capital && other.unrest >= 70 && other.dimension.equals(p.dimension)
					&& Math.hypot(other.x - p.x, other.z - p.z) < 700) {
				WarsWorld.transferProvince(other, rebels.id);
				other.unrest = 0;
				WarsDiplomacy.news(server, "🔥 " + other.name + " joins the revolt of " + rebels.name + "!");
			}
		}

		if (ServerNations.nation(old.id) != null) {
			WarsDiplomacy.startWar(server, rebels, old, false);
		}

		afterChange(server);
	}

	/** The people overthrow a hated player leader: the old ways return. */
	private static void depose(MinecraftServer server, NationData n) {
		UUID leader = n.leader;
		NationData.Member m = n.member(leader);
		String name = m != null ? m.name() : "The leader";
		n.ranks.put(leader, Ranks.CITIZEN);
		n.leader = UUID.randomUUID();
		n.rulerName = Names.person(RANDOM);
		n.lastElection = "The people overthrew " + name + ". " + n.rulerName + " rules now.";
		ServerNations.syncOfficers(n);
		ServerNations.notifyPlayer(server, leader, "The people of " + n.name + " overthrew you!");
		WarsDiplomacy.news(server, "⚔ The people of " + n.name + " overthrew " + name + ". " + n.rulerName + " takes power.");
		afterChange(server);
	}

	private static void afterChange(MinecraftServer server) {
		WarsWorld.saveNow();
		ServerNations.saveNow(server);
		WarsWar.saveNow();
		save();
		ServerNations.broadcast(server);
		WarsWorld.broadcast(server);
		WarsWar.broadcast(server);
	}

	// ---------------------------------------------------------------- saving

	private static void load(MinecraftServer server) {
		SUPPORT.clear();
		LAST_COUP.clear();
		HATED_DAYS.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("mapnationswars_revolts.json");

		if (!Files.exists(file)) {
			return;
		}

		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject support = root.getAsJsonObject("support");

			for (String player : support.keySet()) {
				Map<UUID, Integer> m = new HashMap<>();
				JsonObject o = support.getAsJsonObject(player);

				for (String province : o.keySet()) {
					m.put(UUID.fromString(province), o.get(province).getAsInt());
				}

				SUPPORT.put(UUID.fromString(player), m);
			}

			JsonObject coups = root.getAsJsonObject("lastCoup");

			for (String k : coups.keySet()) {
				LAST_COUP.put(UUID.fromString(k), coups.get(k).getAsLong());
			}

			if (root.has("conspiracy")) {
				JsonObject c = root.getAsJsonObject("conspiracy");

				for (String k : c.keySet()) {
					CONSPIRACY.put(UUID.fromString(k), c.get(k).getAsInt());
				}
			}

			JsonObject hated = root.getAsJsonObject("hatedDays");

			for (String k : hated.keySet()) {
				HATED_DAYS.put(UUID.fromString(k), hated.get(k).getAsInt());
			}
		} catch (Exception e) {
			MapNationsMod.LOGGER.error("Could not read {}", file, e);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}

		JsonObject support = new JsonObject();

		for (Map.Entry<UUID, Map<UUID, Integer>> e : SUPPORT.entrySet()) {
			JsonObject o = new JsonObject();

			for (Map.Entry<UUID, Integer> s : e.getValue().entrySet()) {
				o.addProperty(s.getKey().toString(), s.getValue());
			}

			support.add(e.getKey().toString(), o);
		}

		JsonObject coups = new JsonObject();

		for (Map.Entry<UUID, Long> e : LAST_COUP.entrySet()) {
			coups.addProperty(e.getKey().toString(), e.getValue());
		}

		JsonObject hated = new JsonObject();

		for (Map.Entry<UUID, Integer> e : HATED_DAYS.entrySet()) {
			hated.addProperty(e.getKey().toString(), e.getValue());
		}

		JsonObject root = new JsonObject();
		root.add("support", support);
		root.add("lastCoup", coups);
		root.add("hatedDays", hated);
		JsonObject plots = new JsonObject();

		for (Map.Entry<UUID, Integer> e : CONSPIRACY.entrySet()) {
			plots.addProperty(e.getKey().toString(), e.getValue());
		}

		root.add("conspiracy", plots);

		try {
			Path tmp = file.resolveSibling("mapnationswars_revolts.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			MapNationsMod.LOGGER.error("Could not save {}", file, e);
		}
	}
}
