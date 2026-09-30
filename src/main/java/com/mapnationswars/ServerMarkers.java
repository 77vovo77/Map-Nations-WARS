package com.mapnationswars;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import com.mapnationswars.nation.MarkerData;
import com.mapnationswars.nation.MarkerType;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.network.MarkerActionPayload;
import com.mapnationswars.network.MarkerAreaPayload;
import com.mapnationswars.network.MarkersSyncPayload;

/**
 * Map markers. Three kinds of visibility:
 * "Only me" (private), "My nation" (only members of the owner's nation) and "Everyone".
 * Saved in the world folder as mapnationswars_markers.json.
 */
public final class ServerMarkers {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, MarkerData> MARKERS = new LinkedHashMap<>();
	private static final Map<UUID, Long> LAST_PLACED = new HashMap<>();
	private static Path file;

	private ServerMarkers() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(ServerMarkers::load);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save();
			MARKERS.clear();
			LAST_PLACED.clear();
			file = null;
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (ServerPlayNetworking.canSend(handler.player, MarkersSyncPayload.TYPE)) {
				sender.sendPacket(buildFor(handler.player));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(MarkerAreaPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.level().getServer();

			if (server != null) {
				server.execute(() -> setArea(server, player, payload));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(MarkerActionPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.level().getServer();

			if (server != null) {
				server.execute(() -> {
					if (payload.action() == MarkerActionPayload.PLACE) {
						place(server, player, payload);
					} else if (payload.action() == MarkerActionPayload.REMOVE) {
						remove(server, player, payload.markerId());
					} else if (payload.action() == MarkerActionPayload.RENAME) {
						rename(server, player, payload.markerId(), payload.label());
					} else if (payload.action() == MarkerActionPayload.MOVE) {
						move(server, player, payload.markerId(), payload.x(), payload.z());
					} else if (payload.action() == MarkerActionPayload.VOTE) {
						vote(server, player, payload.markerId());
					}
				});
			}
		});
	}

	// ---------------------------------------------------------------- visibility

	private static boolean canSee(MarkerData m, UUID player, NationData playerNation) {
		return switch (m.visibility) {
			case MarkerType.PUBLIC -> true;
			case MarkerType.NATION -> playerNation != null && playerNation.id.equals(m.nation);
			default -> m.owner.equals(player);
		};
	}

	private static void refreshPopulation() {
		for (MarkerData m : MARKERS.values()) {
			m.population = 0;

			for (long key : m.area) {
				m.population += ServerPopulation.at(m.dimension, key);
			}
		}
	}

	/** Every chunk inside some settlement's borders. */
	static java.util.Set<Long> areaChunks(String dimension) {
		java.util.Set<Long> set = new java.util.HashSet<>();

		for (MarkerData m : MARKERS.values()) {
			if (m.dimension.equals(dimension)) {
				set.addAll(m.area);
			}
		}

		return set;
	}

	/** Which other settlement's borders already cover this chunk (null = none). */
	private static MarkerData areaOwner(String dimension, long key, MarkerData except) {
		for (MarkerData m : MARKERS.values()) {
			if (m != except && m.dimension.equals(dimension) && m.area.contains(key)) {
				return m;
			}
		}

		return null;
	}

	/**
	 * Default borders: a small square around the marker, leaving out chunks of other settlements
	 * and land of other nations.
	 */
	private static void resetArea(MarkerData m) {
		m.area.clear();

		if (!m.type.settlement) {
			return;
		}

		int cx = Math.floorDiv(m.x, 16);
		int cz = Math.floorDiv(m.z, 16);
		NationData land = ServerNations.nationAt(m.dimension, cx, cz);
		int r = m.type.defaultRadius();

		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				long key = MapNationsMod.chunkKey(cx + dx, cz + dz);
				NationData owner = ServerNations.nationAt(m.dimension, cx + dx, cz + dz);
				boolean own = dx == 0 && dz == 0;

				if (own || (areaOwner(m.dimension, key, m) == null && (owner == null || owner == land))) {
					m.area.add(key);
				}
			}
		}
	}

	/** Checks new borders (adds the marker's own chunk). Returns an error message, or null if they're fine. */
	private static String areaProblem(MarkerData m, java.util.Set<Long> chunks) {
		int cx = Math.floorDiv(m.x, 16);
		int cz = Math.floorDiv(m.z, 16);
		NationData land = ServerNations.nationAt(m.dimension, cx, cz);
		chunks.add(MapNationsMod.chunkKey(cx, cz)); // a settlement always contains its own chunk

		if (chunks.size() > m.type.maxArea()) {
			return "A " + m.type.displayName + " can cover at most " + m.type.maxArea() + " chunks.";
		}

		for (long key : chunks) {
			int kx = MapNationsMod.keyX(key);
			int kz = MapNationsMod.keyZ(key);

			if (Math.max(Math.abs(kx - cx), Math.abs(kz - cz)) > MarkerType.AREA_REACH) {
				return "Borders can reach at most " + MarkerType.AREA_REACH + " chunks from the marker.";
			}

			MarkerData other = areaOwner(m.dimension, key, m);

			if (other != null) {
				return "Chunk " + kx + ", " + kz + " already belongs to " + other.title() + ".";
			}

			NationData owner = ServerNations.nationAt(m.dimension, kx, kz);

			if (owner != null && owner != land) {
				return "Chunk " + kx + ", " + kz + " is " + owner.name + "'s land.";
			}
		}

		return null;
	}

	/**
	 * When another nation takes a settlement's land, the settlement is theirs now:
	 * their leader manages it (and a captured capital becomes a city).
	 */
	private static boolean refreshOwnership() {
		boolean changed = false;

		for (MarkerData m : MARKERS.values()) {
			if (!m.type.settlement) {
				continue;
			}

			NationData land = ServerNations.nationAt(m.dimension, Math.floorDiv(m.x, 16), Math.floorDiv(m.z, 16));

			if (land != null && !land.id.equals(m.nation)) {
				m.nation = land.id;
				m.owner = land.leader;
				m.ownerName = land.leaderName();
				m.votes.clear();

				if (m.type == MarkerType.CAPITAL) {
					m.type = MarkerType.CITY;
				}

				changed = true;
			}
		}

		return changed;
	}

	/** The owner (or the leader of the land) sets new borders for a settlement. */
	private static void setArea(MinecraftServer server, ServerPlayer player, MarkerAreaPayload p) {
		MarkerData m = byId(p.markerId());

		if (m == null || !m.type.settlement) {
			return;
		}

		UUID me = player.getUUID();
		int cx = Math.floorDiv(m.x, 16);
		int cz = Math.floorDiv(m.z, 16);
		NationData land = ServerNations.nationAt(m.dimension, cx, cz);
		boolean landLeader = land != null && land.leader.equals(me);

		if (!m.owner.equals(me) && !landLeader) {
			ServerNations.status(player, "Only " + m.ownerName + " or the leader of this land can change these borders.", false);
			return;
		}

		java.util.LinkedHashSet<Long> chunks = new java.util.LinkedHashSet<>(p.chunks());
		String problem = areaProblem(m, chunks);

		if (problem != null) {
			ServerNations.status(player, problem, false);
			return;
		}

		m.area.clear();
		m.area.addAll(chunks);
		ServerNations.status(player, "New borders for " + m.title() + ": " + m.area.size() + " chunks.", true);
		save();
		broadcast(server);
	}

	private static MarkersSyncPayload buildFor(ServerPlayer player) {
		refreshPopulation();
		UUID id = player.getUUID();
		NationData nation = ServerNations.nationOf(id);
		List<MarkerData> list = new ArrayList<>();

		for (MarkerData m : MARKERS.values()) {
			if (canSee(m, id, nation)) {
				list.add(m);
			}
		}

		return new MarkersSyncPayload(list);
	}

	/** Every player gets their own list, because everyone may see different markers. */
	static void broadcast(MinecraftServer server) {
		if (refreshOwnership()) {
			save();
		}

		for (ServerPlayer p : PlayerLookup.all(server)) {
			if (ServerPlayNetworking.canSend(p, MarkersSyncPayload.TYPE)) {
				ServerPlayNetworking.send(p, buildFor(p));
			}
		}
	}

	/** A dissolved nation loses its settlements and nation-only markers. */
	static void onNationDisbanded(UUID nationId) {
		// settlements stay (they belong to whoever owns the land), but the capital and nation-only markers go
		MARKERS.values().removeIf(m -> nationId.equals(m.nation) && (m.type.leaderOnly || m.visibility == MarkerType.NATION));
		save();
	}

	// ---------------------------------------------------------------- placing

	private static String cleanLabel(String label) {
		String l = label.replace("§", "").trim();
		return l.length() > MarkerType.MAX_LABEL ? l.substring(0, MarkerType.MAX_LABEL) : l;
	}

	private static double distance(MarkerData m, int x, int z) {
		double dx = m.x - x;
		double dz = m.z - z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static void place(MinecraftServer server, ServerPlayer player, MarkerActionPayload p) {
		UUID me = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_PLACED.get(me);

		if (last != null && now - last < MarkerType.COOLDOWN_MS) {
			ServerNations.status(player, "Slow down - wait a few seconds before placing another marker.", false);
			return;
		}

		MarkerType type = MarkerType.byName(p.markerType());

		if (type != null && (type.settlement || type == MarkerType.PORT || type == MarkerType.MARKET || type == MarkerType.TEMPLE)) {
			// Map Nations WARS: cities, villages, castles... are real places in the world, not markers
			ServerNations.status(player, "In Map Nations WARS the villages and strongholds are already on the map.", false);
			return;
		}

		if (type == null) {
			return;
		}

		// important places are always visible to everyone
		int visibility = type.alwaysPublic ? MarkerType.PUBLIC : Math.max(0, Math.min(2, p.visibility()));
		Level level = player.level();
		String dim = level.dimension().identifier().toString();
		NationData nation = ServerNations.nationOf(me);
		int x = p.x();
		int z = p.z();

		if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))) {
			ServerNations.status(player, "You can't place markers outside the world border.", false);
			return;
		}

		if (visibility == MarkerType.NATION && nation == null) {
			ServerNations.status(player, "Join a nation to share markers with it.", false);
			return;
		}

		MarkerData replaced = null;
		NationData landOwner = ServerNations.nationAt(dim, Math.floorDiv(x, 16), Math.floorDiv(z, 16));

		if (type.leaderOnly) {
			if (nation == null || !nation.leader.equals(me)) {
				ServerNations.status(player, "Only a nation's leader can place the " + type.displayName + ".", false);
				return;
			}

			if (landOwner == null || !landOwner.id.equals(nation.id)) {
				ServerNations.status(player, "The " + type.displayName + " must be inside " + nation.name + "'s own land.", false);
				return;
			}

			for (MarkerData m : MARKERS.values()) {
				if (m.type == type && nation.id.equals(m.nation)) {
					replaced = m; // only one capital: the new one replaces the old one
				}
			}
		}

		if (type.settlement) {
			if (landOwner != null && (nation == null || !landOwner.id.equals(nation.id))) {
				ServerNations.status(player, "That is " + landOwner.name + "'s land - you can't found a "
						+ type.displayName + " there.", false);
				return;
			}

			for (MarkerData m : MARKERS.values()) {
				if (m != replaced && m.type.settlement && m.dimension.equals(dim) && distance(m, x, z) < MarkerType.SETTLEMENT_SPACING) {
					ServerNations.status(player, "Too close to " + m.title() + ". Settlements need "
							+ MarkerType.SETTLEMENT_SPACING + " blocks of space between them.", false);
					return;
				}
			}
		}

		if (type.playerLimit > 0) {
			for (MarkerData m : MARKERS.values()) {
				if (m.type == type && m.owner.equals(me)) {
					replaced = m; // e.g. your Home simply moves
				}
			}
		}

		int sameVisibility = 0;

		for (MarkerData m : MARKERS.values()) {
			if (m == replaced || !m.owner.equals(me)) {
				continue;
			}

			if (m.visibility == visibility) {
				sameVisibility++;
			}

			if (m.dimension.equals(dim) && distance(m, x, z) < MarkerType.OWN_MARKER_SPACING) {
				ServerNations.status(player, "You already have a marker right here (" + m.title() + ").", false);
				return;
			}
		}

		if (sameVisibility >= MarkerType.VISIBILITY_LIMITS[visibility]) {
			ServerNations.status(player, "You reached your limit of " + MarkerType.VISIBILITY_LIMITS[visibility] + " \""
					+ MarkerType.VISIBILITY_NAMES[visibility] + "\" markers. Remove one first.", false);
			return;
		}

		if (replaced != null) {
			MARKERS.remove(replaced.id);
		}

		MarkerData m = new MarkerData(UUID.randomUUID());
		m.owner = me;
		m.ownerName = player.getName().getString();
		m.nation = nation != null ? nation.id : null;
		m.dimension = dim;
		m.x = x;
		m.z = z;
		m.type = type;
		m.label = cleanLabel(p.label());
		m.visibility = visibility;
		m.created = now;
		resetArea(m);

		if (type.settlement && !p.area().isEmpty()) {
			// the borders the player chose while placing it
			java.util.LinkedHashSet<Long> chosen = new java.util.LinkedHashSet<>(p.area());
			String problem = areaProblem(m, chosen);

			if (problem != null) {
				ServerNations.status(player, "Borders: " + problem, false);
				return;
			}

			m.area.clear();
			m.area.addAll(chosen);
		}

		MARKERS.put(m.id, m);
		LAST_PLACED.put(me, now);

		ServerNations.status(player, (replaced != null ? "Moved " : "Placed ") + type.displayName + ": " + m.title(), true);
		save();
		broadcast(server);
	}

	private static MarkerData byId(String markerId) {
		try {
			return MARKERS.get(UUID.fromString(markerId));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static void rename(MinecraftServer server, ServerPlayer player, String markerId, String label) {
		MarkerData m = byId(markerId);

		if (m == null) {
			return;
		}

		if (!m.owner.equals(player.getUUID())) {
			ServerNations.status(player, "Only " + m.ownerName + " can rename this marker.", false);
			return;
		}

		m.label = cleanLabel(label);
		ServerNations.status(player, "Renamed to " + m.title() + ".", true);
		save();
		broadcast(server);
	}

	private static void move(MinecraftServer server, ServerPlayer player, String markerId, int x, int z) {
		MarkerData m = byId(markerId);

		if (m == null) {
			return;
		}

		UUID me = player.getUUID();

		if (!m.owner.equals(me)) {
			ServerNations.status(player, "Only " + m.ownerName + " can move this marker.", false);
			return;
		}

		long now = System.currentTimeMillis();
		Long last = LAST_PLACED.get(me);

		if (last != null && now - last < MarkerType.COOLDOWN_MS) {
			ServerNations.status(player, "Slow down - wait a few seconds.", false);
			return;
		}

		Level level = player.level();
		String dim = level.dimension().identifier().toString();

		if (!dim.equals(m.dimension)) {
			ServerNations.status(player, "You can only move a marker within its own dimension.", false);
			return;
		}

		if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))) {
			ServerNations.status(player, "You can't move markers outside the world border.", false);
			return;
		}

		NationData nation = ServerNations.nationOf(me);
		NationData landOwner = ServerNations.nationAt(dim, Math.floorDiv(x, 16), Math.floorDiv(z, 16));

		if (m.type.leaderOnly && (nation == null || !nation.leader.equals(me) || landOwner == null || !landOwner.id.equals(nation.id))) {
			ServerNations.status(player, "The " + m.type.displayName + " must stay inside your own nation's land.", false);
			return;
		}

		for (MarkerData other : MARKERS.values()) {
			if (other == m || !other.dimension.equals(dim)) {
				continue;
			}

			if (m.type.settlement && other.type.settlement && distance(other, x, z) < MarkerType.SETTLEMENT_SPACING) {
				ServerNations.status(player, "Too close to " + other.title() + ".", false);
				return;
			}

			if (other.owner.equals(me) && distance(other, x, z) < MarkerType.OWN_MARKER_SPACING) {
				ServerNations.status(player, "You already have a marker right there (" + other.title() + ").", false);
				return;
			}
		}

		if (m.type.settlement && landOwner != null && (nation == null || !landOwner.id.equals(nation.id))) {
			ServerNations.status(player, "That is " + landOwner.name + "'s land.", false);
			return;
		}

		m.x = x;
		m.z = z;
		resetArea(m); // borders move with it (edit them again if you like)
		LAST_PLACED.put(me, now);
		ServerNations.status(player, "Moved " + m.title() + ".", true);
		save();
		broadcast(server);
	}

	/** Voting: when enough players vote, someone else's marker is destroyed. */
	private static void vote(MinecraftServer server, ServerPlayer player, String markerId) {
		MarkerData m = byId(markerId);

		if (m == null) {
			return;
		}

		UUID me = player.getUUID();

		if (m.owner.equals(me)) {
			ServerNations.status(player, "You can't vote on your own marker - just remove it.", false);
			return;
		}

		if (!canSee(m, me, ServerNations.nationOf(me))) {
			return;
		}

		if (m.votes.remove(me)) {
			ServerNations.status(player, "Vote taken back (" + m.votes.size() + "/" + MarkerType.DESTROY_VOTES + ").", true);
		} else {
			m.votes.add(me);
			String who = player.getName().getString();

			if (m.votes.size() >= MarkerType.DESTROY_VOTES) {
				MARKERS.remove(m.id);
				ServerNations.status(player, m.title() + " was destroyed by vote!", true);
				notifyPlayer(server, m.owner, "Your marker " + m.title() + " was destroyed by a vote of "
						+ MarkerType.DESTROY_VOTES + " players.");
			} else {
				ServerNations.status(player, "You voted to destroy " + m.title() + " ("
						+ m.votes.size() + "/" + MarkerType.DESTROY_VOTES + ").", true);
				notifyPlayer(server, m.owner, who + " voted to destroy your marker " + m.title() + " ("
						+ m.votes.size() + "/" + MarkerType.DESTROY_VOTES + ").");
			}
		}

		save();
		broadcast(server);
	}

	private static void notifyPlayer(MinecraftServer server, UUID playerId, String message) {
		for (ServerPlayer p : PlayerLookup.all(server)) {
			if (p.getUUID().equals(playerId)) {
				ServerNations.status(p, message, true);
			}
		}
	}

	private static void remove(MinecraftServer server, ServerPlayer player, String markerId) {
		MarkerData m = byId(markerId);

		if (m == null) {
			return;
		}

		UUID me = player.getUUID();
		NationData nation = ServerNations.nationOf(me);
		boolean isOwner = m.owner.equals(me);
		NationData landOwner = ServerNations.nationAt(m.dimension, Math.floorDiv(m.x, 16), Math.floorDiv(m.z, 16));
		boolean isLeader = nation != null && nation.leader.equals(me) && m.visibility != MarkerType.PRIVATE
				&& (nation.id.equals(m.nation) || (landOwner != null && landOwner.id.equals(nation.id)));

		if (!isOwner && !isLeader) {
			ServerNations.status(player, "Only " + m.ownerName + " or the leader of this land can remove this marker.", false);
			return;
		}

		MARKERS.remove(m.id);
		ServerNations.status(player, "Removed " + m.title() + ".", true);
		save();
		broadcast(server);
	}

	// ---------------------------------------------------------------- saving / loading

	private static void load(MinecraftServer server) {
		MARKERS.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("mapnationswars_markers.json");

		if (!Files.exists(file)) {
			return;
		}

		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

			for (JsonElement el : root.getAsJsonArray("markers")) {
				JsonObject o = el.getAsJsonObject();
				MarkerType type = MarkerType.byName(o.get("type").getAsString());

				if (type == null) {
					continue;
				}

				MarkerData m = new MarkerData(UUID.fromString(o.get("id").getAsString()));
				m.owner = UUID.fromString(o.get("owner").getAsString());
				m.ownerName = o.get("ownerName").getAsString();
				m.nation = o.has("nation") ? UUID.fromString(o.get("nation").getAsString()) : null;
				m.dimension = o.get("dimension").getAsString();
				m.x = o.get("x").getAsInt();
				m.z = o.get("z").getAsInt();
				m.type = type;
				m.label = o.get("label").getAsString();
				m.visibility = type.alwaysPublic ? MarkerType.PUBLIC : o.get("visibility").getAsInt();
				m.created = o.get("created").getAsLong();

				if (o.has("area")) {
					for (JsonElement a : o.getAsJsonArray("area")) {
						m.area.add(a.getAsLong());
					}
				}

				if (o.has("votes")) {
					for (JsonElement v : o.getAsJsonArray("votes")) {
						m.votes.add(UUID.fromString(v.getAsString()));
					}
				}
				MARKERS.put(m.id, m);
			}

			// older markers without borders get the default ones
			for (MarkerData m : MARKERS.values()) {
				if (m.type.settlement && m.area.isEmpty()) {
					resetArea(m);
				}
			}

			MapNationsMod.LOGGER.info("Loaded {} map markers", MARKERS.size());
		} catch (Exception e) {
			MapNationsMod.LOGGER.error("Could not read {}", file, e);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}

		JsonArray arr = new JsonArray();

		for (MarkerData m : MARKERS.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", m.id.toString());
			o.addProperty("owner", m.owner.toString());
			o.addProperty("ownerName", m.ownerName);

			if (m.nation != null) {
				o.addProperty("nation", m.nation.toString());
			}

			o.addProperty("dimension", m.dimension);
			o.addProperty("x", m.x);
			o.addProperty("z", m.z);
			o.addProperty("type", m.type.name());
			o.addProperty("label", m.label);
			o.addProperty("visibility", m.visibility);
			o.addProperty("created", m.created);
			JsonArray votes = new JsonArray();

			for (UUID v : m.votes) {
				votes.add(v.toString());
			}

			o.add("votes", votes);
			JsonArray area = new JsonArray();

			for (long key : m.area) {
				area.add(key);
			}

			o.add("area", area);
			arr.add(o);
		}

		JsonObject root = new JsonObject();
		root.add("markers", arr);

		try {
			Path tmp = file.resolveSibling("mapnationswars_markers.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			MapNationsMod.LOGGER.error("Could not save {}", file, e);
		}
	}
}
