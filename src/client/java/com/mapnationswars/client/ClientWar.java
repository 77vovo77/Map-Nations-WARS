package com.mapnationswars.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mapnationswars.nation.DivisionData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.Ranks;
import com.mapnationswars.network.WarSyncPayload;

/** What the client knows about armies, sieges and battles (Map Nations WARS stage 5). */
public final class ClientWar {
	private static List<DivisionData> divisions = List.of();
	private static final Map<UUID, WarSyncPayload.Siege> SIEGES = new HashMap<>();
	private static List<WarSyncPayload.Battle> battles = List.of();
	/** Where each division was at the previous update (to slide it smoothly between updates). */
	private static final Map<UUID, double[]> PREVIOUS = new HashMap<>();
	private static long lastUpdate = 0;
	private static int version = 0;

	private ClientWar() {
	}

	static void apply(WarSyncPayload payload) {
		Map<UUID, double[]> now = new HashMap<>();

		for (DivisionData d : divisions) {
			double[] pos = position(d);
			now.put(d.id, pos);
		}

		PREVIOUS.clear();
		PREVIOUS.putAll(now);
		boolean changedList = payload.divisions().size() != divisions.size() || payload.sieges().size() != SIEGES.size();
		divisions = new ArrayList<>(payload.divisions());
		SIEGES.clear();

		for (WarSyncPayload.Siege s : payload.sieges()) {
			SIEGES.put(s.province(), s);
		}

		battles = new ArrayList<>(payload.battles());
		lastUpdate = System.currentTimeMillis();

		if (changedList) {
			version++;
		}
	}

	static void clear() {
		divisions = List.of();
		SIEGES.clear();
		battles = List.of();
		PREVIOUS.clear();
		version++;
	}

	public static int version() {
		return version;
	}

	public static List<DivisionData> divisions() {
		return divisions;
	}

	public static List<WarSyncPayload.Battle> battles() {
		return battles;
	}

	public static java.util.Collection<WarSyncPayload.Siege> sieges() {
		return SIEGES.values();
	}

	public static WarSyncPayload.Siege siegeOf(UUID province) {
		return province == null ? null : SIEGES.get(province);
	}

	public static DivisionData get(UUID id) {
		for (DivisionData d : divisions) {
			if (d.id.equals(id)) {
				return d;
			}
		}

		return null;
	}

	public static List<DivisionData> of(UUID nation) {
		List<DivisionData> list = new ArrayList<>();

		for (DivisionData d : divisions) {
			if (d.nation.equals(nation)) {
				list.add(d);
			}
		}

		return list;
	}

	/** Where to draw a division right now: between its last two known positions. */
	public static double[] position(DivisionData d) {
		double[] prev = PREVIOUS.get(d.id);

		if (prev == null) {
			return new double[] {d.x, d.z};
		}

		double t = Math.min(1.0, (System.currentTimeMillis() - lastUpdate) / 1000.0);
		return new double[] {prev[0] + (d.x - prev[0]) * t, prev[1] + (d.z - prev[1]) * t};
	}

	/** Can this player give orders to the division? (Officers and up, like the server checks.) */
	public static boolean canCommand(DivisionData d, UUID player) {
		NationData n = ClientNations.get(d.nation);
		return n != null && (ClientCreative.active() || n.leader.equals(player) || (n.isMember(player) && n.rankOf(player) >= Ranks.OFFICER));
	}

	public static int strengthColor(DivisionData d) {
		double f = d.strength / d.kind.maxStrength;
		return f > 0.66 ? 0xFF5FD35F : (f > 0.33 ? 0xFFE0C040 : 0xFFE05040);
	}
}
