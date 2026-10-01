package com.mapnationswars.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mapnationswars.network.SupportSyncPayload;

/** How much each province's people back this player (Map Nations WARS stage 7: founding a nation). */
public final class ClientRevolts {
	private static final Map<UUID, Integer> SUPPORT = new HashMap<>();

	private ClientRevolts() {
	}

	static void apply(SupportSyncPayload payload) {
		SUPPORT.clear();

		for (SupportSyncPayload.Entry e : payload.support()) {
			SUPPORT.put(e.province(), e.support());
		}
	}

	static void clear() {
		SUPPORT.clear();
	}

	public static int support(UUID province) {
		return province == null ? 0 : SUPPORT.getOrDefault(province, 0);
	}

	public static Map<UUID, Integer> all() {
		return SUPPORT;
	}
}
