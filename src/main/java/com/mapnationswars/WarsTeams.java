package com.mapnationswars;

import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;

/**
 * Map Nations WARS 2.2: who is on whose side.
 * Every soldier, guard and king is in its nation's scoreboard team: the game's own mobs then don't attack their comrades,
 * and their glowing outline in battle has (about) the nation's colour. On top of that, nobody can hurt a friend:
 * soldiers of the same or allied nations, members of their nation, or the villagers of a nation they are not at war with.
 */
public final class WarsTeams {
	private static final String[] COLOR_NAMES = {"BLACK", "DARK_BLUE", "DARK_GREEN", "DARK_AQUA", "DARK_RED", "DARK_PURPLE", "GOLD", "GRAY",
		"DARK_GRAY", "BLUE", "GREEN", "AQUA", "RED", "LIGHT_PURPLE", "YELLOW", "WHITE"};
	private static final int[] COLOR_RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
		0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

	private WarsTeams() {
	}

	static void init() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> allowed(victim, source.getEntity()));
	}

	// ---------------------------------------------------------------- teams

	private static String teamName(UUID nation) {
		return "mnw_" + nation.toString().substring(0, 8);
	}

	/** Puts a war mob into its nation's team (made on first use). */
	static void join(MinecraftServer server, Entity e, NationData n) {
		if (server == null || n == null) {
			return;
		}

		try {
			Scoreboard board = server.getScoreboard();
			PlayerTeam team = board.getPlayerTeam(teamName(n.id));

			if (team == null) {
				team = board.addPlayerTeam(teamName(n.id));
				team.setAllowFriendlyFire(false);
				setColor(team, n.color);
			}

			board.addPlayerToTeam(e.getScoreboardName(), team);
		} catch (RuntimeException ex) {
			MapNationsMod.LOGGER.warn("Map Nations WARS: could not put a soldier in its team", ex);
		}
	}

	static void leave(MinecraftServer server, Entity e) {
		if (server == null || e == null) {
			return;
		}

		try {
			server.getScoreboard().removePlayerFromTeam(e.getScoreboardName());
		} catch (RuntimeException ignored) {
			// it wasn't in a team
		}
	}

	/** The team colour closest to the nation's colour (set by name: 26.x has its own TeamColor type). */
	private static void setColor(PlayerTeam team, int rgb) {
		int best = 15;
		double bestD = Double.MAX_VALUE;

		for (int i = 0; i < COLOR_RGB.length; i++) {
			int c = COLOR_RGB[i];
			double d = Math.pow((c >> 16 & 0xFF) - (rgb >> 16 & 0xFF), 2) + Math.pow((c >> 8 & 0xFF) - (rgb >> 8 & 0xFF), 2) + Math.pow((c & 0xFF) - (rgb & 0xFF), 2);

			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}

		try {
			Class<?> type = Class.forName("net.minecraft.world.scores.TeamColor");
			Object value = null;

			for (Object constant : type.getEnumConstants() != null ? type.getEnumConstants() : new Object[0]) {
				String name = constant instanceof Enum<?> en ? en.name() : constant.toString();

				if (name.equalsIgnoreCase(COLOR_NAMES[best])) {
					value = constant;
				}
			}

			if (value == null) {
				// not an enum: look for a constant field with that name
				value = type.getField(COLOR_NAMES[best]).get(null);
			}

			team.setColor(Optional.of((net.minecraft.world.scores.TeamColor) value));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
			// no colour: the outline stays white
		}
	}

	// ---------------------------------------------------------------- friendly fire

	/** The nation a war mob (soldier, guard, king) fights for, or null. */
	static UUID nationOfMob(UUID entity) {
		UUID n = WarsTroops.nationOf(entity);

		if (n == null) {
			n = WarsGuards.nationOf(entity);
		}

		if (n == null) {
			n = WarsKings.nationOfKing(entity);
		}

		return n;
	}

	private static boolean friends(UUID a, UUID b) {
		if (a.equals(b)) {
			return true;
		}

		NationData na = ServerNations.nation(a);
		return na != null && na.allies.contains(b);
	}

	/** May "attacker" hurt "victim"? */
	private static boolean allowed(LivingEntity victim, Entity attacker) {
		if (attacker == null || attacker == victim) {
			return true;
		}

		UUID victimSide = nationOfMob(victim.getUUID());
		UUID attackerSide = nationOfMob(attacker.getUUID());

		if (victimSide == null && victim instanceof ServerPlayer p) {
			NationData pn = ServerNations.nationOf(p.getUUID());
			victimSide = pn != null ? pn.id : null;

			// soldiers only hurt players who are their enemies
			if (attackerSide != null) {
				NationData an = ServerNations.nation(attackerSide);
				return an == null || WarsPeople.hostileTo(p, an);
			}
		}

		if (attackerSide == null && attacker instanceof ServerPlayer p) {
			NationData pn = ServerNations.nationOf(p.getUUID());
			attackerSide = pn != null ? pn.id : null;

			if (nationOfMob(victim.getUUID()) == null) {
				return true; // a player hitting an ordinary mob
			}
		}

		if (attackerSide == null) {
			return true; // a wild monster
		}

		if (victimSide != null) {
			return !friends(attackerSide, victimSide);
		}

		// a soldier hitting an ordinary villager or village golem: only in the land of an enemy
		if (victim instanceof Villager || victim.getClass().getSimpleName().equals("IronGolem")) {
			ProvinceData p = nearestProvince(victim);
			return p != null && p.nation != null && WarsWar.hostile(attackerSide, p.nation);
		}

		return true;
	}

	private static ProvinceData nearestProvince(Entity e) {
		String dim = e.level().dimension().identifier().toString();
		ProvinceData best = null;
		double bestD = 120;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (p.dimension.equals(dim)) {
				double d = Math.hypot(p.x - e.getX(), p.z - e.getZ());

				if (d < bestD) {
					bestD = d;
					best = p;
				}
			}
		}

		return best;
	}
}
