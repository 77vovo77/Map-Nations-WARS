package com.mapnationswars;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.mapnationswars.nation.DivisionData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.nation.Ranks;
import com.mapnationswars.network.CreativeActionPayload;
import com.mapnationswars.network.CreativeSyncPayload;

/**
 * Map Nations WARS 2.3: CREATIVEMOD.
 * A player in creative mode can switch it on in the map. Then the rules of the mod don't apply to them:
 * they command every army, give orders to every village, claim without paying, and the map gets god tools -
 * paint land for any nation, hand villages over, make armies appear, start and end wars, take over a nation.
 * Leaving creative mode switches it off.
 */
public final class WarsCreative {
	private static final Set<UUID> ON = new HashSet<>();
	private static MinecraftServer server;
	private static int ticks = 0;

	private WarsCreative() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			ON.clear();
			server = null;
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> ON.remove(handler.player.getUUID()));

		// leaving creative mode switches CREATIVEMOD off
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (++ticks % 20 != 0 || ON.isEmpty()) {
				return;
			}

			for (ServerPlayer p : PlayerLookup.all(s)) {
				if (ON.contains(p.getUUID()) && !p.isCreative()) {
					ON.remove(p.getUUID());
					sync(p);
					ServerNations.status(p, "CREATIVEMOD is off: you left creative mode.", false);
				}
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(CreativeActionPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer s = player.level().getServer();

			if (s != null) {
				s.execute(() -> handle(s, player, payload));
			}
		});
	}

	// ---------------------------------------------------------------- who has it

	/** Is CREATIVEMOD on for this player (and are they still in creative)? */
	static boolean on(ServerPlayer player) {
		return player != null && ON.contains(player.getUUID()) && player.isCreative();
	}

	/** The same, by player id (for the permission checks that only know the id). */
	static boolean on(UUID player) {
		if (player == null || !ON.contains(player) || server == null) {
			return false;
		}

		ServerPlayer p = server.getPlayerList().getPlayer(player);
		return p != null && p.isCreative();
	}

	private static void sync(ServerPlayer p) {
		if (ServerPlayNetworking.canSend(p, CreativeSyncPayload.TYPE)) {
			ServerPlayNetworking.send(p, new CreativeSyncPayload(on(p)));
		}
	}

	// ---------------------------------------------------------------- the tools

	private static UUID uuid(String s) {
		try {
			return s == null || s.isEmpty() ? null : UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static void handle(MinecraftServer s, ServerPlayer player, CreativeActionPayload a) {
		if (a.action() == CreativeActionPayload.TOGGLE) {
			if (!player.isCreative()) {
				ON.remove(player.getUUID());
				ServerNations.status(player, "CREATIVEMOD only works in creative mode.", false);
			} else if (ON.remove(player.getUUID())) {
				ServerNations.status(player, "CREATIVEMOD off. The rules apply to you again.", true);
			} else {
				ON.add(player.getUUID());
				ServerNations.status(player, "CREATIVEMOD on! You can do anything: use the tools on the left of the map.", true);
			}

			sync(player);
			return;
		}

		if (!on(player)) {
			ServerNations.status(player, "Switch CREATIVEMOD on first (creative mode only).", false);
			sync(player);
			return;
		}

		NationData n = ServerNations.nation(uuid(a.nation()));
		String dim = player.level().dimension().identifier().toString();

		switch (a.action()) {
			case CreativeActionPayload.PAINT -> paint(player, n, dim, a.x1(), a.z1(), a.x2(), a.z2());
			case CreativeActionPayload.GIVE_PROVINCE -> {
				ProvinceData p = WarsWorld.province(uuid(a.target()));

				if (p == null || n == null) {
					return;
				}

				String old = p.nation != null && ServerNations.nation(p.nation) != null ? ServerNations.nation(p.nation).name : "nobody";
				boolean gone = WarsWorld.transferProvince(p, n.id);
				p.unrest = 0;
				ServerNations.status(player, p.name + " now belongs to " + n.name + " (was " + old + ")." + (gone ? " " + old + " has fallen." : ""), true);
			}
			case CreativeActionPayload.SPAWN_ARMY -> {
				DivisionData.Kind kind = DivisionData.Kind.byName(a.target());

				if (n == null || kind == null) {
					return;
				}

				DivisionData d = WarsWar.spawnDivision(n, kind, dim, a.x1(), a.z1(), "");
				ServerNations.status(player, d.name + " of " + n.name + " appears with " + d.soldiers() + " soldiers.", true);
			}
			case CreativeActionPayload.DELETE_ARMY -> {
				DivisionData d = WarsWar.division(uuid(a.target()));

				if (d != null) {
					WarsWar.disbandQuietly(s, d);
					ServerNations.status(player, d.name + " is gone.", true);
				}
			}
			case CreativeActionPayload.TELEPORT_ARMY -> {
				DivisionData d = WarsWar.division(uuid(a.target()));

				if (d != null) {
					d.dimension = dim;
					d.x = a.x1();
					d.z = a.z1();
					d.goalX = d.x;
					d.goalZ = d.z;
					d.target = null;
					d.portal = null;
					d.state = DivisionData.State.IDLE;
					WarsTroops.forget(s, d.id); // its soldiers appear at the new place
					ServerNations.status(player, d.name + " is now at " + a.x1() + ", " + a.z1() + ".", true);
				}
			}
			case CreativeActionPayload.LEAD -> lead(s, player, n);
			case CreativeActionPayload.TREASURY -> {
				if (n != null) {
					n.treasury = Math.max(0, n.treasury + a.x1());
					ServerNations.status(player, n.name + "'s treasury: " + n.treasury + " emeralds.", true);
				}
			}
			case CreativeActionPayload.WAR, CreativeActionPayload.PEACE, CreativeActionPayload.ALLY -> {
				NationData other = ServerNations.nation(uuid(a.target()));

				if (n == null || other == null || n == other) {
					ServerNations.status(player, "Pick two different nations (the first and the \"with\" nation).", false);
					return;
				}

				relation(s, player, a.action(), n, other);
			}
			case CreativeActionPayload.CALM -> {
				if (n == null) {
					return;
				}

				for (ProvinceData p : WarsWorld.provinces()) {
					if (n.id.equals(p.nation)) {
						p.unrest = 0;
						p.happiness = 100;
						p.food = Math.max(p.food, 100);
					}
				}

				ServerNations.status(player, "Everyone in " + n.name + " is happy now.", true);
			}
			case CreativeActionPayload.BOOST -> {
				ProvinceData p = WarsWorld.province(uuid(a.target()));

				if (p == null) {
					return;
				}

				p.abandoned = false;
				p.population += 5;
				p.houses += 2;
				p.farms += 1;
				p.workshops += 1;
				p.food += 50;
				p.happiness = Math.min(100, p.happiness + 20);
				ServerNations.status(player, p.name + " grows: " + p.population + " villagers, " + p.houses + " houses.", true);
			}
			default -> {
				return;
			}
		}

		saveAndSync(s);
	}

	private static void saveAndSync(MinecraftServer s) {
		ServerNations.saveNow(s);
		WarsWorld.saveNow();
		WarsWar.saveNow();
		WarsDiplomacy.saveNow();
		ServerNations.broadcast(s);
		WarsWorld.broadcast(s);
		WarsWar.broadcast(s);
		WarsDiplomacy.broadcast(s);
	}

	/** Chunks become a nation's land (joined to its nearest province), or nobody's. */
	private static void paint(ServerPlayer player, NationData n, String dim, int x1, int z1, int x2, int z2) {
		int minX = Math.min(x1, x2);
		int maxX = Math.max(x1, x2);
		int minZ = Math.min(z1, z2);
		int maxZ = Math.max(z1, z2);

		if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > 4096) {
			ServerNations.status(player, "That is too much at once (4096 chunks at most).", false);
			return;
		}

		int done = 0;

		for (int cx = minX; cx <= maxX; cx++) {
			for (int cz = minZ; cz <= maxZ; cz++) {
				long key = MapNationsMod.chunkKey(cx, cz);

				// out of every province's land first
				for (ProvinceData p : WarsWorld.provinces()) {
					if (p.dimension.equals(dim) && p.area.remove((Long) key)) {
						p.chunks = p.area.size();
					}
				}

				ProvinceData home = n == null ? null : nearestProvince(n, dim, cx * 16 + 8, cz * 16 + 8);

				if (home != null) {
					home.area.add(key);
					home.chunks = home.area.size();
				}

				ServerNations.setOwner(dim, key, n == null ? null : n.id);
				done++;
			}
		}

		ServerNations.status(player, done + (done == 1 ? " chunk" : " chunks") + (n == null ? " are nobody's land now." : " now belong to " + n.name + "."), true);
	}

	private static ProvinceData nearestProvince(NationData n, String dim, double x, double z) {
		ProvinceData best = null;
		double bestD = Double.MAX_VALUE;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation) && p.dimension.equals(dim)) {
				double d = Math.hypot(p.x - x, p.z - z);

				if (d < bestD) {
					bestD = d;
					best = p;
				}
			}
		}

		return best;
	}

	/** You take over a nation: you leave your own and become its leader. */
	private static void lead(MinecraftServer s, ServerPlayer player, NationData n) {
		if (n == null) {
			return;
		}

		UUID me = player.getUUID();
		String name = player.getName().getString();
		NationData mine = ServerNations.nationOf(me);

		if (mine == n && n.leader.equals(me)) {
			ServerNations.status(player, "You already lead " + n.name + ".", false);
			return;
		}

		if (mine != null && mine != n) {
			boolean wasLeader = mine.leader.equals(me);
			mine.members.removeIf(m -> m.id().equals(me));
			ServerNations.removeMemberData(mine, me);

			if (mine.ai) {
				if (wasLeader) {
					mine.leader = UUID.randomUUID(); // the game rules it again
				}
			} else if (mine.members.isEmpty()) {
				ServerNations.disband(mine);
			} else if (wasLeader) {
				mine.leader = mine.members.get(0).id();
			}

			ServerNations.syncOfficers(mine);
		}

		UUID oldLeader = n.leader;
		ServerNations.addMember(n, me, name);
		n.leader = me;
		n.ranks.put(me, Ranks.MINISTER);

		if (oldLeader != null && !oldLeader.equals(me) && n.isMember(oldLeader)) {
			n.ranks.put(oldLeader, Ranks.MINISTER); // the old leader stays at court
			ServerNations.notifyPlayer(s, oldLeader, name + " took over " + n.name + " (CREATIVEMOD).");
		}

		ServerNations.syncOfficers(n);
		WarsDiplomacy.news(s, "♚ " + name + " is now the " + n.ideology.leaderTitle + " of " + n.name + ".");
		ServerNations.status(player, "You are now the " + n.ideology.leaderTitle + " of " + n.name + ".", true);
	}

	private static void relation(MinecraftServer s, ServerPlayer player, int action, NationData a, NationData b) {
		switch (action) {
			case CreativeActionPayload.WAR -> {
				if (WarsDiplomacy.atWar(a.id, b.id)) {
					ServerNations.status(player, a.name + " and " + b.name + " are already at war.", false);
					return;
				}

				WarsDiplomacy.startWar(s, a, b, false);
				ServerNations.status(player, a.name + " is now at war with " + b.name + ".", true);
			}
			case CreativeActionPayload.PEACE -> {
				a.allies.remove(b.id);
				b.allies.remove(a.id);

				if (WarsDiplomacy.atWar(a.id, b.id)) {
					WarsDiplomacy.endWar(a.id, b.id);
					WarsWar.onPeace(s, a, b);
					WarsDiplomacy.news(s, "☘ " + a.name + " and " + b.name + " made peace.");
				}

				ServerNations.status(player, a.name + " and " + b.name + " are at peace (and not allied).", true);
			}
			case CreativeActionPayload.ALLY -> {
				if (WarsDiplomacy.atWar(a.id, b.id)) {
					WarsDiplomacy.endWar(a.id, b.id);
					WarsWar.onPeace(s, a, b);
				}

				if (!a.allies.contains(b.id)) {
					a.allies.add(b.id);
				}

				if (!b.allies.contains(a.id)) {
					b.allies.add(a.id);
				}

				a.allyRequests.remove(b.id);
				b.allyRequests.remove(a.id);
				WarsDiplomacy.news(s, "✦ " + a.name + " and " + b.name + " are now allies.");
				ServerNations.status(player, a.name + " and " + b.name + " are allies.", true);
			}
			default -> {
			}
		}
	}

}
