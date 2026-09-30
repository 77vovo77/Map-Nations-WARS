package com.mapnationswars;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Counts villagers in claimed land and inside settlement borders.
 * Only loaded chunks can be counted; chunks nobody is near keep their last known count.
 */
public final class ServerPopulation {
	private static final int INTERVAL_TICKS = 200; // every 10 seconds

	/** dimension -> (chunk key -> villagers last seen there) */
	private static final Map<String, Map<Long, Integer>> KNOWN = new HashMap<>();
	private static int ticks = 0;

	private ServerPopulation() {
	}

	static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % INTERVAL_TICKS == 0) {
				update(server);
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> KNOWN.clear());
	}

	/** Villagers last counted in one chunk. */
	static int at(String dimension, long chunkKey) {
		Map<Long, Integer> map = KNOWN.get(dimension);
		Integer count = map == null ? null : map.get(chunkKey);
		return count != null ? count : 0;
	}

	private static void update(MinecraftServer server) {
		boolean changed = false;

		for (ServerLevel level : server.getAllLevels()) {
			String dim = level.dimension().identifier().toString();
			Set<Long> interesting = new HashSet<>(ServerNations.claimedChunks(dim));
			interesting.addAll(ServerMarkers.areaChunks(dim));

			if (interesting.isEmpty()) {
				continue;
			}

			Map<Long, Integer> fresh = new HashMap<>();

			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof Villager) {
					long key = MapNationsMod.chunkKey(Mth.floor(entity.getX()) >> 4, Mth.floor(entity.getZ()) >> 4);
					fresh.merge(key, 1, Integer::sum);
				}
			}

			Map<Long, Integer> known = KNOWN.computeIfAbsent(dim, d -> new HashMap<>());

			for (long key : interesting) {
				if (!level.getChunkSource().hasChunk(MapNationsMod.keyX(key), MapNationsMod.keyZ(key))) {
					continue; // not loaded: keep the old count
				}

				int count = fresh.getOrDefault(key, 0);
				Integer old = known.put(key, count);

				if (old == null ? count != 0 : old != count) {
					changed = true;
				}
			}
		}

		if (changed) {
			ServerNations.broadcast(server);
		}
	}
}
