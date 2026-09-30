package com.mapnationswars.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.LevelResource;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.network.TerrainRequestPayload;
import com.mapnationswars.network.TerrainTilesPayload;

/**
 * Remembers the terrain you have explored, plus the "preview" of land you haven't
 * visited yet, which the server paints from the world generator.
 * Every tick it looks at a few loaded chunks around you and paints their top blocks
 * into 256x256 "regions". Regions are saved to .minecraft/mapnationswars/... so the map
 * is still there next time you play.
 */
public final class MapData {
	/** What you have really seen. */
	private static final RegionStore EXPLORED = new RegionStore();
	/** Land you have not visited yet, painted by the server from the world generator. */
	private static final RegionStore PREVIEW = new RegionStore();
	private static final Map<Long, Long> LAST_SCANNED = new HashMap<>();
	/** Preview chunks asked for but not received yet (chunk key -> time asked). */
	private static final Map<Long, Long> PENDING = new HashMap<>();
	private static final int MAX_PENDING = 768;

	/** e.g. "sp_My_World/minecraft_overworld" – which world + dimension is loaded. */
	private static String worldKey = null;
	private static Path folder = null;
	/** Which world (seed) the server says this is; 0 = not known yet. */
	private static long worldId = 0;
	private static long lastSaveTime = 0;
	private static int uploadsThisFrame = 0;

	private MapData() {
	}

	// ---------------------------------------------------------------- tick

	static void tick(Minecraft mc) {
		ClientLevel level = mc.level;

		if (level == null || mc.player == null) {
			if (worldKey != null) {
				unloadAll(mc);
			}

			return;
		}

		String key = computeWorldKey(mc, level);

		if (!key.equals(worldKey)) {
			unloadAll(mc);
			worldKey = key;
			Path root = FabricLoader.getInstance().getGameDir().resolve("mapnationswars");
			folder = root.resolve(key);
			EXPLORED.folder = folder;
			PREVIEW.folder = worldId != 0 ? folder.resolve("preview-" + Long.toHexString(worldId)) : null;
		}

		scanAroundPlayer(mc, level);

		long now = System.currentTimeMillis();

		if (now - lastSaveTime > 30_000) {
			lastSaveTime = now;
			saveAll();
		}
	}

	private static String computeWorldKey(Minecraft mc, ClientLevel level) {
		String world;
		IntegratedServer sp = mc.getSingleplayerServer();

		if (sp != null) {
			Path root = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path name = root.getFileName();
			world = "sp_" + (name != null ? name.toString() : "world");
		} else {
			ServerData server = mc.getCurrentServer();
			world = "mp_" + (server != null ? server.ip : "unknown");
		}

		String dim = level.dimension().identifier().toString();
		return safe(world) + "/" + safe(dim);
	}

