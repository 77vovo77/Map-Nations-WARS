package com.mapnationswars;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.nation.Ranks;

/**
 * Map Nations WARS 1.9: nations claim land.
 * Every province's land can grow into the wild land around it. Nations ruled by the game expand by themselves every day
 * (happy villages grow faster). Leaders and Ministers of player-led nations claim land on the map (Claim land button),
 * next to their own land and near one of their provinces, for 2 emeralds a chunk from the treasury.
 */
public final class WarsLand {
	public static final int PLAYER_COST = 2;
	/** How far (in chunks) from its centre a province's land can reach. */
	private static final int PLAYER_REACH = 12;

	private WarsLand() {
	}

	private static int reach(ProvinceData p) {
		return switch (p.type) {
			case VILLAGE -> 8;
			case OUTPOST -> 4;
			case MANSION -> 8;
			case BASTION -> 5;
		};
	}

	private static long key(int cx, int cz) {
		return MapNationsMod.chunkKey(cx, cz);
	}

	private static boolean free(String dim, int cx, int cz) {
		return ServerNations.nationAt(dim, cx, cz) == null;
	}

	private static boolean inBorder(ServerLevel level, int cx, int cz) {
		return level == null || level.getWorldBorder().isWithinBounds(new BlockPos(cx * 16 + 8, 64, cz * 16 + 8));
	}

	private static void give(ProvinceData p, int cx, int cz) {
		long k = key(cx, cz);

		if (!p.area.contains(k)) {
			p.area.add(k);
			p.chunks = p.area.size();
		}

		ServerNations.setOwner(p.dimension, k, p.nation);
	}

	// ---------------------------------------------------------------- the game's nations grow by themselves

	static void runDay(MinecraftServer server) {
		boolean changed = false;

		for (ProvinceData p : new ArrayList<>(WarsWorld.provinces())) {
			NationData n = ServerNations.nation(p.nation);

			if (n == null || !n.aiRuled() || p.abandoned || n.treasury < 10) {
				continue;
			}

			int grow = p.happiness >= 60 ? 3 : p.happiness >= 40 ? 2 : 1;
			ServerLevel level = WarsWorld.levelOf(server, p.dimension);
			int pcx = Math.floorDiv(p.x, 16);
			int pcz = Math.floorDiv(p.z, 16);
			int r = reach(p);

			for (int i = 0; i < grow && n.treasury >= 10; i++) {
				// the free chunk next to its land that is closest to its centre
				int[] best = null;
				double bestD = Double.MAX_VALUE;
				Set<Long> area = new HashSet<>(p.area);

				for (long k : area) {
					int cx = MapNationsMod.keyX(k);
					int cz = MapNationsMod.keyZ(k);
					int[][] next = {{cx + 1, cz}, {cx - 1, cz}, {cx, cz + 1}, {cx, cz - 1}};

					for (int[] c : next) {
						double d = Math.hypot(c[0] - pcx, c[1] - pcz);

						if (d <= r && d < bestD && !area.contains(key(c[0], c[1])) && free(p.dimension, c[0], c[1]) && inBorder(level, c[0], c[1])) {
							bestD = d;
							best = c;
						}
					}
				}

				if (best == null) {
					break;
				}

				give(p, best[0], best[1]);
				n.treasury -= 1;
				changed = true;
			}
		}

		if (changed) {
			WarsWorld.saveNow();
			ServerNations.saveNow(server);
		}
	}

	// ---------------------------------------------------------------- players claim on the map

	private static boolean mayClaim(NationData n, ServerPlayer player) {
		return n != null && (n.leader.equals(player.getUUID()) || n.rankOf(player.getUUID()) >= Ranks.MINISTER);
	}

