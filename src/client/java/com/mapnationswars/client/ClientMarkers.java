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
	private static List<MarkerData> markers = List.of();

	private ClientMarkers() {
	}

	static void apply(MarkersSyncPayload payload) {
		markers = payload.markers();
	}

	static void clear() {
		markers = List.of();
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
			if (m.type.settlement && m.dimension.equals(dimension) && m.areaContains(chunkX, chunkZ)
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
