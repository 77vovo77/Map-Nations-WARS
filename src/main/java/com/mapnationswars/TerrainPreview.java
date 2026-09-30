package com.mapnationswars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.material.MapColor;

import com.mapnationswars.network.TerrainRequestPayload;
import com.mapnationswars.network.TerrainTilesPayload;

/**
 * Lets the map show terrain you have never visited.
 * The server asks the world generator what the land looks like (height + biome) without
 * actually generating the chunks, paints a small picture of each chunk and sends it to the client.
 * This runs on background threads, so the server doesn't lag.
 */
public final class TerrainPreview {
	/** Change this when the painting changes, so clients throw away their old saved pictures. */
	public static final int STYLE_VERSION = 1;
	/** Most chunks one player may have waiting at once. */
	private static final int MAX_QUEUED_PER_PLAYER = 2048;
	/** Chunks further than this from 0,0 are never painted. */
	private static final int MAX_COORD = 1_875_000;
	private static final int CACHE_SIZE = 16_384;

	private record Job(UUID player, ServerLevel level, String dimension, long key) {
	}

	private record Result(UUID player, String dimension, long key, int[] pixels) {
	}

	private record CacheKey(String dimension, long key) {
	}

	private static final LinkedBlockingDeque<Job> JOBS = new LinkedBlockingDeque<>();
	private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();
	private static final Map<UUID, AtomicInteger> QUEUED = new ConcurrentHashMap<>();
	private static final Map<CacheKey, int[]> CACHE = new LinkedHashMap<>(1024, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<CacheKey, int[]> eldest) {
			return this.size() > CACHE_SIZE;
		}
	};

	private static volatile boolean workersStarted = false;
	private static volatile boolean loggedError = false;

	private TerrainPreview() {
	}

	static void init() {
		ServerPlayNetworking.registerGlobalReceiver(TerrainRequestPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.level().getServer();

			if (server != null) {
				server.execute(() -> request(player, payload));
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (ServerPlayNetworking.canSend(handler.player, TerrainTilesPayload.TYPE)) {
				// tell the client which world this is (so it can load its saved pictures)
				sender.sendPacket(new TerrainTilesPayload("", worldId(server), new long[0], new byte[0]));
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> QUEUED.remove(handler.player.getUUID()));

		ServerTickEvents.END_SERVER_TICK.register(TerrainPreview::sendResults);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			JOBS.clear();
			RESULTS.clear();
			QUEUED.clear();

			synchronized (CACHE) {
				CACHE.clear();
			}
		});
	}

	/** A number that is different for every world seed (it doesn't reveal the seed itself). */
	static long worldId(MinecraftServer server) {
		long seed = server.overworld().getSeed();
		return new SplittableRandom(seed ^ 0x5DEECE66DL).nextLong() ^ STYLE_VERSION;
	}

	// ---------------------------------------------------------------- requests

	private static void request(ServerPlayer player, TerrainRequestPayload payload) {
		startWorkers();

		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		String dim = level.dimension().identifier().toString();
		AtomicInteger queued = QUEUED.computeIfAbsent(player.getUUID(), u -> new AtomicInteger());

		for (long key : payload.chunks()) {
			int cx = MapNationsMod.keyX(key);
			int cz = MapNationsMod.keyZ(key);

			if (Math.abs(cx) > MAX_COORD || Math.abs(cz) > MAX_COORD || queued.get() >= MAX_QUEUED_PER_PLAYER) {
				continue;
			}

			if (!touchesBorder(level, cx, cz)) {
				continue; // outside the world border: only exploring shows it
			}

			int[] cached;

			synchronized (CACHE) {
				cached = CACHE.get(new CacheKey(dim, key));
			}

			if (cached != null) {
				RESULTS.add(new Result(player.getUUID(), dim, key, cached));
			} else {
				queued.incrementAndGet();
				JOBS.add(new Job(player.getUUID(), level, dim, key));
			}
		}
	}

	/** True if any part of the chunk is inside the world border. */
	private static boolean touchesBorder(ServerLevel level, int cx, int cz) {
		net.minecraft.world.level.border.WorldBorder border = level.getWorldBorder();
		int x = cx << 4;
		int z = cz << 4;
		int[][] points = {{x, z}, {x + 15, z}, {x, z + 15}, {x + 15, z + 15}, {x + 8, z + 8}};

		for (int[] p : points) {
			if (border.isWithinBounds(new net.minecraft.core.BlockPos(p[0], 0, p[1]))) {
				return true;
			}
		}

		return false;
	}

	private static synchronized void startWorkers() {
		if (workersStarted) {
			return;
		}

		workersStarted = true;
		int threads = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 3));

		for (int i = 0; i < threads; i++) {
			Thread t = new Thread(TerrainPreview::workLoop, "Map Nations WARS terrain preview " + (i + 1));
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			t.start();
		}
	}

	private static void workLoop() {
		while (true) {
			Job job;

			try {
				job = JOBS.take();
			} catch (InterruptedException e) {
				return;
			}

			AtomicInteger queued = QUEUED.get(job.player());

			if (queued == null) {
				continue; // player left
			}

			queued.decrementAndGet();
			int[] pixels = null;

			try {
				pixels = paint(job.level(), MapNationsMod.keyX(job.key()), MapNationsMod.keyZ(job.key()));
			} catch (Throwable e) {
				if (!loggedError) {
					loggedError = true;
					MapNationsMod.LOGGER.warn("Could not paint the terrain preview (the map will only show explored land)", e);
				}
			}

			if (pixels != null) {
				synchronized (CACHE) {
					CACHE.put(new CacheKey(job.dimension(), job.key()), pixels);
				}

				RESULTS.add(new Result(job.player(), job.dimension(), job.key(), pixels));
			}
		}
	}

	private static void sendResults(MinecraftServer server) {
		if (RESULTS.isEmpty()) {
			return;
		}

		Map<UUID, List<Result>> perPlayer = new HashMap<>();
		int budget = 1024;
		Result r;

		while (budget-- > 0 && (r = RESULTS.poll()) != null) {
			perPlayer.computeIfAbsent(r.player(), u -> new ArrayList<>()).add(r);
		}

		long worldId = worldId(server);

		for (Map.Entry<UUID, List<Result>> entry : perPlayer.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());

			if (player == null || !ServerPlayNetworking.canSend(player, TerrainTilesPayload.TYPE)) {
				continue;
			}

			// group by dimension, then send in packets of up to MAX_CHUNKS
			Map<String, List<Result>> perDim = new HashMap<>();

			for (Result res : entry.getValue()) {
				perDim.computeIfAbsent(res.dimension(), d -> new ArrayList<>()).add(res);
			}

			for (Map.Entry<String, List<Result>> dimEntry : perDim.entrySet()) {
				List<Result> list = dimEntry.getValue();

				for (int start = 0; start < list.size(); start += TerrainTilesPayload.MAX_CHUNKS) {
					int n = Math.min(TerrainTilesPayload.MAX_CHUNKS, list.size() - start);
					long[] keys = new long[n];
					byte[] rgb = new byte[n * TerrainTilesPayload.BYTES_PER_CHUNK];

					for (int i = 0; i < n; i++) {
						Result res = list.get(start + i);
						keys[i] = res.key();
						int o = i * TerrainTilesPayload.BYTES_PER_CHUNK;

						for (int p = 0; p < 256; p++) {
							int c = res.pixels()[p];
							rgb[o++] = (byte) (c >> 16);
							rgb[o++] = (byte) (c >> 8);
							rgb[o++] = (byte) c;
						}
					}

					ServerPlayNetworking.send(player, new TerrainTilesPayload(dimEntry.getKey(), worldId, keys, rgb));
				}
			}
		}
	}

	// ---------------------------------------------------------------- painting

	/** Paints one chunk (16 x 16 RGB colours) from the world generator. */
	static int[] paint(ServerLevel level, int cx, int cz) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		RandomState random = level.getChunkSource().randomState();
		BiomeResolver biomes = level.uncachedBiomeResolver();
		boolean nether = level.dimension() == Level.NETHER;
		boolean end = level.dimension() == Level.END;
		int sea = generator.getSeaLevel();
		int minY = level.getMinY();
		int x0 = cx << 4;
		int z0 = cz << 4;

		// ground height every 4 blocks (5 x 5 points, including the chunk's far edges)
		int[] h = new int[25];

		for (int j = 0; j < 5; j++) {
			for (int i = 0; i < 5; i++) {
				h[j * 5 + i] = nether ? 64 : generator.getBaseHeight(x0 + i * 4, z0 + j * 4, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
			}
		}

		// biome of every 4 x 4 cell
		String[] cellBiome = new String[16];

		for (int j = 0; j < 4; j++) {
			for (int i = 0; i < 4; i++) {
				int y = nether ? 64 : Math.max(Math.max(h[j * 5 + i], sea), minY);
				Holder<Biome> biome = biomes.getNoiseBiome((x0 >> 2) + i, y >> 2, (z0 >> 2) + j);
				cellBiome[j * 4 + i] = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
			}
		}

		int[] out = new int[256];

		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int i = lx >> 2;
				int j = lz >> 2;
				double tx = (lx & 3) / 4.0;
				double tz = (lz & 3) / 4.0;
				int a = h[j * 5 + i];
				int b = h[j * 5 + i + 1];
				int c = h[(j + 1) * 5 + i];
				int d = h[(j + 1) * 5 + i + 1];
				double height = (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
				double slopeZ = ((c - a) * (1 - tx) + (d - b) * tx) / 4.0;
				double slopeX = ((b - a) * (1 - tz) + (d - c) * tz) / 4.0;
				int wx = x0 + lx;
				int wz = z0 + lz;
				int checker = (lx + lz) & 1;
				String biome = cellBiome[j * 4 + i];

				if (end && height <= minY + 1) {
					out[lz * 16 + lx] = 0x000000; // the void
					continue;
				}

				if (!nether && !end && height < sea - 0.5) {
					// water: brighter when shallow, like vanilla maps
					double depth = sea - height;
					double f = depth * 0.1 + checker * 0.2;
					int shade = f < 0.5 ? 2 : (f > 0.9 ? 0 : 1);
					int water = biome.contains("frozen") && depth < 12 ? MapColor.ICE.col : MapColor.WATER.col;
					out[lz * 16 + lx] = shade(water, shade);
					continue;
				}

				int color = landColor(biome, wx, wz, height, Math.max(Math.abs(slopeX), Math.abs(slopeZ)), nether, end);
				int shade;

				if (nether) {
					double n = noise(wx >> 1, wz >> 1);
					shade = n < 0.3 ? 0 : (n > 0.7 ? 2 : 1);
				} else {
					double s = slopeZ + (checker - 0.5) * 0.4;
					shade = s > 0.6 ? 2 : (s < -0.6 ? 0 : 1);
				}

				out[lz * 16 + lx] = shade(color, shade);
			}
		}

		return out;
	}

	/** Colour of the ground for a biome (the same colours real Minecraft maps use). */
	private static int landColor(String biome, int x, int z, double height, double steepness, boolean nether, boolean end) {
		double n = noise(x >> 1, z >> 1); // 2x2 block clumps, look like tree tops
		double fine = noise(x, z);

		if (end) {
			return MapColor.SAND.col; // end stone
		}

		if (nether) {
			if (biome.contains("crimson")) {
				return n < 0.25 ? MapColor.NETHER.col : MapColor.CRIMSON_NYLIUM.col;
			}

			if (biome.contains("warped")) {
				return n < 0.2 ? MapColor.NETHER.col : MapColor.WARPED_NYLIUM.col;
			}

			if (biome.contains("soul")) {
				return MapColor.COLOR_BROWN.col;
			}

			if (biome.contains("basalt")) {
				return n < 0.5 ? MapColor.COLOR_BLACK.col : MapColor.COLOR_GRAY.col;
			}

			return MapColor.NETHER.col;
		}

		boolean snowy = biome.contains("snow") || biome.contains("frozen") || biome.contains("ice") || biome.contains("grove")
				|| biome.contains("jagged") || biome.contains("peaks") && !biome.contains("stony");

		// very steep slopes are bare rock
		if (steepness > 1.6 && !snowy && !biome.contains("badlands")) {
			return fine < 0.8 ? MapColor.STONE.col : MapColor.GRASS.col;
		}

		if (biome.contains("beach") || biome.contains("desert")) {
			return biome.contains("snowy") ? MapColor.SNOW.col : MapColor.SAND.col;
		}

		if (biome.contains("stony") || biome.contains("gravelly")) {
			return MapColor.STONE.col;
		}

		if (biome.contains("ocean") || biome.contains("river")) {
			return MapColor.SAND.col; // shores above the water
		}

		if (biome.contains("badlands")) {
			if (biome.contains("wooded") && height > 95) {
				return n < 0.4 ? MapColor.PLANT.col : MapColor.GRASS.col;
			}

			double band = Math.floorMod((int) Math.round(height), 12);
			return band < 3 ? MapColor.TERRACOTTA_WHITE.col : band < 7 ? MapColor.TERRACOTTA_ORANGE.col : MapColor.COLOR_ORANGE.col;
		}

		if (biome.contains("mushroom")) {
			return MapColor.COLOR_PURPLE.col;
		}

		if (biome.contains("ice_spikes")) {
			return fine < 0.25 ? MapColor.ICE.col : MapColor.SNOW.col;
		}

		if (biome.contains("taiga") && snowy) {
			return n < 0.45 ? MapColor.PLANT.col : MapColor.SNOW.col;
		}

		if (snowy) {
			return MapColor.SNOW.col;
		}

		if (biome.contains("cherry")) {
			return n < 0.65 ? MapColor.COLOR_PINK.col : MapColor.GRASS.col;
		}

		if (biome.contains("pale")) {
			return n < 0.8 ? MapColor.COLOR_LIGHT_GRAY.col : MapColor.GRASS.col;
		}

		if (biome.contains("dark_forest") || biome.contains("jungle") || biome.contains("mangrove")) {
			return n < 0.9 ? MapColor.PLANT.col : MapColor.GRASS.col;
		}

		if (biome.contains("swamp")) {
			return n < 0.35 ? MapColor.PLANT.col : (fine < 0.15 ? MapColor.WATER.col : MapColor.GRASS.col);
		}

		if (biome.contains("forest") || biome.contains("taiga") || biome.contains("woodland")) {
			boolean podzol = biome.contains("old_growth") && fine < 0.15;
			return n < 0.7 ? MapColor.PLANT.col : (podzol ? MapColor.PODZOL.col : MapColor.GRASS.col);
		}

		if (biome.contains("savanna")) {
			return n < 0.12 ? MapColor.PLANT.col : MapColor.GRASS.col;
		}

		if (biome.contains("windswept")) {
			return n < 0.3 ? MapColor.STONE.col : (n > 0.85 ? MapColor.PLANT.col : MapColor.GRASS.col);
		}

		// plains, meadow, sunflower plains and anything unknown (modded biomes)
		return n < 0.04 ? MapColor.PLANT.col : MapColor.GRASS.col;
	}

	/** Same shading as MapData on the client: 0 = dark, 1 = normal, 2 = bright. */
	private static int shade(int rgb, int shade) {
		int mul = shade == 2 ? 255 : (shade == 1 ? 220 : 180);
		int r = ((rgb >> 16) & 0xFF) * mul / 255;
		int g = ((rgb >> 8) & 0xFF) * mul / 255;
		int b = (rgb & 0xFF) * mul / 255;
		return (r << 16) | (g << 8) | b;
	}

	/** A fixed "random" number 0..1 for a position (always the same for the same place). */
	private static double noise(int x, int z) {
		int n = x * 73856093 ^ z * 19349663;
		n = (n ^ (n >>> 13)) * 0x5BD1E995;
		n ^= n >>> 15;
		return (n & 0xFFFF) / 65536.0;
	}
}
