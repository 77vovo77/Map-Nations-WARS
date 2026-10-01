package com.mapnationswars.client;

import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;

import com.mapnationswars.nation.MarkerData;
import com.mapnationswars.nation.MarkerType;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.network.MarkersSyncPayload;

/** The markers this player is allowed to see (the server already filtered them). */
public final class ClientMarkers {
	/** Players' markers plus the provinces of the world (shown like settlements). */
	private static List<MarkerData> markers = List.of();
	private static List<MarkerData> playerMarkers = List.of();
	private static List<MarkerData> provinceMarkers = List.of();

	private ClientMarkers() {
	}

	static void apply(MarkersSyncPayload payload) {
		playerMarkers = payload.markers();
		rebuild();
	}

	/** Map Nations WARS: every province becomes a settlement on the map. */
	static void applyProvinces(com.mapnationswars.network.ProvincesSyncPayload payload) {
		List<MarkerData> list = new java.util.ArrayList<>();

		for (com.mapnationswars.nation.ProvinceData p : payload.provinces()) {
			MarkerData m = new MarkerData(p.id);
			m.province = p;
			m.owner = new UUID(0, 0);
			m.ownerName = p.mayorName;
			m.nation = p.nation;
			m.dimension = p.dimension;
			m.x = p.x;
			m.z = p.z;
			m.type = switch (p.type) {
				case VILLAGE -> p.abandoned ? MarkerType.DANGER : (p.capital ? MarkerType.CAPITAL : (p.population >= 12 ? MarkerType.CITY : MarkerType.VILLAGE));
				case OUTPOST -> MarkerType.FORT;
				case MANSION -> p.capital ? MarkerType.CAPITAL : MarkerType.CASTLE;
				case BASTION -> p.capital ? MarkerType.CAPITAL : MarkerType.CASTLE;
			};
			m.label = p.title();
			m.visibility = MarkerType.PUBLIC;
			m.area.addAll(p.area);
			m.population = p.population;
			list.add(m);
		}

		provinceMarkers = list;
		rebuild();
	}

	/** A province by id (Map Nations WARS), or null. */
	public static com.mapnationswars.nation.ProvinceData province(UUID id) {
		for (MarkerData m : provinceMarkers) {
			if (m.province != null && m.province.id.equals(id)) {
				return m.province;
			}
		}

		return null;
	}

	private static void rebuild() {
		List<MarkerData> all = new java.util.ArrayList<>(provinceMarkers.size() + playerMarkers.size());
		all.addAll(provinceMarkers);
		all.addAll(playerMarkers);
		markers = all;
	}

	static void clear() {
		markers = List.of();
		playerMarkers = List.of();
		provinceMarkers = List.of();
	}

	public static List<MarkerData> all() {
		return markers;
	}

	private static UUID me() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null ? mc.player.getUUID() : new UUID(0, 0);
	}

	/** How many markers of this visibility I own (a marker that would be replaced does not count). */
	public static int countMine(int visibility, MarkerType replacing) {
		UUID me = me();
		int count = 0;

		for (MarkerData m : markers) {
			if (m.owner.equals(me) && m.visibility == visibility && !(replacing != null && m.type == replacing)) {
				count++;
			}
		}

		return count;
	}

	/** The settlement whose borders contain this chunk (the biggest kind wins), or null. */
	public static MarkerData settlementAt(String dimension, int chunkX, int chunkZ) {
		MarkerData best = null;

		for (MarkerData m : markers) {
			if ((m.type.settlement || m.province != null) && m.dimension.equals(dimension) && m.areaContains(chunkX, chunkZ)
					&& (best == null || m.type.ordinal() < best.type.ordinal())) {
				best = m;
			}
		}

		return best;
	}

	/** Can I remove this marker? (my own, or as leader: my members' markers and anything on my land) */
	public static boolean canRemove(MarkerData m) {
		UUID me = me();

		if (m.owner.equals(me)) {
			return true;
		}

		NationData mine = ClientNations.myNation();

		if (mine == null || !mine.leader.equals(me) || m.visibility == MarkerType.PRIVATE) {
			return false;
		}

		// leaders can remove markers of their own members, and anything on their land
		NationData land = ClientNations.nationAt(m.dimension, Math.floorDiv(m.x, 16), Math.floorDiv(m.z, 16));
		return mine.id.equals(m.nation) || (land != null && land.id.equals(mine.id));
	}
}
