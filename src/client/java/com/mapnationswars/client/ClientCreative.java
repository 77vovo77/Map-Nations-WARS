package com.mapnationswars.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;

import com.mapnationswars.nation.DivisionData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.network.CreativeActionPayload;

/** CREATIVEMOD (2.3) on the client: is it on, and what the creative panel of the map has picked. */
public final class ClientCreative {
	public enum Tool {
		NONE(""),
		PAINT("Drag on the map: the land becomes %s's."),
		ERASE("Drag on the map: the land becomes nobody's."),
		GIVE("Click a village: it now belongs to %s."),
		SPAWN("Click the map: an army of %s appears there."),
		MOVE("Click an army, then click where it should be."),
		DELETE("Click an army to make it vanish."),
		BOOST("Click a village: more villagers, houses and farms.");

		public final String hint;

		Tool(String hint) {
			this.hint = hint;
		}
	}

	/** Told by the server. */
	private static boolean on = false;
	public static Tool tool = Tool.NONE;
	/** The nation the tools work for. */
	public static UUID nation;
	/** The other nation, for war / peace / alliance. */
	public static UUID other;
	public static DivisionData.Kind kind = DivisionData.Kind.INFANTRY;
	/** MOVE tool: the army picked up. */
	public static UUID moving;

	private ClientCreative() {
	}

	static void apply(boolean value) {
		on = value;

		if (!on) {
			tool = Tool.NONE;
			moving = null;
		}
	}

	/** Is the player in creative mode (so the CREATIVEMOD button shows)? */
	public static boolean creativePlayer() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.player.isCreative();
	}

	/** Is CREATIVEMOD on right now? */
	public static boolean active() {
		return on && creativePlayer();
	}

	public static boolean paints() {
		return active() && (tool == Tool.PAINT || tool == Tool.ERASE);
	}

	// ---------------------------------------------------------------- nations to pick from

	private static List<NationData> nations() {
		List<NationData> list = new ArrayList<>(ClientNations.all());
		list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
		return list;
	}

	public static NationData picked() {
		NationData n = ClientNations.get(nation);

		if (n == null) {
			n = ClientNations.myNation();

			if (n == null && !nations().isEmpty()) {
				n = nations().get(0);
			}

			nation = n != null ? n.id : null;
		}

		return n;
	}

	public static NationData pickedOther() {
		NationData n = ClientNations.get(other);

		if (n == null || n.id.equals(nation)) {
			for (NationData o : nations()) {
				if (!o.id.equals(nation)) {
					other = o.id;
					return o;
				}
			}

			other = null;
			return null;
		}

		return n;
	}

	/** Next / previous nation in the list. */
	public static UUID cycle(UUID current, int step, UUID skip) {
		List<NationData> list = nations();
		list.removeIf(n -> n.id.equals(skip));

		if (list.isEmpty()) {
			return null;
		}

		int i = 0;

		for (int k = 0; k < list.size(); k++) {
			if (list.get(k).id.equals(current)) {
				i = k;
			}
		}

		return list.get(Math.floorMod(i + step, list.size())).id;
	}

	public static String hint() {
		NationData n = picked();
		return String.format(tool.hint, n != null ? n.name : "?");
	}

	// ---------------------------------------------------------------- talking to the server

	public static void send(int action, String target, int x1, int z1, int x2, int z2) {
		if (ClientPlayNetworking.canSend(CreativeActionPayload.TYPE)) {
			boolean erase = action == CreativeActionPayload.PAINT && tool == Tool.ERASE;
			ClientPlayNetworking.send(new CreativeActionPayload(action, nation != null && !erase ? nation.toString() : "", target, x1, z1, x2, z2));
		}
	}

	public static void send(int action, String target) {
		send(action, target, 0, 0, 0, 0);
	}
}