	private static String safe(String s) {
		return s.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	// ---------------------------------------------------------------- scanning

	private static void scanAroundPlayer(Minecraft mc, ClientLevel level) {
		ClientChunkCache chunks = level.getChunkSource();
		boolean nether = level.dimension() == Level.NETHER;
		int playerChunkX = Mth.floor(mc.player.getX()) >> 4;
		int playerChunkZ = Mth.floor(mc.player.getZ()) >> 4;
		int radius = Math.min(mc.options.renderDistance().get(), 32);
		int budget = nether ? 4 : 12; // chunks per tick, keeps the game smooth
		long now = System.currentTimeMillis();

		outer:
		for (int r = 0; r <= radius; r++) {
			// chunks near you refresh quickly, far ones slowly
			long refreshAfter = r <= 2 ? 2_000 : 20_000;

			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue; // only the ring at distance r
					}

					int cx = playerChunkX + dx;
					int cz = playerChunkZ + dz;
					long ck = MapNationsMod.chunkKey(cx, cz);
					Long last = LAST_SCANNED.get(ck);

					if (last != null && now - last < refreshAfter) {
						continue;
					}

					LevelChunk chunk = chunks.getChunk(cx, cz, ChunkStatus.FULL, false);

					if (chunk == null) {
						continue;
					}

					LevelChunk northChunk = chunks.getChunk(cx, cz - 1, ChunkStatus.FULL, false);
					scanChunk(level, chunk, northChunk, cx, cz, nether);
					LAST_SCANNED.put(ck, now);

					if (--budget <= 0) {
						break outer;
					}
				}
			}
		}
	}

	private static void scanChunk(ClientLevel level, LevelChunk chunk, LevelChunk northChunk, int cx, int cz, boolean nether) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minY = level.getMinY();
		int[] heights = new int[16 * 16];

		MapRegion region = getOrCreateRegion(cx >> 4, cz >> 4);
		int baseX = (cx & 15) << 4;
		int baseZ = (cz & 15) << 4;

		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int wx = (cx << 4) + lx;
				int wz = (cz << 4) + lz;
				int y = nether ? netherSurface(chunk, pos, wx, wz, minY) : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);

				int rgb = 0;
				int waterDepth = 0;

				while (y >= minY) {
					pos.set(wx, y, wz);
					BlockState state = chunk.getBlockState(pos);
					MapColor mapColor = state.getMapColor(level, pos);

					if (mapColor.col != 0) {
						rgb = mapColor.col;

						if (mapColor == MapColor.WATER) {
							waterDepth = waterDepth(chunk, level, pos, wx, y, wz, minY);
						}

						break;
					}

					y--; // invisible block (air, glass...), look further down
				}

				heights[lz * 16 + lx] = y;

				if (y < minY) {
					region.setPixel(baseX + lx, baseZ + lz, 0xFF000000); // void
					continue;
				}

				// Shading like vanilla maps: brighter when higher than the block to the north.
				int shade; // 0 = dark, 1 = normal, 2 = bright

				if (waterDepth > 0) {
					shade = waterDepth <= 2 ? 2 : (waterDepth <= 6 ? 1 : 0);
				} else {
					int northY;

					if (lz > 0) {
						northY = heights[(lz - 1) * 16 + lx];
					} else if (northChunk != null && !nether) {
						northY = northChunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, 15);
					} else {
						northY = y;
					}

					shade = y > northY ? 2 : (y < northY ? 0 : 1);
				}

				region.setPixel(baseX + lx, baseZ + lz, 0xFF000000 | applyShade(rgb, shade));
			}
		}
	}

	private static int waterDepth(LevelChunk chunk, ClientLevel level, BlockPos.MutableBlockPos pos, int wx, int topY, int wz, int minY) {
		int depth = 1;

		for (int y = topY - 1; y >= minY && depth < 12; y--) {
			pos.set(wx, y, wz);

			if (chunk.getBlockState(pos).getMapColor(level, pos) != MapColor.WATER) {
				break;
			}

			depth++;
		}

		pos.set(wx, topY, wz);
		return depth;
	}

	/** In the Nether the top block is the bedrock roof, so find the first floor under open air instead. */
	private static int netherSurface(LevelChunk chunk, BlockPos.MutableBlockPos pos, int wx, int wz, int minY) {
		boolean foundAir = false;

		for (int y = 120; y >= minY; y--) {
			pos.set(wx, y, wz);
			boolean air = chunk.getBlockState(pos).isAir();

			if (air) {
				foundAir = true;
			} else if (foundAir) {
				return y;
			}
		}

		return minY - 1;
	}

	private static int applyShade(int rgb, int shade) {
		int mul = shade == 2 ? 255 : (shade == 1 ? 220 : 180);
		int r = ((rgb >> 16) & 0xFF) * mul / 255;
		int g = ((rgb >> 8) & 0xFF) * mul / 255;
		int b = (rgb & 0xFF) * mul / 255;
		return (r << 16) | (g << 8) | b;
	}

	// ---------------------------------------------------------------- regions

	private static MapRegion getOrCreateRegion(int rx, int rz) {
		return EXPLORED.getOrCreate(rx, rz);
	}

	/** Returns the explored region from memory or disk, or null if it was never explored. */
	static MapRegion getRegion(int rx, int rz) {
		return EXPLORED.get(rx, rz);
	}

	/** Returns the preview region (land painted by the server), or null. */
	static MapRegion getPreviewRegion(int rx, int rz) {
		return PREVIEW.get(rx, rz);
	}

	/** Has this block column been seen (drawn into the map) yet? */
	static boolean isExplored(int x, int z) {
		return pixelAt(EXPLORED, x, z) != 0;
	}

	/** Is anything shown on the map here (explored, or painted by the server)? */
	static boolean isKnown(int x, int z) {
		return isExplored(x, z) || pixelAt(PREVIEW, x, z) != 0;
	}

	private static int pixelAt(RegionStore store, int x, int z) {
		MapRegion region = store.get(Math.floorDiv(x, MapRegion.SIZE), Math.floorDiv(z, MapRegion.SIZE));
		return region == null ? 0 : region.pixels[Math.floorMod(z, MapRegion.SIZE) * MapRegion.SIZE + Math.floorMod(x, MapRegion.SIZE)];
	}

	// ---------------------------------------------------------------- terrain preview from the server

	/** Left the server: forget which world it was. */
	static void onDisconnect() {
		worldId = 0;
		PENDING.clear();
	}

	/** The server told us which world this is (or sent pictures of chunks). */
	static void applyTiles(Minecraft mc, TerrainTilesPayload payload) {
		if (payload.worldId() != worldId) {
			PREVIEW.saveAll();
			PREVIEW.clear(mc);
			PENDING.clear();
			worldId = payload.worldId();
			PREVIEW.folder = folder != null ? folder.resolve("preview-" + Long.toHexString(worldId)) : null;
		}

		if (payload.chunks().length == 0 || mc.level == null || !payload.dimension().equals(mc.level.dimension().identifier().toString())) {
			return;
		}

		byte[] rgb = payload.rgb();

		for (int i = 0; i < payload.chunks().length; i++) {
			long key = payload.chunks()[i];
			PENDING.remove(key);
			int cx = MapNationsMod.keyX(key);
			int cz = MapNationsMod.keyZ(key);
			MapRegion region = PREVIEW.getOrCreate(cx >> 4, cz >> 4);
			int baseX = (cx & 15) << 4;
			int baseZ = (cz & 15) << 4;
			int o = i * TerrainTilesPayload.BYTES_PER_CHUNK;

			for (int p = 0; p < 256; p++) {
				int color = 0xFF000000 | ((rgb[o] & 0xFF) << 16) | ((rgb[o + 1] & 0xFF) << 8) | (rgb[o + 2] & 0xFF);
				o += 3;
				region.setPixel(baseX + (p & 15), baseZ + (p >> 4), color);
			}
		}
	}

	/**
	 * Asks the server for pictures of chunks around the middle of the map view that are not shown yet.
	 * minX..maxZ is the visible area in chunks.
	 */
	static void requestPreview(int centerCX, int centerCZ, int minCX, int minCZ, int maxCX, int maxCZ, int maxRadius) {
		if (worldId == 0 || !ClientPlayNetworking.canSend(TerrainRequestPayload.TYPE)) {
			return;
		}

		long now = System.currentTimeMillis();
		PENDING.values().removeIf(t -> now - t > 20_000); // lost? ask again later

		int budget = Math.min(TerrainRequestPayload.MAX_CHUNKS, MAX_PENDING - PENDING.size());

		if (budget <= 0) {
			return;
		}

		List<Long> wanted = new ArrayList<>();
		int radius = Math.min(maxRadius, Math.max(Math.max(centerCX - minCX, maxCX - centerCX), Math.max(centerCZ - minCZ, maxCZ - centerCZ)));

		outer:
		for (int r = 0; r <= radius; r++) {
			for (int dz = -r; dz <= r; dz++) {
				int step = Math.abs(dz) == r ? 1 : 2 * r; // whole row at the top and bottom, only the two ends in between

				for (int dx = -r; dx <= r; dx += Math.max(1, step)) {
					int cx = centerCX + dx;
					int cz = centerCZ + dz;

					if (cx < minCX || cx > maxCX || cz < minCZ || cz > maxCZ) {
						continue;
					}

					long key = MapNationsMod.chunkKey(cx, cz);

					if (PENDING.containsKey(key) || isKnown((cx << 4) + 8, (cz << 4) + 8)) {
						continue;
					}

					PENDING.put(key, now);
					wanted.add(key);

					if (wanted.size() >= budget) {
						break outer;
					}
				}
			}
		}

		if (!wanted.isEmpty()) {
			ClientPlayNetworking.send(new TerrainRequestPayload(wanted));
		}
	}

	/** Called by the map screen once per frame; limits texture uploads so the screen never freezes. */
	static void beginFrame() {
		uploadsThisFrame = 0;
	}

	static boolean tryUseUpload() {
		if (uploadsThisFrame < 8) {
			uploadsThisFrame++;
			return true;
		}

		return false;
	}

	// ---------------------------------------------------------------- disk

	private static void saveAll() {
		EXPLORED.saveAll();
		PREVIEW.saveAll();
	}

	private static void unloadAll(Minecraft mc) {
		saveAll();
		EXPLORED.clear(mc);
		PREVIEW.clear(mc);
		EXPLORED.folder = null;
		PREVIEW.folder = null;
		LAST_SCANNED.clear();
		PENDING.clear();
		worldKey = null;
		folder = null;
	}


	/**
	 * The world border as a box {minX, minZ, maxX, maxZ} in blocks (max is exclusive),
	 * or null if there is no usable border here.
	 */
	static int[] borderBox(ClientLevel level, double playerX, double playerZ) {
		net.minecraft.world.level.border.WorldBorder border = level.getWorldBorder();
		int ax = Mth.floor(playerX);
		int az = Mth.floor(playerZ);

		if (!border.isWithinBounds(new BlockPos(ax, 0, az))) {
			ax = 0;
			az = 0;

			if (!border.isWithinBounds(new BlockPos(0, 0, 0))) {
				return null;
			}
		}

		return new int[] {
			findEdge(border, ax, az, -1, 0),
			findEdge(border, ax, az, 0, -1),
			findEdge(border, ax, az, 1, 0) + 1,
			findEdge(border, ax, az, 0, 1) + 1
		};
	}

	/** Binary search for the last block inside the border going in direction (dx, dz). */
	static int findEdge(net.minecraft.world.level.border.WorldBorder border, int startX, int startZ, int dx, int dz) {
		int inside = 0;
		int outside = 60_000_001;

		while (outside - inside > 1) {
			int mid = inside + (outside - inside) / 2;

			if (border.isWithinBounds(new BlockPos(startX + dx * mid, 0, startZ + dz * mid))) {
				inside = mid;
			} else {
				outside = mid;
			}
		}

		return dx != 0 ? startX + dx * inside : startZ + dz * inside;
	}

	/** Regions kept in memory, loaded from / saved to one folder. */
	private static final class RegionStore {
		final Map<Long, MapRegion> regions = new HashMap<>();
		final Set<Long> notOnDisk = new HashSet<>();
		Path folder;

		MapRegion get(int rx, int rz) {
			long key = MapNationsMod.chunkKey(rx, rz);
			MapRegion region = this.regions.get(key);

			if (region != null || this.notOnDisk.contains(key)) {
				return region;
			}

			region = this.load(rx, rz);

			if (region != null) {
				this.regions.put(key, region);
			} else {
				this.notOnDisk.add(key);
			}

			return region;
		}

		MapRegion getOrCreate(int rx, int rz) {
			MapRegion region = this.get(rx, rz);

			if (region == null) {
				region = new MapRegion(rx, rz);
				this.regions.put(MapNationsMod.chunkKey(rx, rz), region);
			}

			return region;
		}

		private Path file(int rx, int rz) {
			return this.folder.resolve("r." + rx + "." + rz + ".bin");
		}

		private MapRegion load(int rx, int rz) {
			if (this.folder == null) {
				return null;
			}

			Path file = this.file(rx, rz);

			if (!Files.exists(file)) {
				return null;
			}

			try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
				MapRegion region = new MapRegion(rx, rz);

				for (int i = 0; i < region.pixels.length; i++) {
					region.pixels[i] = in.readInt();
				}

				return region;
			} catch (IOException e) {
				MapNationsMod.LOGGER.warn("Could not read map file {}", file, e);
				return null;
			}
		}

		void saveAll() {
			if (this.folder == null) {
				return;
			}

			for (MapRegion region : this.regions.values()) {
				if (!region.unsaved) {
					continue;
				}

				Path file = this.file(region.regionX, region.regionZ);

				try {
					Files.createDirectories(this.folder);

					try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))))) {
						for (int p : region.pixels) {
							out.writeInt(p);
						}
					}

					region.unsaved = false;
				} catch (IOException e) {
					MapNationsMod.LOGGER.warn("Could not save map file {}", file, e);
				}
			}
		}

		void clear(Minecraft mc) {
			for (MapRegion region : this.regions.values()) {
				region.releaseTexture(mc);
			}

			this.regions.clear();
			this.notOnDisk.clear();
		}
	}
}
