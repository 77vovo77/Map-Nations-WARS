package com.mapnationswars;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
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

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import com.mapnationswars.nation.Faction;
import com.mapnationswars.nation.Ideology;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.network.ProvincesSyncPayload;

/**
 * Map Nations WARS: the world of nations.
 * When a new world starts, the server finds the villages, pillager outposts, woodland mansions and bastions around spawn,
 * turns each one into a province with its own land and mayor, and groups them into AI nations:
 * villager kingdoms (and a few independent villages), illager dominions, piglin clans - and undead hordes
 * that take over villages where only zombies are left.
 * Saved in the world folder as mapnationswars_world.json.
 */
public final class WarsWorld {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** How far from the centre of the world (in blocks) the first scan looks for villages. */
	private static final int SCAN_RADIUS = 2000;
	private static final int NETHER_SCAN_RADIUS = 700;
	/** Land around each kind of province, in chunks. */
	private static final int VILLAGE_RADIUS = 4;
	private static final int OUTPOST_RADIUS = 2;
	private static final int MANSION_RADIUS = 4;
	private static final int BASTION_RADIUS = 3;

	private static final TagKey<Structure> OUTPOSTS = TagKey.create(Registries.STRUCTURE, MapNationsMod.id("outposts"));
	private static final TagKey<Structure> MANSIONS = TagKey.create(Registries.STRUCTURE, MapNationsMod.id("mansions"));
	private static final TagKey<Structure> BASTIONS = TagKey.create(Registries.STRUCTURE, MapNationsMod.id("bastions"));

	private static final Map<UUID, ProvinceData> PROVINCES = new LinkedHashMap<>();
	/** province id -> the villager who is its mayor (server only) */
	private static final Map<UUID, UUID> MAYOR_ENTITY = new HashMap<>();

	private record Job(ServerLevel level, TagKey<Structure> tag, ProvinceData.Type type, BlockPos pos, int radius) {
	}

	private record Found(ProvinceData.Type type, String dimension, int x, int z) {
	}

	private static final ArrayDeque<Job> JOBS = new ArrayDeque<>();
	private static final List<Found> FOUND = new ArrayList<>();
	private static int totalJobs = 0;
	private static boolean generated = false;
	private static Path file;
	private static int ticks = 0;

	private WarsWorld() {
	}

	static void init() {
		// after ServerNations has loaded its nations
		ServerLifecycleEvents.SERVER_STARTED.register(WarsWorld::load);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save();
			PROVINCES.clear();
			MAYOR_ENTITY.clear();
			JOBS.clear();
			FOUND.clear();
			generated = false;
			file = null;
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (ServerPlayNetworking.canSend(handler.player, ProvincesSyncPayload.TYPE)) {
				sender.sendPacket(new ProvincesSyncPayload(new ArrayList<>(PROVINCES.values())));
			}

			if (!generated) {
				ServerNations.status(handler.player, "Map Nations WARS is mapping the nations of the world... (" + progress() + "%)", true);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(WarsWorld::tick);

		// right-click a mayor: open the village page instead of trading
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof Villager)) {
				return net.minecraft.world.InteractionResult.PASS;
			}

			if (level.isClientSide()) {
				return entity.hasCustomName() && entity.getCustomName() != null && entity.getCustomName().getString().startsWith("Mayor ")
						? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.PASS;
			}

			ProvinceData p = provinceOfMayor(entity.getUUID());

			if (p == null || !(player instanceof ServerPlayer sp)) {
				return net.minecraft.world.InteractionResult.PASS;
			}

			if (ServerPlayNetworking.canSend(sp, com.mapnationswars.network.OpenVillagePayload.TYPE)) {
				ServerPlayNetworking.send(sp, new com.mapnationswars.network.OpenVillagePayload(p.id.toString(),
						WarsItems.countEmeralds(sp), WarsEconomy.canOrder(sp, p)));
			}

