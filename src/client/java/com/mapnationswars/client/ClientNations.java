package com.mapnationswars.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.AllianceData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.network.NationsSyncPayload;
import com.mapnationswars.network.PlayersPayload;

/** The client's copy of all nations, claims and player positions, as sent by the server. */
public final class ClientNations {
	/** A label drawn on the map for one connected piece of a nation's land. */
	/** A connected area claimed by more than one nation. */
	public record ConflictZone(double centerX, double centerZ, List<UUID> nations, int chunks) {
	}

	public record TerritoryLabel(UUID nation, double centerX, double centerZ, float angle,
			double lengthBlocks, double thicknessBlocks, int chunks) {
	}

	private static final Map<UUID, NationData> NATIONS = new LinkedHashMap<>();
	private static final Map<UUID, AllianceData> ALLIANCES = new LinkedHashMap<>();
	/** nation id -> alliance id */
	private static final Map<UUID, UUID> NATION_ALLIANCE = new HashMap<>();
	/** dimension -> (chunk key -> owning nation id). The owner is the first nation that claimed it. */
	private static final Map<String, Map<Long, UUID>> CLAIMS = new HashMap<>();
	/** dimension -> (chunk key -> every nation claiming it). More than one = conflict zone. */
	private static final Map<String, Map<Long, List<UUID>>> CLAIMANTS = new HashMap<>();
	private static final Map<String, List<ConflictZone>> CONFLICT_CACHE = new HashMap<>();
	/** dimension -> (chunk key -> land an officer proposed) */
	private static final Map<String, Map<Long, NationsSyncPayload.Proposal>> PROPOSALS = new HashMap<>();
	private static final Map<UUID, UUID> PLAYER_NATION = new HashMap<>();
	private static final Map<String, List<TerritoryLabel>> LABEL_CACHE = new HashMap<>();
	private static List<PlayersPayload.Entry> players = List.of();

	/** Goes up every time new data arrives, so screens know to refresh. */
	private static int version = 0;

	private static String statusText = "";
	private static boolean statusOk = true;
	private static long statusUntil = 0;

	private ClientNations() {
	}

	// ---------------------------------------------------------------- incoming data

	static void apply(NationsSyncPayload payload) {
		NATIONS.clear();
		CLAIMS.clear();
		CLAIMANTS.clear();
		CONFLICT_CACHE.clear();
		PLAYER_NATION.clear();
		LABEL_CACHE.clear();
		PROPOSALS.clear();
		ALLIANCES.clear();
		NATION_ALLIANCE.clear();

		for (AllianceData a : payload.alliances()) {
			ALLIANCES.put(a.id, a);

			for (UUID n : a.nations) {
				NATION_ALLIANCE.put(n, a.id);
			}
		}

		for (NationsSyncPayload.Proposal p : payload.proposals()) {
			PROPOSALS.computeIfAbsent(p.dimension(), d -> new HashMap<>()).put(MapNationsMod.chunkKey(p.chunkX(), p.chunkZ()), p);
		}

		for (NationData n : payload.nations()) {
			NATIONS.put(n.id, n);

			for (NationData.Member m : n.members) {
				PLAYER_NATION.put(m.id(), n.id);
			}
		}

		for (NationsSyncPayload.Claim c : payload.claims()) {
			long key = MapNationsMod.chunkKey(c.chunkX(), c.chunkZ());
			// the first nation listed for a chunk is its owner
			CLAIMS.computeIfAbsent(c.dimension(), d -> new HashMap<>()).putIfAbsent(key, c.nation());
			CLAIMANTS.computeIfAbsent(c.dimension(), d -> new HashMap<>())
					.computeIfAbsent(key, k -> new ArrayList<>()).add(c.nation());
		}

		version++;
	}

	static void applyPlayers(PlayersPayload payload) {
		players = payload.players();
	}

	static void clear() {
		PROPOSALS.clear();
		ALLIANCES.clear();
		NATION_ALLIANCE.clear();
		NATIONS.clear();
		CLAIMS.clear();
		CLAIMANTS.clear();
		CONFLICT_CACHE.clear();
		PLAYER_NATION.clear();
		LABEL_CACHE.clear();
		players = List.of();
		version++;
	}

	static void setStatus(String text, boolean ok) {
		statusText = text;
		statusOk = ok;
		statusUntil = System.currentTimeMillis() + 6000;
	}

	// ---------------------------------------------------------------- queries

	public static int version() {
		return version;
	}

	public static String status() {
		return System.currentTimeMillis() < statusUntil ? statusText : "";
	}

	public static boolean statusOk() {
		return statusOk;
	}

	public static long statusUntil() {
		return statusUntil;
	}

	public static Collection<NationData> all() {
		return NATIONS.values();
	}

	public static Collection<AllianceData> alliances() {
		return ALLIANCES.values();
	}

	public static AllianceData alliance(UUID id) {
		return id == null ? null : ALLIANCES.get(id);
	}

	/** The alliance a nation is in, or null. */
	public static AllianceData allianceOf(UUID nation) {
		return nation == null ? null : alliance(NATION_ALLIANCE.get(nation));
	}

