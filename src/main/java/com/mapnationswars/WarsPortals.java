package com.mapnationswars;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.LevelResource;

import com.mapnationswars.nation.DivisionData;
import com.mapnationswars.nation.Faction;
import com.mapnationswars.nation.Ideology;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.PortalSite;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.network.PortalsSyncPayload;

/**
 * Map Nations WARS stage 8: portals.
 * The ruined portals of the Overworld are slowly waking up. Every portal players build (or relight) makes them wake faster -
 * and wakes up itself. When a portal reaches 100% it tears open for three days and a piglin army pours into the Overworld.
 * Armies can march through open portals and player-built portals both ways: piglins invade the Overworld,
 * Overworld nations invade the Nether. Guarding a portal with an army, or killing Nether creatures near it, calms it.
 * Saved in the world folder as mapnationswars_portals.json.
 */
public final class WarsPortals {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final TagKey<Structure> RUINED_PORTALS = TagKey.create(Registries.STRUCTURE, MapNationsMod.id("ruined_portals"));
	private static final int SCAN_RADIUS = 1800;
	private static final int SCAN_STEP = 600;
	private static final int MAX_SITES = 40;
	/** How long a torn-open ruined portal stays open. */
	private static final long OPEN_TICKS = 3L * WarsEconomy.DAY_TICKS;
	/** Within this many blocks of a portal you are "at" it. */
	static final double AT_PORTAL = 48;

	private static final Map<UUID, PortalSite> SITES = new LinkedHashMap<>();
	private static final ArrayDeque<BlockPos> JOBS = new ArrayDeque<>();
	/** player -> last place in the Overworld, and the dimension they were in */
	private static final Map<UUID, double[]> LAST_OVERWORLD = new HashMap<>();
	private static final Map<UUID, String> LAST_DIM = new HashMap<>();
	/** Nether creatures that came out of an open rift -> the portal */
	private static final Map<UUID, UUID> RIFT_MOBS = new HashMap<>();
	private static final Random RANDOM = new Random();
	private static boolean scanned = false;
	private static UUID legion = null;
	private static Path file;
	private static int ticks = 0;

	private WarsPortals() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(WarsPortals::load);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save();
			SITES.clear();
			JOBS.clear();
			LAST_OVERWORLD.clear();
			LAST_DIM.clear();
			RIFT_MOBS.clear();
			scanned = false;
			legion = null;
			file = null;
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (ServerPlayNetworking.canSend(handler.player, PortalsSyncPayload.TYPE)) {
				sender.sendPacket(new PortalsSyncPayload(new ArrayList<>(SITES.values())));
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(WarsPortals::tick);

		// killing Nether creatures near a portal pushes it back
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(source.getEntity() instanceof ServerPlayer) || !PortalSite.OVERWORLD.equals(entity.level().dimension().identifier().toString())) {
				return;
			}

			// the game's own class names (it is not obfuscated): Piglin, PiglinBrute, Hoglin, Blaze, Ghast, MagmaCube, WitherSkeleton...
			String type = entity.getClass().getSimpleName().toLowerCase();

