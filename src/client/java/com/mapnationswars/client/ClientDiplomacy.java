package com.mapnationswars.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mapnationswars.nation.LetterData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.Relations;
import com.mapnationswars.network.DiplomacySyncPayload;

/** The client's copy of relations between nations and its nation's letters (Map Nations WARS stage 4). */
public final class ClientDiplomacy {
	private static final Map<String, DiplomacySyncPayload.Entry> RELATIONS = new HashMap<>();
	private static List<LetterData> letters = List.of();
	private static int version = 0;

	private ClientDiplomacy() {
	}

	static void apply(DiplomacySyncPayload payload) {
		RELATIONS.clear();

		for (DiplomacySyncPayload.Entry e : payload.relations()) {
			RELATIONS.put(Relations.key(e.a(), e.b()), e);
		}

		letters = payload.letters();
		version++;
	}

	static void clear() {
		RELATIONS.clear();
		letters = List.of();
		version++;
	}

	public static int version() {
		return version;
	}

	public static List<LetterData> letters() {
		return letters;
	}

	public static boolean atWar(UUID a, UUID b) {
		DiplomacySyncPayload.Entry e = RELATIONS.get(Relations.key(a, b));
		return e != null && e.war();
	}

	public static boolean trading(UUID a, UUID b) {
		DiplomacySyncPayload.Entry e = RELATIONS.get(Relations.key(a, b));
		return e != null && e.trade();
	}

	/** Same formula as the server. */
	public static int opinion(NationData a, NationData b) {
		DiplomacySyncPayload.Entry e = RELATIONS.get(Relations.key(a.id, b.id));
		int o = Relations.base(a, b);

		if (e != null) {
			o += e.modifier() + (e.war() ? -30 : 0) + (e.trade() ? 5 : 0);
		}

		if (a.allies.contains(b.id)) {
			o += 20;
		}

		return Relations.clamp(o);
	}

	/** Letters waiting for my nation's answer. */
	public static int pending(UUID myNation) {
		int n = 0;

		for (LetterData l : letters) {
			if (l.status == LetterData.Status.PENDING && myNation.equals(l.to)) {
				n++;
			}
		}

		return n;
	}
}