			return net.minecraft.world.InteractionResult.SUCCESS;
		});

		ServerPlayNetworking.registerGlobalReceiver(com.mapnationswars.network.VillageActionPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.level().getServer();

			if (server == null) {
				return;
			}

			server.execute(() -> {
				ProvinceData p;

				try {
					p = PROVINCES.get(UUID.fromString(payload.province()));
				} catch (IllegalArgumentException e) {
					return;
				}

				if (p == null) {
					return;
				}

				int action = payload.action();
				boolean inPerson = action != com.mapnationswars.network.VillageActionPayload.ORDER;

				// emeralds change hands in person; orders can be given from the map
				if (inPerson && (!player.level().dimension().identifier().toString().equals(p.dimension)
						|| Math.hypot(player.getX() - p.x, player.getZ() - p.z) > 96)) {
					ServerNations.status(player, "Go to " + p.name + " and talk to its mayor.", false);
					return;
				}

				if (action == com.mapnationswars.network.VillageActionPayload.DONATE) {
					WarsEconomy.donate(server, player, p, payload.amount());
				} else if (action == com.mapnationswars.network.VillageActionPayload.COLLECT_SALARY) {
					WarsPolitics.collectSalary(server, player, p);
				} else if (action == com.mapnationswars.network.VillageActionPayload.DEPOSIT
						|| action == com.mapnationswars.network.VillageActionPayload.WITHDRAW) {
					WarsPolitics.treasury(server, player, p, payload.amount(), action == com.mapnationswars.network.VillageActionPayload.DEPOSIT);
				} else if (payload.action() == com.mapnationswars.network.VillageActionPayload.ORDER) {
					WarsEconomy.Build b = WarsEconomy.Build.byName(payload.argument());

					if (b != null) {
						WarsEconomy.order(server, player, p, b);
					}
				}
			});
		});
	}

	// ---------------------------------------------------------------- queries

	static java.util.Collection<ProvinceData> provinces() {
		return PROVINCES.values();
	}

	/** Is this entity the mayor of a village? Returns the village, or null. */
	static ProvinceData provinceOfMayor(UUID entity) {
		for (Map.Entry<UUID, UUID> e : MAYOR_ENTITY.entrySet()) {
			if (e.getValue().equals(entity)) {
				return PROVINCES.get(e.getKey());
			}
		}

		return null;
	}

	static void saveNow() {
		save();
	}

	/** Is anyone close enough that the province's chunk is loaded? (then real villagers are counted) */
	static boolean isNearPlayer(MinecraftServer server, ProvinceData p) {
		ServerLevel level = levelOf(server, p.dimension);
		return level != null && level.getChunkSource().hasChunk(Math.floorDiv(p.x, 16), Math.floorDiv(p.z, 16));
	}

	static boolean isGenerated() {
		return generated;
	}

	static ProvinceData province(UUID id) {
		return PROVINCES.get(id);
	}

	/** Villagers living in all provinces of a nation. */
	static int population(UUID nation) {
		int total = 0;

		for (ProvinceData p : PROVINCES.values()) {
			if (nation.equals(p.nation)) {
				total += p.population;
			}
		}

		return total;
	}

	private static int progress() {
		return totalJobs == 0 ? 0 : (int) (100 * (totalJobs - JOBS.size()) / (double) totalJobs);
	}

	// ---------------------------------------------------------------- scanning the world

	private static void startScan(MinecraftServer server) {
		JOBS.clear();
		FOUND.clear();
		ServerLevel overworld = server.overworld();
		addGrid(overworld, StructureTags.VILLAGE, ProvinceData.Type.VILLAGE, SCAN_RADIUS, 512, 2);
		addGrid(overworld, OUTPOSTS, ProvinceData.Type.OUTPOST, SCAN_RADIUS, 512, 2);
		addGrid(overworld, MANSIONS, ProvinceData.Type.MANSION, SCAN_RADIUS + 1000, 1536, 2);
		ServerLevel nether = server.getLevel(Level.NETHER);

		if (nether != null) {
			addGrid(nether, BASTIONS, ProvinceData.Type.BASTION, NETHER_SCAN_RADIUS, 384, 2);
		}

		totalJobs = JOBS.size();
		MapNationsMod.LOGGER.info("Map Nations WARS: looking for villages and strongholds ({} searches)", totalJobs);
	}

	/** Search points on a grid around the middle of the world (inside the world border). */
	private static void addGrid(ServerLevel level, TagKey<Structure> tag, ProvinceData.Type type, int radius, int step, int searchRadius) {
		WorldBorder border = level.getWorldBorder();

		for (int x = -radius; x <= radius; x += step) {
			for (int z = -radius; z <= radius; z += step) {
				BlockPos pos = new BlockPos(x, 64, z);

				if (border.isWithinBounds(pos)) {
					JOBS.add(new Job(level, tag, type, pos, searchRadius));
				}
			}
		}
	}

	private static void tick(MinecraftServer server) {
		if (!generated && !JOBS.isEmpty()) {
			// one search per tick, so the server never freezes
			Job job = JOBS.poll();

			try {
				BlockPos found = job.level().findNearestMapStructure(job.tag(), job.pos(), job.radius(), false);

				if (found != null && job.level().getWorldBorder().isWithinBounds(found)) {
					addFound(new Found(job.type(), job.level().dimension().identifier().toString(), found.getX(), found.getZ()));
				}
			} catch (Exception e) {
				MapNationsMod.LOGGER.warn("Map Nations WARS: a structure search failed", e);
			}

			if (JOBS.isEmpty()) {
				generate(server);
			} else if (JOBS.size() % 40 == 0) {
				for (ServerPlayer p : PlayerLookup.all(server)) {
					ServerNations.status(p, "Map Nations WARS is mapping the nations of the world... (" + progress() + "%)", true);
				}
			}

			return;
		}

		if (generated && ++ticks % 100 == 0) {
			updateVillages(server);
		}

		if (generated) {
			WarsEconomy.tick(server);
		}
	}

	private static void addFound(Found f) {
		for (Found other : FOUND) {
			if (other.type() == f.type() && other.dimension().equals(f.dimension())
					&& Math.abs(other.x() - f.x()) < 48 && Math.abs(other.z() - f.z()) < 48) {
				return; // same place found twice
			}
		}

		FOUND.add(f);
	}

	// ---------------------------------------------------------------- making provinces and nations

	private static void generate(MinecraftServer server) {
		Random random = new Random(server.overworld().getSeed() ^ 0x4D4E5752L);
		PROVINCES.clear();

		for (Found f : FOUND) {
			ProvinceData p = new ProvinceData(new UUID(random.nextLong(), random.nextLong()));
			p.type = f.type();
			p.dimension = f.dimension();
			p.x = f.x();
			p.z = f.z();
			p.name = Names.place(random, f.type());
			p.mayorName = f.type() == ProvinceData.Type.VILLAGE ? Names.person(random) : Names.warlord(random, f.type());
			p.population = switch (f.type()) {
				case VILLAGE -> 5 + random.nextInt(9);
				case OUTPOST -> 4 + random.nextInt(5);
				case MANSION -> 10 + random.nextInt(8);
				case BASTION -> 8 + random.nextInt(10);
			};
			WarsEconomy.startingBuildings(p, random);
			PROVINCES.put(p.id, p);
		}

		assignLand();

		// villager nations: kingdoms of several villages, and some villages on their own
		List<ProvinceData> villages = byType(ProvinceData.Type.VILLAGE);
		cluster(random, villages, Faction.VILLAGER, 900, 7, 0.28);

		// illager nations: every woodland mansion rules the outposts near it, other outposts form warbands
		List<ProvinceData> mansions = byType(ProvinceData.Type.MANSION);
		List<ProvinceData> outposts = byType(ProvinceData.Type.OUTPOST);

		for (ProvinceData mansion : mansions) {
			List<ProvinceData> group = new ArrayList<>();
			group.add(mansion);
			outposts.sort(Comparator.comparingDouble(o -> dist(o, mansion)));

			for (ProvinceData o : new ArrayList<>(outposts)) {
				if (group.size() < 6 && dist(o, mansion) < 1600) {
					group.add(o);
					outposts.remove(o);
				}
			}

			makeNation(random, group, Faction.ILLAGER);
		}

		cluster(random, outposts, Faction.ILLAGER, 800, 3, 0.4);
		cluster(random, byType(ProvinceData.Type.BASTION), Faction.PIGLIN, 500, 3, 0.3);

		generated = true;
		JOBS.clear();
		FOUND.clear();
		save();
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
		broadcast(server);

		int nations = 0;

		for (NationData n : ServerNations.allNations()) {
			if (n.ai) {
				nations++;
			}
		}

		MapNationsMod.LOGGER.info("Map Nations WARS: {} provinces, {} nations", PROVINCES.size(), nations);

		for (ServerPlayer p : PlayerLookup.all(server)) {
			ServerNations.status(p, "The world is ready: " + PROVINCES.size() + " provinces and " + nations + " nations. Open the map (M)!", true);
		}
	}

	private static List<ProvinceData> byType(ProvinceData.Type type) {
		List<ProvinceData> list = new ArrayList<>();

		for (ProvinceData p : PROVINCES.values()) {
			if (p.type == type) {
				list.add(p);
			}
		}

		return list;
	}

	private static double dist(ProvinceData a, ProvinceData b) {
		return Math.hypot(a.x - b.x, a.z - b.z);
	}

	/** Groups provinces into nations: start at one, add the nearest free ones until the nation is big enough. */
	private static void cluster(Random random, List<ProvinceData> provinces, Faction faction, double reach, int maxSize, double aloneChance) {
		List<ProvinceData> free = new ArrayList<>(provinces);
		free.sort(Comparator.comparingDouble(p -> Math.hypot(p.x, p.z)));

		while (!free.isEmpty()) {
			ProvinceData capital = free.remove(0);
			List<ProvinceData> group = new ArrayList<>();
			group.add(capital);
			int target = random.nextDouble() < aloneChance ? 1 : 2 + random.nextInt(Math.max(1, maxSize - 1));
			free.sort(Comparator.comparingDouble(p -> dist(p, capital)));

			for (ProvinceData p : new ArrayList<>(free)) {
				if (group.size() >= target || dist(p, capital) > reach + 120 * group.size()) {
					break;
				}

				group.add(p);
				free.remove(p);
			}

			makeNation(random, group, faction);
		}
	}

	private static void makeNation(Random random, List<ProvinceData> group, Faction faction) {
		ProvinceData capital = group.get(0);
		NationData n = new NationData(new UUID(random.nextLong(), random.nextLong()));
		n.ai = true;
		n.faction = faction;
		n.ideology = pickIdeology(random, faction, group.size());
		n.name = Names.nation(random, faction, n.ideology, capital.name, group.size());
		n.color = Names.color(random, faction);
		n.leader = new UUID(random.nextLong(), random.nextLong()); // not a real player
		n.rulerName = faction == Faction.VILLAGER ? Names.person(random) : Names.warlord(random, capital.type);
		ServerNations.addGeneratedNation(n);

		for (ProvinceData p : group) {
			giveProvince(p, n.id);
			p.capital = p == capital;
		}
	}

	private static Ideology pickIdeology(Random random, Faction faction, int size) {
		Ideology[] options = switch (faction) {
			case VILLAGER -> size == 1
					? new Ideology[] {Ideology.AGRARIANISM, Ideology.LIBERTARIANISM, Ideology.ANARCHISM, Ideology.NEUTRAL}
					: new Ideology[] {Ideology.EMPIRE, Ideology.CONSERVATISM, Ideology.LIBERALISM, Ideology.THEOCRACY,
							Ideology.SOCIALISM, Ideology.TECHNOCRACY, Ideology.AGRARIANISM, Ideology.CONSERVATISM};
			case ILLAGER -> new Ideology[] {Ideology.FASCISM, Ideology.EMPIRE};
			case PIGLIN -> new Ideology[] {Ideology.EMPIRE, Ideology.ANARCHISM};
			case UNDEAD -> new Ideology[] {Ideology.THEOCRACY};
			default -> new Ideology[] {Ideology.NEUTRAL};
		};

		return options[random.nextInt(options.length)];
	}

	/** Every province gets the chunks around it; where two are close, each chunk goes to the nearer one. */
	private static void assignLand() {
		Map<String, Map<Long, ProvinceData>> owner = new HashMap<>();
		Map<String, Map<Long, Double>> best = new HashMap<>();

		for (ProvinceData p : PROVINCES.values()) {
			int r = switch (p.type) {
				case VILLAGE -> VILLAGE_RADIUS;
				case OUTPOST -> OUTPOST_RADIUS;
				case MANSION -> MANSION_RADIUS;
				case BASTION -> BASTION_RADIUS;
			};
			int cx = Math.floorDiv(p.x, 16);
			int cz = Math.floorDiv(p.z, 16);

			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (dx * dx + dz * dz > r * r + r) {
						continue; // round, not square
					}

					long key = MapNationsMod.chunkKey(cx + dx, cz + dz);
					double d = Math.hypot((cx + dx) * 16 + 8 - p.x, (cz + dz) * 16 + 8 - p.z);
					Map<Long, Double> dimBest = best.computeIfAbsent(p.dimension, k -> new HashMap<>());

					if (d < dimBest.getOrDefault(key, Double.MAX_VALUE)) {
						dimBest.put(key, d);
						owner.computeIfAbsent(p.dimension, k -> new HashMap<>()).put(key, p);
					}
				}
			}
		}

		for (Map<Long, ProvinceData> dimOwner : owner.values()) {
			for (Map.Entry<Long, ProvinceData> e : dimOwner.entrySet()) {
				e.getValue().area.add(e.getKey());
			}
		}

		for (ProvinceData p : PROVINCES.values()) {
			p.chunks = p.area.size();
		}
	}

	/** Hands a province (and all its land) to a nation. */
	private static void giveProvince(ProvinceData p, UUID nation) {
		p.nation = nation;

		for (long key : p.area) {
			ServerNations.setOwner(p.dimension, key, nation);
		}
	}

	// ---------------------------------------------------------------- living villages: population, mayors, abandoned villages

	private static void updateVillages(MinecraftServer server) {
		boolean changed = false;
		boolean nationsChanged = false;

		for (ProvinceData p : new ArrayList<>(PROVINCES.values())) {
			if (p.type != ProvinceData.Type.VILLAGE) {
				continue;
			}

			ServerLevel level = levelOf(server, p.dimension);

			if (level == null || !level.getChunkSource().hasChunk(Math.floorDiv(p.x, 16), Math.floorDiv(p.z, 16))) {
				continue; // nobody is near: keep the last known numbers
			}

			AABB box = new AABB(p.x - 56, level.getMinY(), p.z - 56, p.x + 56, level.getMinY() + level.getHeight(), p.z + 56);
			List<Villager> villagers = level.getEntitiesOfClass(Villager.class, box);
			int zombies = level.getEntitiesOfClass(ZombieVillager.class, box).size();

			if (villagers.size() != p.population) {
				p.population = villagers.size();
				changed = true;
			}

			// only zombies left: the undead take the village
			if (!p.abandoned && villagers.isEmpty() && zombies > 0) {
				p.abandoned = true;
				undeadTakeOver(p);
				changed = true;
				nationsChanged = true;
				continue;
			}

			if (p.abandoned || villagers.isEmpty()) {
				continue;
			}

			// the mayor is a real villager with a name tag
			UUID mayorId = MAYOR_ENTITY.get(p.id);
			Entity mayor = mayorId != null ? level.getEntity(mayorId) : null;

			if (mayor == null || !mayor.isAlive()) {
				if (mayorId != null) {
					p.mayorName = Names.person(new Random(p.id.getLeastSignificantBits() ^ System.nanoTime()));
				}

				Villager chosen = null;
				double bestDist = Double.MAX_VALUE;

				for (Villager v : villagers) {
					double d = Math.hypot(v.getX() - p.x, v.getZ() - p.z);

					if (!v.hasCustomName() && d < bestDist) {
						bestDist = d;
						chosen = v;
					}
				}

				if (chosen != null) {
					chosen.setCustomName(Component.literal("Mayor " + p.mayorName));
					chosen.setCustomNameVisible(true);
					MAYOR_ENTITY.put(p.id, chosen.getUUID());
					changed = true;
				}
			}
		}

		if (changed) {
			save();
			broadcast(server);
		}

		if (nationsChanged) {
			ServerNations.saveNow(server);
			ServerNations.broadcast(server);
		}
	}

	/** An abandoned village goes to the nearest undead horde (or starts a new one). */
	private static void undeadTakeOver(ProvinceData p) {
		UUID oldNation = p.nation;
		NationData horde = null;
		double bestDist = 2500;

		for (ProvinceData other : PROVINCES.values()) {
			if (other != p && other.abandoned && other.nation != null && other.dimension.equals(p.dimension)) {
				double d = dist(other, p);
				NationData n = ServerNations.nation(other.nation);

				if (n != null && n.faction == Faction.UNDEAD && d < bestDist) {
					bestDist = d;
					horde = n;
				}
			}
		}

		Random random = new Random(p.id.getMostSignificantBits());

		if (horde == null) {
			horde = new NationData(new UUID(random.nextLong(), random.nextLong()));
			horde.ai = true;
			horde.faction = Faction.UNDEAD;
			horde.ideology = Ideology.THEOCRACY;
			horde.name = "Undead Horde of " + p.name;
			horde.color = Names.color(random, Faction.UNDEAD);
			horde.leader = new UUID(random.nextLong(), random.nextLong());
			horde.rulerName = Names.warlord(random, p.type);
			ServerNations.addGeneratedNation(horde);
			p.capital = true;
		} else {
			p.capital = false;
		}

		p.mayorName = "";
		MAYOR_ENTITY.remove(p.id);
		giveProvince(p, horde.id);

		if (oldNation != null) {
			fixNationAfterLoss(oldNation);
		}
	}

	/** After a nation lost a province: new capital if needed, or the nation is gone if it has nothing left. */
	private static void fixNationAfterLoss(UUID nation) {
		ProvinceData biggest = null;
		boolean hasCapital = false;

		for (ProvinceData p : PROVINCES.values()) {
			if (nation.equals(p.nation)) {
				hasCapital |= p.capital;

				if (biggest == null || p.population > biggest.population) {
					biggest = p;
				}
			}
		}

		if (biggest == null) {
			ServerNations.removeGeneratedNation(nation);
		} else if (!hasCapital) {
			biggest.capital = true;
		}
	}

	private static ServerLevel levelOf(MinecraftServer server, String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().toString().equals(dimension)) {
				return level;
			}
		}

		return null;
	}

	// ---------------------------------------------------------------- sync and saving

	static void broadcast(MinecraftServer server) {
		ProvincesSyncPayload payload = new ProvincesSyncPayload(new ArrayList<>(PROVINCES.values()));

		for (ServerPlayer p : PlayerLookup.all(server)) {
			if (ServerPlayNetworking.canSend(p, ProvincesSyncPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	private static void load(MinecraftServer server) {
		PROVINCES.clear();
		MAYOR_ENTITY.clear();
		generated = false;
		file = server.getWorldPath(LevelResource.ROOT).resolve("mapnationswars_world.json");

		if (Files.exists(file)) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				generated = root.has("generated") && root.get("generated").getAsBoolean();

				for (JsonElement el : root.getAsJsonArray("provinces")) {
					JsonObject o = el.getAsJsonObject();
					ProvinceData p = new ProvinceData(UUID.fromString(o.get("id").getAsString()));
					p.name = o.get("name").getAsString();
					p.type = ProvinceData.Type.byName(o.get("type").getAsString());
					p.dimension = o.get("dimension").getAsString();
					p.x = o.get("x").getAsInt();
					p.z = o.get("z").getAsInt();
					p.nation = o.has("nation") ? UUID.fromString(o.get("nation").getAsString()) : null;
					p.mayorName = o.get("mayor").getAsString();
					p.population = o.get("population").getAsInt();
					p.capital = o.get("capital").getAsBoolean();
					p.abandoned = o.get("abandoned").getAsBoolean();

					if (o.has("economy")) {
						JsonObject eco = o.getAsJsonObject("economy");
						p.houses = eco.get("houses").getAsInt();
						p.farms = eco.get("farms").getAsInt();
						p.workshops = eco.get("workshops").getAsInt();
						p.food = eco.get("food").getAsInt();
						p.funds = eco.get("funds").getAsInt();
						p.happiness = eco.get("happiness").getAsInt();
						p.building = eco.get("building").getAsString();
						p.buildDays = eco.get("buildDays").getAsInt();
						p.lastFood = eco.get("lastFood").getAsInt();
						p.lastIncome = eco.get("lastIncome").getAsInt();
						p.lastTax = eco.get("lastTax").getAsInt();
						p.lastUpkeep = eco.get("lastUpkeep").getAsInt();
					} else {
						WarsEconomy.startingBuildings(p, new Random(p.id.getLeastSignificantBits()));
					}
					for (JsonElement c : o.getAsJsonArray("chunks")) {
						p.area.add(c.getAsLong());
					}

					p.chunks = p.area.size();
					PROVINCES.put(p.id, p);

					if (o.has("mayorEntity")) {
						MAYOR_ENTITY.put(p.id, UUID.fromString(o.get("mayorEntity").getAsString()));
					}
				}

				MapNationsMod.LOGGER.info("Map Nations WARS: loaded {} provinces", PROVINCES.size());
			} catch (Exception e) {
				MapNationsMod.LOGGER.error("Could not read {}", file, e);
			}
		}

		if (!generated) {
			startScan(server);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}

		JsonArray list = new JsonArray();

		for (ProvinceData p : PROVINCES.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", p.id.toString());
			o.addProperty("name", p.name);
			o.addProperty("type", p.type.name());
			o.addProperty("dimension", p.dimension);
			o.addProperty("x", p.x);
			o.addProperty("z", p.z);

			if (p.nation != null) {
				o.addProperty("nation", p.nation.toString());
			}

			o.addProperty("mayor", p.mayorName);
			o.addProperty("population", p.population);
			o.addProperty("capital", p.capital);
			o.addProperty("abandoned", p.abandoned);
			JsonObject eco = new JsonObject();
			eco.addProperty("houses", p.houses);
			eco.addProperty("farms", p.farms);
			eco.addProperty("workshops", p.workshops);
			eco.addProperty("food", p.food);
			eco.addProperty("funds", p.funds);
			eco.addProperty("happiness", p.happiness);
			eco.addProperty("building", p.building);
			eco.addProperty("buildDays", p.buildDays);
			eco.addProperty("lastFood", p.lastFood);
			eco.addProperty("lastIncome", p.lastIncome);
			eco.addProperty("lastTax", p.lastTax);
			eco.addProperty("lastUpkeep", p.lastUpkeep);
			o.add("economy", eco);
			JsonArray chunks = new JsonArray();

			for (long c : p.area) {
				chunks.add(c);
			}

			o.add("chunks", chunks);
			UUID mayor = MAYOR_ENTITY.get(p.id);

			if (mayor != null) {
				o.addProperty("mayorEntity", mayor.toString());
			}

			list.add(o);
		}

		JsonObject root = new JsonObject();
		root.addProperty("generated", generated);
		root.add("provinces", list);

		try {
			Path tmp = file.resolveSibling("mapnationswars_world.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			MapNationsMod.LOGGER.error("Could not save {}", file, e);
		}
	}
}
