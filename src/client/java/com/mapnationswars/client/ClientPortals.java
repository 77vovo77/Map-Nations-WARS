package com.mapnationswars.client;

import java.util.ArrayList;
import java.util.List;

import com.mapnationswars.nation.PortalSite;
import com.mapnationswars.network.PortalsSyncPayload;

/** Every portal the client knows about (Map Nations WARS stage 8). */
public final class ClientPortals {
	private static List<PortalSite> portals = List.of();

	private ClientPortals() {
	}

	static void apply(PortalsSyncPayload payload) {
		portals = new ArrayList<>(payload.portals());
	}

	static void clear() {
		portals = List.of();
	}

	public static List<PortalSite> all() {
		return portals;
	}

	/** Portals sorted from most awake to least. */
	public static List<PortalSite> byDanger() {
		List<PortalSite> list = new ArrayList<>(portals);
		list.sort((a, b) -> Double.compare(b.open ? 101 : b.activation, a.open ? 101 : a.activation));
		return list;
	}

	/** Is this portal shown in this dimension? Overworld: all. Nether: the other side of the usable ones. */
	public static boolean visibleIn(PortalSite s, String dimension) {
		return PortalSite.OVERWORLD.equals(dimension) || (PortalSite.NETHER.equals(dimension) && s.usable());
	}

	public static int color(PortalSite s) {
		if (s.open) {
			return 0xFFFF4020;
		}

		return s.activation >= 75 ? 0xFFE040FF : s.activation >= 40 ? 0xFFA050E0 : 0xFF6A3A9A;
	}

	public static String state(PortalSite s) {
		if (s.open) {
			return "TORN OPEN - the Nether is invading!";
		}

		return s.activation >= 90 ? "About to open!" : s.activation >= 75 ? "Glowing with Nether light" : s.activation >= 40 ? "Stirring" : "Dormant";
	}
}
