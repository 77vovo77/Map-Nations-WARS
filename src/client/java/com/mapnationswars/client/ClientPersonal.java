package com.mapnationswars.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mapnationswars.nation.DutyData;
import com.mapnationswars.network.PersonalSyncPayload;

/** What nations think of this player, their personal wars and their duties (1.9). */
public final class ClientPersonal {
	private static final Map<UUID, PersonalSyncPayload.Standing> STANDING = new HashMap<>();
	private static List<DutyData> duties = List.of();
	private static int version = 0;

	private ClientPersonal() {
	}

	static void apply(PersonalSyncPayload payload) {
		STANDING.clear();

		for (PersonalSyncPayload.Standing s : payload.standings()) {
			STANDING.put(s.nation(), s);
		}

		duties = payload.duties();
		version++;
	}

	static void clear() {
		STANDING.clear();
		duties = List.of();
		version++;
	}

	public static int version() {
		return version;
	}

	public static int standing(UUID nation) {
		PersonalSyncPayload.Standing s = nation == null ? null : STANDING.get(nation);
		return s == null ? 0 : s.opinion();
	}

	public static boolean atWar(UUID nation) {
		PersonalSyncPayload.Standing s = nation == null ? null : STANDING.get(nation);
		return s != null && s.war();
	}

	/** Their guards attack you there (same rule as the server). */
	public static boolean outlawIn(UUID nation) {
		return atWar(nation) || standing(nation) <= -60;
	}

	public static java.util.Collection<PersonalSyncPayload.Standing> standings() {
		return STANDING.values();
	}

	public static List<DutyData> duties() {
		return duties;
	}
}