	/** Colours already used by other alliances. */
	public static Set<Integer> usedAllianceColors(UUID except) {
		Set<Integer> set = new HashSet<>();

		for (AllianceData a : ALLIANCES.values()) {
			if (!a.id.equals(except)) {
				set.add(a.color);
			}
		}

		return set;
	}

	/** How many chunks all nations of an alliance own together. */
	public static int allianceChunks(AllianceData a) {
		int total = 0;

		for (UUID n : a.nations) {
			total += chunkCount(n);
		}

		return total;
	}

	public static NationData get(UUID id) {
		return id == null ? null : NATIONS.get(id);
	}

	/**
	 * A player's name in their nation's colour, with the leader's symbol or the officer star in front
	 * (used for name tags and the player list). Null if the player isn't in a nation.
	 */
	public static net.minecraft.network.chat.MutableComponent styledName(UUID player, String name) {
		NationData nation = nationOfPlayer(player);

		if (nation == null) {
			return null;
		}

		net.minecraft.network.chat.MutableComponent out = net.minecraft.network.chat.Component.literal(name).withColor(nation.color);

		if (player.equals(nation.leader)) {
			out = net.minecraft.network.chat.Component.literal(nation.ideology.symbol + " ").withColor(0xFFD54F).append(out);
		} else if (nation.isOfficer(player)) {
			out = net.minecraft.network.chat.Component.literal("\u2726 ").withColor(0xC0C8FF).append(out);
		}

		return out;
	}

	public static NationData nationOfPlayer(UUID player) {
		return get(PLAYER_NATION.get(player));
	}