	/** The province of this nation whose centre is closest to the chunk (within reach), or null. */
	private static ProvinceData provinceFor(NationData n, String dim, int cx, int cz) {
		ProvinceData best = null;
		double bestD = PLAYER_REACH + 0.5;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation) && p.dimension.equals(dim) && !p.abandoned) {
				double d = Math.hypot(Math.floorDiv(p.x, 16) - cx, Math.floorDiv(p.z, 16) - cz);

				if (d < bestD) {
					bestD = d;
					best = p;
				}
			}
		}

		return best;
	}

	/** Claims (or gives up) the chunks in a rectangle. One chunk = a rectangle of one. */
	static void claim(MinecraftServer server, ServerPlayer player, int fromX, int fromZ, int toX, int toZ, boolean claim) {
		NationData n = ServerNations.nationOf(player.getUUID());

		if (n == null) {
			ServerNations.status(player, "Join a nation first. Land belongs to nations.", false);
			return;
		}

		if (!mayClaim(n, player)) {
			ServerNations.status(player, "Only the " + n.ideology.leaderTitle + " and Ministers of " + n.name + " claim land.", false);
			return;
		}

		String dim = player.level().dimension().identifier().toString();
		ServerLevel level = WarsWorld.levelOf(server, dim);
		int minX = Math.min(fromX, toX);
		int maxX = Math.max(fromX, toX);
		int minZ = Math.min(fromZ, toZ);
		int maxZ = Math.max(fromZ, toZ);

		if ((maxX - minX + 1) * (maxZ - minZ + 1) > 256) {
			ServerNations.status(player, "That is too much at once (256 chunks at most).", false);
			return;
		}

		int done = 0;
		String problem = null;

		if (claim) {
			// grow outwards from our land, pass after pass, so a whole rectangle next to our border works
			boolean progress = true;

			while (progress) {
				progress = false;

				for (int cx = minX; cx <= maxX; cx++) {
					for (int cz = minZ; cz <= maxZ; cz++) {
						if (!free(dim, cx, cz)) {
							NationData owner = ServerNations.nationAt(dim, cx, cz);

							if (owner != n && problem == null) {
								problem = "Some of it belongs to " + owner.name + ". Land of other nations is taken in war.";
							}

							continue;
						}

						if (!inBorder(level, cx, cz)) {
							problem = "Part of it is outside the world border.";
							continue;
						}

						boolean touches = own(n, dim, cx + 1, cz) || own(n, dim, cx - 1, cz) || own(n, dim, cx, cz + 1) || own(n, dim, cx, cz - 1);
						ProvinceData p = provinceFor(n, dim, cx, cz);

						if (!touches) {
							if (problem == null) {
								problem = "Land must touch your own land.";
							}

							continue;
						}

						if (p == null) {
							problem = "Too far from your provinces (" + PLAYER_REACH + " chunks at most).";
							continue;
						}

						if (n.treasury < PLAYER_COST) {
							problem = "The treasury is empty (" + PLAYER_COST + " emeralds a chunk).";
							progress = false;
							break;
						}

						n.treasury -= PLAYER_COST;
						give(p, cx, cz);
						done++;
						progress = true;
					}
				}
			}
		} else {
			for (int cx = minX; cx <= maxX; cx++) {
				for (int cz = minZ; cz <= maxZ; cz++) {
					if (!own(n, dim, cx, cz)) {
						continue;
					}

					long k = key(cx, cz);
					boolean centre = false;

					for (ProvinceData p : WarsWorld.provinces()) {
						if (p.dimension.equals(dim) && Math.floorDiv(p.x, 16) == cx && Math.floorDiv(p.z, 16) == cz) {
							centre = true;
						}
					}

					if (centre) {
						problem = "The heart of a province can't be given up.";
						continue;
					}

					for (ProvinceData p : WarsWorld.provinces()) {
						if (p.area.remove(k)) {
							p.chunks = p.area.size();
						}
					}

					ServerNations.setOwner(dim, k, null);
					done++;
				}
			}
		}

		if (done > 0) {
			ServerNations.status(player, claim ? "Claimed " + done + " chunk" + (done == 1 ? "" : "s") + " for " + done * PLAYER_COST
					+ " emeralds." + (problem != null ? " (" + problem + ")" : "") : "Gave up " + done + " chunk" + (done == 1 ? "" : "s") + ".", true);
			WarsWorld.saveNow();
			ServerNations.saveNow(server);
			ServerNations.broadcast(server);
			WarsWorld.broadcast(server);
		} else {
			ServerNations.status(player, problem != null ? problem : "Nothing to claim there.", false);
		}
	}

	private static boolean own(NationData n, String dim, int cx, int cz) {
		NationData o = ServerNations.nationAt(dim, cx, cz);
		return o != null && o.id.equals(n.id);
	}
}