			if (type.contains("piglin") || type.contains("hoglin") || type.contains("blaze") || type.contains("ghast")
					|| type.contains("magma") || type.contains("witherskeleton")) {
				PortalSite s = nearest(PortalSite.OVERWORLD, entity.getX(), entity.getZ(), AT_PORTAL);
				RIFT_MOBS.remove(entity.getUUID());

				if (s != null && !s.open) {
					s.activation = Math.max(0, s.activation - 1.5);
				} else if (s != null) {
					// fighting at an open rift closes it sooner
					s.openTicks -= 2400;
					ServerNations.status((ServerPlayer) source.getEntity(), "The rift weakens... (" + Math.max(0, s.openTicks / 1200) + " min left)", true);
				}
			}
		});
	}

	// ---------------------------------------------------------------- queries

	static java.util.Collection<PortalSite> sites() {
		return SITES.values();
	}

	static PortalSite site(UUID id) {
		return id == null ? null : SITES.get(id);
	}

	static PortalSite nearest(String dimension, double x, double z, double within) {
		PortalSite best = null;
		double bestD = within;

		for (PortalSite s : SITES.values()) {
			double d = Math.hypot(s.xIn(dimension) - x, s.zIn(dimension) - z);

			if (d < bestD && (PortalSite.OVERWORLD.equals(dimension) || s.usable())) {
				bestD = d;
				best = s;
			}
		}

		return best;
	}

	/** The closest portal an army can march through (open, or built by players). */
	static PortalSite nearestUsable(String dimension, double x, double z) {
		PortalSite best = null;
		double bestD = Double.MAX_VALUE;

		if (!PortalSite.OVERWORLD.equals(dimension) && !PortalSite.NETHER.equals(dimension)) {
			return null;
		}

		for (PortalSite s : SITES.values()) {
			if (!s.usable()) {
				continue;
			}

			double d = Math.hypot(s.xIn(dimension) - x, s.zIn(dimension) - z);

			if (d < bestD) {
				bestD = d;
				best = s;
			}
		}

		return best;
	}

	// ---------------------------------------------------------------- every tick

	private static void tick(MinecraftServer server) {
		ticks++;

		if (!WarsWorld.isGenerated() || file == null) {
			return;
		}

		// finding the ruined portals (once per world), one search every few ticks
		if (!scanned) {
			if (JOBS.isEmpty()) {
				startScan(server);
			} else if (ticks % 4 == 0) {
				scanOne(server);
			}

			return;
		}

		if (ticks % 20 == 0) {
			watchTravellers(server);
		}

		if (ticks % 200 == 0) {
			rifts(server);
		}

		// portals wake up a little every minute (a day is 20 minutes)
		if (ticks % 1200 == 0) {
			minute(server);
		}
	}

	private static void startScan(MinecraftServer server) {
		ServerLevel overworld = server.overworld();

		for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x += SCAN_STEP) {
			for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z += SCAN_STEP) {
				BlockPos pos = new BlockPos(x, 64, z);

				if (overworld.getWorldBorder().isWithinBounds(pos)) {
					JOBS.add(pos);
				}
			}
		}

		if (JOBS.isEmpty()) {
			scanned = true;
		}
	}

	private static void scanOne(MinecraftServer server) {
		BlockPos at = JOBS.poll();

		try {
			BlockPos found = server.overworld().findNearestMapStructure(RUINED_PORTALS, at, 2, false);

			if (found != null && SITES.size() < MAX_SITES && nearest(PortalSite.OVERWORLD, found.getX(), found.getZ(), 64) == null) {
				PortalSite s = new PortalSite(UUID.randomUUID());
				s.x = found.getX();
				s.y = found.getY();
				s.z = found.getZ();
				s.name = "Ruined Portal" + near(s.x, s.z);
				s.activation = RANDOM.nextInt(25);
				SITES.put(s.id, s);
			}
		} catch (Exception e) {
			MapNationsMod.LOGGER.warn("Map Nations WARS: a ruined portal search failed", e);
		}

		if (JOBS.isEmpty()) {
			scanned = true;
			MapNationsMod.LOGGER.info("Map Nations WARS: found {} ruined portals", SITES.size());
			updateRates(server);
			save();
			broadcast(server);
		}
	}

	private static String near(double x, double z) {
		ProvinceData best = null;
		double bestD = 900;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (PortalSite.OVERWORLD.equals(p.dimension)) {
				double d = Math.hypot(p.x - x, p.z - z);

				if (d < bestD) {
					bestD = d;
					best = p;
				}
			}
		}

		return best != null ? " near " + best.name : "";
	}

	/** Notices players going through Nether portals: every portal players use is a portal players built. */
	private static void watchTravellers(MinecraftServer server) {
		for (ServerPlayer p : PlayerLookup.all(server)) {
			String dim = p.level().dimension().identifier().toString();
			String before = LAST_DIM.put(p.getUUID(), dim);

			if (PortalSite.OVERWORLD.equals(dim)) {
				if (PortalSite.NETHER.equals(before)) {
					// came out of the Nether: there is a portal right here
					playerPortal(server, p, p.getX(), p.getY(), p.getZ());
				}

				LAST_OVERWORLD.put(p.getUUID(), new double[] {p.getX(), p.getY(), p.getZ()});
			} else if (PortalSite.NETHER.equals(dim) && PortalSite.OVERWORLD.equals(before)) {
				// went into the Nether from where they last stood
				double[] last = LAST_OVERWORLD.get(p.getUUID());

				if (last != null) {
					playerPortal(server, p, last[0], last[1], last[2]);
				}
			}
		}
	}

	private static void playerPortal(MinecraftServer server, ServerPlayer player, double x, double y, double z) {
		PortalSite near = nearest(PortalSite.OVERWORLD, x, z, 24);

		if (near != null) {
			if (!near.playerBuilt) {
				// a ruined portal relit by players
				near.playerBuilt = true;
				near.builder = player.getName().getString();
				near.name = near.name.replace("Ruined Portal", "Relit Portal");
				WarsDiplomacy.news(server, "⭘ " + player.getName().getString() + " relit the " + near.name.replace("Relit Portal", "ruined portal")
						+ ". The Nether stirs...");
				updateRates(server);
				save();
				broadcast(server);
			}

			return;
		}

		PortalSite s = new PortalSite(UUID.randomUUID());
		s.x = (int) Math.floor(x);
		s.y = (int) Math.floor(y);
		s.z = (int) Math.floor(z);
		s.playerBuilt = true;
		s.builder = player.getName().getString();
		s.name = "Portal of " + s.builder + near(x, z);
		s.activation = 10;
		SITES.put(s.id, s);
		WarsDiplomacy.news(server, "⭘ A new Nether portal was opened" + near(x, z) + " (" + s.x + ", " + s.z
				+ "). Every portal makes the ruined ones wake faster...");
		updateRates(server);
		save();
		broadcast(server);
	}

	// ---------------------------------------------------------------- waking up

	private static long day(MinecraftServer server) {
		return server.overworld().getGameTime() / WarsEconomy.DAY_TICKS;
	}

	/** How fast every portal wakes up (per day). */
	static void updateRates(MinecraftServer server) {
		double piglinPower = 0;

		for (NationData n : ServerNations.allNations()) {
			if (n.faction == Faction.PIGLIN) {
				piglinPower = Math.max(piglinPower, WarsWar.armyStrength(n.id));
			}
		}

		double age = Math.min(2, day(server) / 20.0); // the world grows more dangerous

		for (PortalSite s : SITES.values()) {
			if (s.open) {
				s.rate = 0;
				continue;
			}

			double rate = (s.playerBuilt ? 3.0 : 1.0) + age + Math.min(2, piglinPower / 300.0);
			double fromBuilt = 0;

			for (PortalSite o : SITES.values()) {
				if (o != s && o.playerBuilt && Math.hypot(o.x - s.x, o.z - s.z) < 1500) {
					fromBuilt += 1.5;
				}
			}

			rate += Math.min(6, fromBuilt);

			// an army camped at a portal keeps it quiet
			for (DivisionData d : WarsWar.divisions()) {
				NationData dn = ServerNations.nation(d.nation);

				if (dn != null && dn.faction != Faction.PIGLIN && PortalSite.OVERWORLD.equals(d.dimension)
						&& Math.hypot(d.x - s.x, d.z - s.z) < AT_PORTAL) {
					rate -= 4;
					break;
				}
			}

			s.rate = Math.max(0, rate);
		}
	}

	private static void minute(MinecraftServer server) {
		updateRates(server);
		boolean changed = false;

		for (PortalSite s : new ArrayList<>(SITES.values())) {
			if (s.open) {
				s.openTicks -= 1200;

				if (s.openTicks <= 0) {
					s.open = false;
					s.activation = s.playerBuilt ? 30 : 0;
					WarsDiplomacy.news(server, "⭘ The " + s.name + " has closed again.");
				}

				changed = true;
				continue;
			}

			double before = s.activation;
			s.activation = Math.min(100, s.activation + s.rate / 20.0);

			if (before < 75 && s.activation >= 75) {
				WarsDiplomacy.news(server, "⚠ The " + s.name + " (" + s.x + ", " + s.z + ") glows with Nether light: it is 75% awake!");
			}

			if (s.activation >= 100) {
				tearOpen(server, s);
			}

			changed = true;
		}

		cleanLegion(server);

		if (changed) {
			save();
			broadcast(server);
		}
	}

	// ---------------------------------------------------------------- open rifts spit out Nether creatures near players

	private static void rifts(MinecraftServer server) {
		ServerLevel level = server.overworld();

		// creatures of closed rifts, or with nobody around, go back
		for (java.util.Iterator<Map.Entry<UUID, UUID>> it = RIFT_MOBS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, UUID> e = it.next();
			PortalSite s = SITES.get(e.getValue());
			net.minecraft.world.entity.Entity mob = level.getEntity(e.getKey());

			if (mob == null || !mob.isAlive()) {
				it.remove();
			} else if (s == null || !s.open || nearestPlayer(server, s, 120) == null) {
				mob.discard();
				it.remove();
			}
		}

		for (PortalSite s : SITES.values()) {
			if (!s.open) {
				continue;
			}

			ServerPlayer target = nearestPlayer(server, s, 64);

			if (target == null) {
				continue;
			}

			int alive = 0;

			for (UUID site : RIFT_MOBS.values()) {
				if (site.equals(s.id)) {
					alive++;
				}
			}

			if (alive >= 4) {
				continue;
			}

			int x = s.x + RANDOM.nextInt(9) - 4;
			int z = s.z + RANDOM.nextInt(9) - 4;

			if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
				continue;
			}

			int roll = RANDOM.nextInt(10);
			String type = roll < 7 ? "ZOMBIFIED_PIGLIN" : roll < 9 ? "MAGMA_CUBE" : "BLAZE";
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			net.minecraft.world.entity.Mob mob = WarsWar.spawnMob(level, type, x, y, z);

			if (mob != null) {
				mob.setCustomName(net.minecraft.network.chat.Component.literal(WarsWar.soldierPrefix() + "Nether Invader").withColor(0xFF6030));
				mob.setTarget(target);
				RIFT_MOBS.put(mob.getUUID(), s.id);
			}
		}
	}

	private static ServerPlayer nearestPlayer(MinecraftServer server, PortalSite s, double within) {
		ServerPlayer best = null;
		double bestD = within;

		for (ServerPlayer p : PlayerLookup.all(server)) {
			if (!PortalSite.OVERWORLD.equals(p.level().dimension().identifier().toString()) || p.isSpectator() || p.isCreative()) {
				continue;
			}

			double d = Math.hypot(p.getX() - s.x, p.getZ() - s.z);

			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}

		return best;
	}

	// ---------------------------------------------------------------- invasions

	/** A portal tears open: the Nether invades. */
	static void tearOpen(MinecraftServer server, PortalSite s) {
		s.open = true;
		s.openTicks = OPEN_TICKS;
		s.activation = 100;
		NationData piglins = invaders();

		// whom do they attack? the owner of the nearest Overworld province that isn't theirs
		NationData victim = null;
		double bestD = 2000;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (!PortalSite.OVERWORLD.equals(p.dimension) || p.nation == null || p.nation.equals(piglins.id)) {
				continue;
			}

			double d = Math.hypot(p.x - s.x, p.z - s.z);

			if (d < bestD) {
				bestD = d;
				victim = ServerNations.nation(p.nation);
			}
		}

		long day = day(server);
		int armies = (int) Math.min(5, 2 + day / 10);
		DivisionData.Kind[] kinds = {DivisionData.Kind.INFANTRY, DivisionData.Kind.CAVALRY, DivisionData.Kind.ARCHERS,
				DivisionData.Kind.INFANTRY, DivisionData.Kind.SIEGE};

		for (int i = 0; i < armies; i++) {
			DivisionData d = WarsWar.spawnDivision(piglins, kinds[i], PortalSite.OVERWORLD,
					s.x + RANDOM.nextInt(31) - 15, s.z + RANDOM.nextInt(31) - 15, "Invaders");
			d.morale = 95;
		}

		WarsDiplomacy.news(server, "🔥 The " + s.name + " (" + s.x + ", " + s.z + ") has torn open! " + armies + " armies of "
				+ piglins.name + " pour into the Overworld!");

		if (victim != null && !WarsDiplomacy.atWar(piglins.id, victim.id)) {
			WarsDiplomacy.startWar(server, piglins, victim, true);
		}

		WarsWar.saveNow();
		WarsWar.broadcast(server);
	}

	/** The piglins who invade: the strongest piglin clan, or the Legion of the Nether if there is none. */
	private static NationData invaders() {
		NationData best = null;
		int bestPower = -1;

		for (NationData n : ServerNations.allNations()) {
			if (n.faction == Faction.PIGLIN && n.aiRuled()) {
				int power = WarsWar.armyStrength(n.id);

				if (power > bestPower) {
					bestPower = power;
					best = n;
				}
			}
		}

		if (best != null) {
			return best;
		}

		NationData existing = ServerNations.nation(legion);

		if (existing != null) {
			return existing;
		}

		Random random = new Random();
		NationData n = new NationData(UUID.randomUUID());
		n.ai = true;
		n.faction = Faction.PIGLIN;
		n.ideology = Ideology.EMPIRE;
		n.name = "Legion of the Nether";
		n.color = Names.color(random, Faction.PIGLIN);
		n.leader = UUID.randomUUID();
		n.rulerName = Names.warlord(random, ProvinceData.Type.BASTION);
		ServerNations.addGeneratedNation(n);
		legion = n.id;
		return n;
	}

	/** The Legion of the Nether has no land: when its armies are gone, so is it. */
	private static void cleanLegion(MinecraftServer server) {
		NationData n = ServerNations.nation(legion);

		if (n == null) {
			legion = null;
			return;
		}

		if (!WarsWar.divisionsOf(n.id).isEmpty()) {
			return;
		}

		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation)) {
				return; // it conquered land: it stays
			}
		}

		ServerNations.removeGeneratedNation(n.id);
		legion = null;
		WarsDiplomacy.news(server, "☠ The Legion of the Nether was driven back.");
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
	}

	/** An army reached the portal it was marching to: it comes out on the other side. */
	static void cross(MinecraftServer server, DivisionData d) {
		PortalSite s = site(d.portal);
		d.portal = null;

		if (s == null || !s.usable()) {
			d.state = DivisionData.State.IDLE;
			return;
		}

		String to = PortalSite.OVERWORLD.equals(d.dimension) ? PortalSite.NETHER : PortalSite.OVERWORLD;
		d.dimension = to;
		d.x = s.xIn(to) + RANDOM.nextInt(9) - 4;
		d.z = s.zIn(to) + RANDOM.nextInt(9) - 4;
		d.goalX = d.x;
		d.goalZ = d.z;
		d.target = null;
		d.state = DivisionData.State.IDLE;
		NationData n = ServerNations.nation(d.nation);
		WarsDiplomacy.news(server, "⭘ " + d.name + " of " + (n != null ? n.name : "?") + " marched through the " + s.name
				+ (PortalSite.NETHER.equals(to) ? " into the Nether!" : " into the Overworld!"));
	}

	// ---------------------------------------------------------------- sync and saving

	static void broadcast(MinecraftServer server) {
		PortalsSyncPayload payload = new PortalsSyncPayload(new ArrayList<>(SITES.values()));

		for (ServerPlayer p : PlayerLookup.all(server)) {
			if (ServerPlayNetworking.canSend(p, PortalsSyncPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	private static void load(MinecraftServer server) {
		SITES.clear();
		JOBS.clear();
		scanned = false;
		legion = null;
		file = server.getWorldPath(LevelResource.ROOT).resolve("mapnationswars_portals.json");

		if (!Files.exists(file)) {
			return;
		}

		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			scanned = root.get("scanned").getAsBoolean();
			legion = root.has("legion") ? UUID.fromString(root.get("legion").getAsString()) : null;

			for (JsonElement el : root.getAsJsonArray("sites")) {
				JsonObject o = el.getAsJsonObject();
				PortalSite s = new PortalSite(UUID.fromString(o.get("id").getAsString()));
				s.name = o.get("name").getAsString();
				s.x = o.get("x").getAsInt();
				s.y = o.get("y").getAsInt();
				s.z = o.get("z").getAsInt();
				s.activation = o.get("activation").getAsDouble();
				s.open = o.get("open").getAsBoolean();
				s.openTicks = o.get("openTicks").getAsLong();
				s.playerBuilt = o.get("playerBuilt").getAsBoolean();
				s.builder = o.get("builder").getAsString();
				SITES.put(s.id, s);
			}
		} catch (Exception e) {
			MapNationsMod.LOGGER.error("Could not read {}", file, e);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}

		JsonArray sites = new JsonArray();

		for (PortalSite s : SITES.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", s.id.toString());
			o.addProperty("name", s.name);
			o.addProperty("x", s.x);
			o.addProperty("y", s.y);
			o.addProperty("z", s.z);
			o.addProperty("activation", s.activation);
			o.addProperty("open", s.open);
			o.addProperty("openTicks", s.openTicks);
			o.addProperty("playerBuilt", s.playerBuilt);
			o.addProperty("builder", s.builder);
			sites.add(o);
		}

		JsonObject root = new JsonObject();
		root.addProperty("scanned", scanned);

		if (legion != null) {
			root.addProperty("legion", legion.toString());
		}

		root.add("sites", sites);

		try {
			Path tmp = file.resolveSibling("mapnationswars_portals.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			MapNationsMod.LOGGER.error("Could not save {}", file, e);
		}
	}
}