	public static NationData myNation() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : nationOfPlayer(mc.player.getUUID());
	}

	public static Map<Long, UUID> claims(String dimension) {
		Map<Long, UUID> map = CLAIMS.get(dimension);
		return map != null ? map : Collections.emptyMap();
	}

	public static NationData nationAt(String dimension, int chunkX, int chunkZ) {
		return get(claims(dimension).get(MapNationsMod.chunkKey(chunkX, chunkZ)));
	}

	public static int chunkCount(UUID nation) {
		int count = 0;

		for (Map<Long, List<UUID>> dim : CLAIMANTS.values()) {
			for (List<UUID> ids : dim.values()) {
				if (ids.contains(nation)) {
					count++;
				}
			}
		}

		return count;
	}

	public static Map<Long, NationsSyncPayload.Proposal> proposals(String dimension) {
		Map<Long, NationsSyncPayload.Proposal> map = PROPOSALS.get(dimension);
		return map != null ? map : Collections.emptyMap();
	}

	public static NationsSyncPayload.Proposal proposalAt(String dimension, int chunkX, int chunkZ) {
		return proposals(dimension).get(MapNationsMod.chunkKey(chunkX, chunkZ));
	}

	/** How many chunks this nation's officers have proposed (all dimensions). */
	public static int proposalCount(UUID nation) {
		int count = 0;

		for (Map<Long, NationsSyncPayload.Proposal> dim : PROPOSALS.values()) {
			for (NationsSyncPayload.Proposal p : dim.values()) {
				if (p.nation().equals(nation)) {
					count++;
				}
			}
		}

		return count;
	}

	/** Every nation claiming this chunk (owner first). Empty list if nobody. */
	public static List<UUID> claimants(String dimension, int chunkX, int chunkZ) {
		Map<Long, List<UUID>> map = CLAIMANTS.get(dimension);
		List<UUID> list = map == null ? null : map.get(MapNationsMod.chunkKey(chunkX, chunkZ));
		return list != null ? list : List.of();
	}

	public static Map<Long, List<UUID>> claimantsMap(String dimension) {
		Map<Long, List<UUID>> map = CLAIMANTS.get(dimension);
		return map != null ? map : Collections.emptyMap();
	}

	public static boolean contested(String dimension, int chunkX, int chunkZ) {
		return claimants(dimension, chunkX, chunkZ).size() > 1;
	}

	public static boolean allied(UUID a, UUID b) {
		NationData na = get(a);
		return na != null && na.allies.contains(b);
	}

	/** Conflict zones of a dimension: connected contested chunks, with the nations involved. */
	public static List<ConflictZone> conflicts(String dimension) {
		return CONFLICT_CACHE.computeIfAbsent(dimension, ClientNations::computeConflicts);
	}

	private static List<ConflictZone> computeConflicts(String dimension) {
		Map<Long, List<UUID>> map = claimantsMap(dimension);
		List<ConflictZone> result = new ArrayList<>();
		Set<Long> visited = new HashSet<>();

		for (Map.Entry<Long, List<UUID>> start : map.entrySet()) {
			if (start.getValue().size() < 2 || visited.contains(start.getKey())) {
				continue;
			}

			List<UUID> nations = new ArrayList<>();
			ArrayDeque<Long> queue = new ArrayDeque<>();
			queue.add(start.getKey());
			visited.add(start.getKey());
			double sumX = 0;
			double sumZ = 0;
			int count = 0;

			while (!queue.isEmpty()) {
				long key = queue.poll();
				int cx = MapNationsMod.keyX(key);
				int cz = MapNationsMod.keyZ(key);
				sumX += cx * 16 + 8;
				sumZ += cz * 16 + 8;
				count++;

				for (UUID id : map.get(key)) {
					if (!nations.contains(id)) {
						nations.add(id);
					}
				}

				long[] neighbours = {
						MapNationsMod.chunkKey(cx + 1, cz), MapNationsMod.chunkKey(cx - 1, cz),
						MapNationsMod.chunkKey(cx, cz + 1), MapNationsMod.chunkKey(cx, cz - 1)
				};

				for (long nb : neighbours) {
					List<UUID> other = map.get(nb);

					if (other != null && other.size() > 1 && !visited.contains(nb)) {
						visited.add(nb);
						queue.add(nb);
					}
				}
			}

			result.add(new ConflictZone(sumX / count, sumZ / count, nations, count));
		}

		return result;
	}

	public static Set<Integer> usedColors(UUID except) {
		Set<Integer> used = new HashSet<>();

		for (NationData n : NATIONS.values()) {
			if (!n.id.equals(except)) {
				used.add(n.color);
			}
		}

		return used;
	}

	public static List<PlayersPayload.Entry> players() {
		return players;
	}

	// ---------------------------------------------------------------- map labels

	/** One label per connected piece of land, with a size and angle that fits the shape. */
	public static List<TerritoryLabel> labels(String dimension) {
		return LABEL_CACHE.computeIfAbsent(dimension, ClientNations::computeLabels);
	}

	private static List<TerritoryLabel> computeLabels(String dimension) {
		Map<Long, UUID> claims = claims(dimension);
		List<TerritoryLabel> result = new ArrayList<>();
		Set<Long> visited = new HashSet<>();

		for (Map.Entry<Long, UUID> start : claims.entrySet()) {
			if (visited.contains(start.getKey())) {
				continue;
			}

			UUID nation = start.getValue();
			List<Long> piece = new ArrayList<>();
			ArrayDeque<Long> queue = new ArrayDeque<>();
			queue.add(start.getKey());
			visited.add(start.getKey());

			// flood fill: all touching chunks of the same nation
			while (!queue.isEmpty()) {
				long key = queue.poll();
				piece.add(key);
				int cx = MapNationsMod.keyX(key);
				int cz = MapNationsMod.keyZ(key);
				long[] neighbours = {
						MapNationsMod.chunkKey(cx + 1, cz), MapNationsMod.chunkKey(cx - 1, cz),
						MapNationsMod.chunkKey(cx, cz + 1), MapNationsMod.chunkKey(cx, cz - 1)
				};

				for (long nb : neighbours) {
					if (!visited.contains(nb) && nation.equals(claims.get(nb))) {
						visited.add(nb);
						queue.add(nb);
					}
				}
			}

			result.add(makeLabel(nation, piece));
		}

		return result;
	}

	private static TerritoryLabel makeLabel(UUID nation, List<Long> piece) {
		double sumX = 0;
		double sumZ = 0;

		for (long key : piece) {
			sumX += MapNationsMod.keyX(key) * 16 + 8;
			sumZ += MapNationsMod.keyZ(key) * 16 + 8;
		}

		double mx = sumX / piece.size();
		double mz = sumZ / piece.size();

		// Which direction is the land longest in? (so the name can follow the shape)
		double sxx = 0;
		double szz = 0;
		double sxz = 0;

		for (long key : piece) {
			double dx = MapNationsMod.keyX(key) * 16 + 8 - mx;
			double dz = MapNationsMod.keyZ(key) * 16 + 8 - mz;
			sxx += dx * dx;
			szz += dz * dz;
			sxz += dx * dz;
		}

		double angle = 0.5 * Math.atan2(2 * sxz, sxx - szz);

		// Tilted too steeply would be hard to read -> keep it flat.
		if (Math.abs(angle) > 0.7) {
			angle = 0;
		}

		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		double minA = Double.MAX_VALUE;
		double maxA = -Double.MAX_VALUE;
		double minB = Double.MAX_VALUE;
		double maxB = -Double.MAX_VALUE;

		for (long key : piece) {
			double dx = MapNationsMod.keyX(key) * 16 + 8 - mx;
			double dz = MapNationsMod.keyZ(key) * 16 + 8 - mz;
			double a = dx * cos + dz * sin;
			double b = -dx * sin + dz * cos;
			minA = Math.min(minA, a);
			maxA = Math.max(maxA, a);
			minB = Math.min(minB, b);
			maxB = Math.max(maxB, b);
		}

		double length = maxA - minA + 16;
		double thickness = maxB - minB + 16;
		// centre of the bounding box along the axes (looks better than the average for L-shapes)
		double midA = (minA + maxA) / 2;
		double midB = (minB + maxB) / 2;
		double cx = mx + midA * cos - midB * sin;
		double cz = mz + midA * sin + midB * cos;

		return new TerritoryLabel(nation, cx, cz, (float) angle, length, thickness, piece.size());
	}
}
