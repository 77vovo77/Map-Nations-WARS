package com.mapnationswars;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;

import com.mapnationswars.nation.Faction;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;

/**
 * Map Nations WARS 1.9: village guards.
 * Come close to a village of a nation you are at war with - your nation's war, your own personal war,
 * or because they think you are an outlaw - and its guards come out to kill you:
 * iron golems for villagers, vindicators and pillagers for illagers, brutes for piglins, husks and skeletons for the dead.
 * They go back when you leave or when there is peace.
 */
public final class WarsGuards {
	/** Guards come out when you are this close to a village's centre. */
	private static final double ALARM = 72;
	/** ...and go back when you are this far. */
	private static final double GIVE_UP = 120;

	private record Guard(UUID province, UUID nation, UUID target, String dimension) {
	}

	private static final Map<UUID, Guard> GUARDS = new HashMap<>();
	/** "player|province" pairs already warned */
	private static final Set<String> WARNED = new HashSet<>();
	private static final Random RANDOM = new Random();
	private static int ticks = 0;

	private WarsGuards() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			removeAll(server);
			WARNED.clear();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 40 == 0 && WarsWorld.isGenerated()) {
				tick(server);
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			Guard g = GUARDS.remove(entity.getUUID());

			if (g != null && source.getEntity() instanceof ServerPlayer killer) {
				MinecraftServer server = killer.level().getServer();
				WarsPeople.changeStanding(killer, g.nation(), -5);
				NationData mine = ServerNations.nationOf(killer.getUUID());

				if (mine != null && WarsDiplomacy.atWar(mine.id, g.nation()) && server != null) {
					WarsPolitics.addMerit(server, mine, killer.getUUID(), 2);
				}

				if (server != null) {
					WarsDuties.onEnemyKilled(server, killer);
				}
			}
		});
	}

	private static void tick(MinecraftServer server) {
		// guards whose target left (or made peace) go back
		for (Iterator<Map.Entry<UUID, Guard>> it = GUARDS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Guard> e = it.next();
			Guard g = e.getValue();
			ServerLevel level = WarsWorld.levelOf(server, g.dimension());
			Entity mob = level != null ? level.getEntity(e.getKey()) : null;
			ServerPlayer target = player(server, g.target());
			ProvinceData p = WarsWorld.province(g.province());
			NationData n = ServerNations.nation(g.nation());
			boolean keep = mob != null && mob.isAlive() && target != null && p != null && n != null && g.nation().equals(p.nation)
					&& target.level().dimension().identifier().toString().equals(g.dimension())
					&& Math.hypot(target.getX() - p.x, target.getZ() - p.z) < GIVE_UP && WarsPeople.hostileTo(target, n);

			if (!keep) {
				if (mob != null) {
					mob.discard();
				}

				it.remove();
			} else if (mob instanceof Mob m) {
				m.setTarget(target); // keep after them
			}
		}

		for (ServerPlayer player : PlayerLookup.all(server)) {
			if (player.isSpectator()) {
				continue;
			}

			String dim = player.level().dimension().identifier().toString();

			for (ProvinceData p : WarsWorld.provinces()) {
				if (p.abandoned || p.nation == null || !p.dimension.equals(dim) || Math.hypot(p.x - player.getX(), p.z - player.getZ()) > ALARM) {
					continue;
				}

				NationData n = ServerNations.nation(p.nation);

				if (n == null || !WarsPeople.hostileTo(player, n)) {
					continue;
				}

				String key = player.getUUID() + "|" + p.id;

				if (WARNED.add(key)) {
					player.sendSystemMessage(Component.literal("⚠ The guards of " + p.name + " (" + n.name + ") come for you!").withColor(0xFF6050));
					ServerNations.status(player, "⚠ The guards of " + p.name + " attack you!", false);
				}

				int alive = 0;

				for (Guard g : GUARDS.values()) {
					if (g.province().equals(p.id) && g.target().equals(player.getUUID())) {
						alive++;
					}
				}

				if (alive < wanted(p, n)) {
					spawn(server, player, p, n);
				}
			}
		}

		// forget warnings for places the player has left, so they are warned again next time
		WARNED.removeIf(k -> {
			String[] parts = k.split("\\|");
			ServerPlayer pl = player(server, UUID.fromString(parts[0]));
			ProvinceData p = WarsWorld.province(UUID.fromString(parts[1]));
			return pl == null || p == null || Math.hypot(p.x - pl.getX(), p.z - pl.getZ()) > GIVE_UP;
		});
	}

	private static int wanted(ProvinceData p, NationData n) {
		int base = switch (p.type) {
			case VILLAGE -> 2 + p.houses / 4;
			case OUTPOST -> 4;
			case MANSION, BASTION -> 5;
		};

		if (n.faction == Faction.VILLAGER || n.faction == Faction.PLAYER) {
			base = Math.min(base, 3); // golems are strong
		}

		return Math.min(5, base);
	}

	private static String guardType(Faction f, boolean nether) {
		boolean ranged = RANDOM.nextInt(3) == 0;

		return switch (f) {
			case ILLAGER -> ranged ? "PILLAGER" : "VINDICATOR";
			case PIGLIN -> nether ? (ranged ? "PIGLIN" : "PIGLIN_BRUTE") : "ZOMBIFIED_PIGLIN";
			case UNDEAD -> ranged ? "SKELETON" : "HUSK";
			default -> "IRON_GOLEM";
		};
	}

	private static void spawn(MinecraftServer server, ServerPlayer target, ProvinceData p, NationData n) {
		ServerLevel level = WarsWorld.levelOf(server, p.dimension);

		if (level == null) {
			return;
		}

		// they come out of the village, between its centre and you
		double t = 0.35 + RANDOM.nextDouble() * 0.3;
		int x = (int) Math.floor(p.x + (target.getX() - p.x) * t) + RANDOM.nextInt(7) - 3;
		int z = (int) Math.floor(p.z + (target.getZ() - p.z) * t) + RANDOM.nextInt(7) - 3;

		if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
			return;
		}

		boolean nether = "minecraft:the_nether".equals(p.dimension);
		int y = nether ? (int) Math.floor(target.getY()) : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		Mob mob = WarsWar.spawnMob(level, guardType(n.faction, nether), x, y, z);

		if (mob == null) {
			return;
		}

		if (n.faction == Faction.UNDEAD) {
			mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHAINMAIL_HELMET));
			mob.setDropChance(net.minecraft.world.entity.EquipmentSlot.HEAD, 0f);
		}

		WarsTeams.join(server, mob, n);
		mob.setCustomName(Component.literal(WarsWar.soldierPrefix() + "Guard of " + p.name).withColor(n.color));
		mob.setCustomNameVisible(true);
		mob.setTarget(target);
		GUARDS.put(mob.getUUID(), new Guard(p.id, n.id, target.getUUID(), p.dimension));
	}

	static UUID nationOf(UUID entity) {
		Guard g = GUARDS.get(entity);
		return g == null ? null : g.nation();
	}

	private static ServerPlayer player(MinecraftServer server, UUID id) {
		for (ServerPlayer p : PlayerLookup.all(server)) {
			if (p.getUUID().equals(id)) {
				return p;
			}
		}

		return null;
	}

	private static void removeAll(MinecraftServer server) {
		for (Map.Entry<UUID, Guard> e : GUARDS.entrySet()) {
			ServerLevel level = WarsWorld.levelOf(server, e.getValue().dimension());
			Entity mob = level != null ? level.getEntity(e.getKey()) : null;

			if (mob != null) {
				mob.discard();
			}
		}

		GUARDS.clear();
	}
}
