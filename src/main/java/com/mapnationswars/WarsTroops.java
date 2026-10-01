package com.mapnationswars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import com.mapnationswars.nation.DivisionData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;

/**
 * Map Nations WARS 2.0: armies you can see.
 * When a player is near a division, it stands in the world as real soldiers (one mob for every few soldiers):
 * villager militia with swords, villager bowmen, iron golems; vindicators, pillagers and ravagers; brutes, piglins and hoglins;
 * husks, skeletons and wither skeletons. They march in formation where the division marches and fight the troops
 * of nations at war with them - and enemy players. Every soldier killed in the world is lost by the division.
 * Besieged provinces send their own defenders out. When nobody is near, the soldiers go back into the map.
 */
public final class WarsTroops {
	/** Players this close make a division appear in the world. */
	private static final double SHOW = 128;
	/** ...and further than this make it go away. */
	private static final double HIDE = 176;
	private static final double ENGAGE = 24;

	/** group = the division (or the besieged province, for its defenders) */
	private record Troop(UUID group, UUID nation, String dimension, boolean garrison, boolean archer, boolean villager, int slot, int perTroop) {
	}

	private static final Map<UUID, Troop> TROOPS = new HashMap<>();
	/** villager soldiers: who they fight, and when they may strike again */
	private static final Map<UUID, UUID> ENGAGED = new HashMap<>();
	private static final Map<UUID, Long> COOLDOWN = new HashMap<>();
	private static long tickCount = 0;

	private WarsTroops() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STOPPING.register(WarsTroops::removeAll);

		// villager soldiers fight by hand (they don't know how by themselves): twice a second
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++tickCount % 10 == 0 && !ENGAGED.isEmpty()) {
				villagersFight(server);
			}

			if (tickCount % 5 == 0 && !BOATS.isEmpty()) {
				row(server);
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			Troop t = TROOPS.remove(entity.getUUID());

			if (t == null) {
				return;
			}

			ENGAGED.remove(entity.getUUID());
			COOLDOWN.remove(entity.getUUID());
			WarsWar.untrack(entity.getUUID());
			dropBoat(entity.level() instanceof ServerLevel sl ? sl : null, entity.getUUID());
			LAST_POS.remove(entity.getUUID());
			STUCK.remove(entity.getUUID());

			if (t.garrison()) {
				WarsWar.garrisonKilled(t.group());
			} else {
				DivisionData d = WarsWar.division(t.group());

				if (d != null) {
					d.strength = Math.max(0, d.strength - t.perTroop());
					d.morale = Math.max(0, d.morale - 2);
				}
			}

			if (source.getEntity() instanceof ServerPlayer killer) {
				MinecraftServer server = killer.level().getServer();
				NationData kn = ServerNations.nationOf(killer.getUUID());

				if (server != null) {
					if (kn != null && WarsWar.hostile(kn.id, t.nation())) {
						WarsPolitics.addMerit(server, kn, killer.getUUID(), 3);
					}

					WarsPeople.changeStanding(killer, t.nation(), -2);
					WarsDuties.onEnemyKilled(server, killer);
				}
			}
		});
	}

	// ---------------------------------------------------------------- once a second (from WarsWar)

	static void tick(MinecraftServer server) {
		// soldiers whose division is gone go away
		forgetIf(server, t -> t.garrison() ? !isBesieged(t.group()) : WarsWar.division(t.group()) == null);

		for (DivisionData d : new ArrayList<>(WarsWar.divisions())) {
			NationData n = ServerNations.nation(d.nation);
			ServerLevel level = WarsWorld.levelOf(server, d.dimension);

			if (n == null || level == null) {
				continue;
			}

			boolean show = playerNear(server, d.dimension, d.x, d.z, SHOW) && level.getChunkSource().hasChunk(floor(d.x) >> 4, floor(d.z) >> 4);
			boolean keep = show || playerNear(server, d.dimension, d.x, d.z, HIDE);
			List<Map.Entry<UUID, Troop>> mine = troopsOf(d.id);

			if (!keep) {
				forget(server, d.id);
				continue;
			}

			int desired = Math.min(d.kind.maxTroops(), (int) Math.ceil(d.strength / d.kind.perTroop));

			// the division lost soldiers on the map: some of the ones you see fall back
			for (int i = mine.size() - 1; i >= desired && i >= 0; i--) {
				discard(level, mine.get(i).getKey());
			}

			mine = troopsOf(d.id);

			if (show && mine.size() < desired) {
				for (int i = 0; i < 2 && mine.size() + i < desired; i++) {
					spawn(level, n, d.id, d.kind, false, d.x, d.z, freeSlot(mine, i));
				}

				mine = troopsOf(d.id);
			}

			for (Map.Entry<UUID, Troop> e : mine) {
				control(server, level, e.getKey(), e.getValue(), d.x, d.z);
			}
		}

		// besieged provinces send defenders out when someone is there to see it
		for (ProvinceData p : WarsWar.activeSieges()) {
			NationData n = ServerNations.nation(p.nation);
			ServerLevel level = WarsWorld.levelOf(server, p.dimension);

			if (n == null || level == null) {
				continue;
			}

			List<Map.Entry<UUID, Troop>> mine = troopsOf(p.id);

			if (!playerNear(server, p.dimension, p.x, p.z, HIDE)) {
				forget(server, p.id);
				continue;
			}

			int desired = Math.min(6, 1 + (int) (WarsWar.garrison(p) / 15));

			if (playerNear(server, p.dimension, p.x, p.z, SHOW) && mine.size() < desired && level.getChunkSource().hasChunk(p.x >> 4, p.z >> 4)) {
				spawn(level, n, p.id, DivisionData.Kind.INFANTRY, true, p.x, p.z, freeSlot(mine, 0));
				mine = troopsOf(p.id);
			}

			for (Map.Entry<UUID, Troop> e : mine) {
				control(server, level, e.getKey(), e.getValue(), p.x, p.z);
			}
		}
	}

	private static boolean isBesieged(UUID province) {
		for (ProvinceData p : WarsWar.activeSieges()) {
			if (p.id.equals(province)) {
				return true;
			}
		}

		return false;
	}

	private static int floor(double v) {
		return (int) Math.floor(v);
	}

	private static boolean playerNear(MinecraftServer server, String dim, double x, double z, double range) {
		for (ServerPlayer p : PlayerLookup.all(server)) {
			if (!p.isSpectator() && p.level().dimension().identifier().toString().equals(dim) && Math.hypot(p.getX() - x, p.getZ() - z) < range) {
				return true;
			}
		}

		return false;
	}

	private static List<Map.Entry<UUID, Troop>> troopsOf(UUID group) {
		List<Map.Entry<UUID, Troop>> list = new ArrayList<>();

		for (Map.Entry<UUID, Troop> e : TROOPS.entrySet()) {
			if (e.getValue().group().equals(group)) {
				list.add(e);
			}
		}

		list.sort((a, b) -> Integer.compare(a.getValue().slot(), b.getValue().slot()));
		return list;
	}

	private static int freeSlot(List<Map.Entry<UUID, Troop>> mine, int skip) {
		for (int slot = 0; ; slot++) {
			boolean used = false;

			for (Map.Entry<UUID, Troop> e : mine) {
				used |= e.getValue().slot() == slot;
			}

			if (!used && skip-- <= 0) {
				return slot;
			}
		}
	}

	// ---------------------------------------------------------------- making soldiers

	private static void spawn(ServerLevel level, NationData n, UUID group, DivisionData.Kind kind, boolean garrison, double cx, double cz, int slot) {
		boolean nether = "minecraft:the_nether".equals(level.dimension().identifier().toString());
		double[] at = formation(cx, cz, slot);
		int x = floor(at[0]);
		int z = floor(at[1]);

		if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
			return;
		}

		int y = nether ? floor(nearestPlayerY(level, x, z)) : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		String type = kind.troopType(n.faction, nether);
		Mob mob = WarsWar.spawnMob(level, type, x, y, z);

		if (mob == null) {
			return;
		}

		boolean archer = kind == DivisionData.Kind.ARCHERS;
		boolean villager = mob instanceof Villager;

		if (mob instanceof Villager v) {
			// a villager soldier: no villager business, a sword (or a bow) and some armour
			try {
				v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(), archer ? VillagerProfession.FLETCHER : VillagerProfession.ARMORER));
				v.setVillagerDataFinalized(true);
			} catch (Exception ignored) {
				// keeps its looks
			}

			// no villager business (after the profession is set, so nothing rebuilds it)
			v.getBrain().removeAllBehaviors();
			v.getBrain().clearMemories();

			v.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(archer ? Items.BOW : Items.IRON_SWORD));
			setHealth(v, 30);
		}

		// piglins and hoglins stay themselves in the Overworld
		try {
			mob.getClass().getMethod("setImmuneToZombification", boolean.class).invoke(mob, true);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// not a piglin
		}

		// the undead wear helmets so the sun doesn't burn them
		if (n.faction == com.mapnationswars.nation.Faction.UNDEAD) {
			mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
			mob.setDropChance(EquipmentSlot.HEAD, 0f);
		}

		mob.setDropChance(EquipmentSlot.MAINHAND, 0f);
		mob.setPersistenceRequired();
		mob.setCustomName(Component.literal(WarsWar.soldierPrefix() + (garrison ? "Defender" : kind.unitName(n.faction))).withColor(n.color));
		mob.setCustomNameVisible(false);
		TROOPS.put(mob.getUUID(), new Troop(group, n.id, level.dimension().identifier().toString(), garrison, archer, villager, slot, kind.perTroop));
	}

	private static double nearestPlayerY(ServerLevel level, int x, int z) {
		double best = Double.MAX_VALUE;
		double y = 64;

		for (ServerPlayer p : level.players()) {
			double d = Math.hypot(p.getX() - x, p.getZ() - z);

			if (d < best) {
				best = d;
				y = p.getY();
			}
		}

		return y;
	}

	private static void setHealth(LivingEntity e, double health) {
		AttributeInstance max = e.getAttribute(Attributes.MAX_HEALTH);

		if (max != null) {
			max.setBaseValue(health);
			e.setHealth((float) health);
		}
	}

	/** Where soldier number "slot" stands: rows of 4, two blocks apart. */
	private static double[] formation(double cx, double cz, int slot) {
		int row = slot / 4;
		int col = slot % 4;
		return new double[] {cx + (col - 1.5) * 2.2, cz + row * 2.2 - 2};
	}

	// ---------------------------------------------------------------- what each soldier does

	private static void control(MinecraftServer server, ServerLevel level, UUID id, Troop t, double cx, double cz) {
		Entity e = level.getEntity(id);

		if (!(e instanceof Mob mob) || !mob.isAlive()) {
			TROOPS.remove(id);
			ENGAGED.remove(id);
			WarsWar.untrack(id);
			return;
		}

		LivingEntity enemy = enemyNear(server, level, mob, t);

		if (t.villager()) {
			if (enemy != null) {
				ENGAGED.put(id, enemy.getUUID());
				return;
			}

			ENGAGED.remove(id);
		} else {
			if (enemy != null) {
				mob.setTarget(enemy);
				return;
			}

			// don't let them chase friends (their own players, or players not at war with them)
			LivingEntity current = mob.getTarget();

			if (current instanceof ServerPlayer p) {
				NationData n = ServerNations.nation(t.nation());

				if (n == null || !WarsPeople.hostileTo(p, n)) {
					mob.setTarget(null);
				}
			}
		}

		// no enemy: keep formation where the division (or the province) is
		double[] at = formation(cx, cz, t.slot());
		double dist = Math.hypot(mob.getX() - at[0], mob.getZ() - at[1]);

		// across water: into a boat (and out of it again on the other shore)
		if (boats(level, id, mob, at, dist)) {
			return;
		}

		// stuck somewhere (a hole, a wall, a tree): after a few seconds without getting closer, go straight to its place
		double[] last = LAST_POS.put(id, new double[] {mob.getX(), mob.getZ()});
		int stuck = last != null && dist > 4 && Math.hypot(mob.getX() - last[0], mob.getZ() - last[1]) < 0.4 ? STUCK.merge(id, 1, Integer::sum) : 0;

		if (stuck == 0) {
			STUCK.remove(id);
		}

		if (dist > 48 || stuck >= 6) {
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, floor(at[0]), floor(at[1]));
			mob.teleportTo(at[0], y, at[1]);
			STUCK.remove(id);
		} else if (dist > 2.5) {
			mob.getNavigation().moveTo(at[0], mob.getY(), at[1], t.villager() ? 0.75 : 1.0);
		}
	}

	// ---------------------------------------------------------------- boats (2.1)

	private static final Map<UUID, UUID> BOATS = new HashMap<>();
	private static final Map<UUID, double[]> LAST_POS = new HashMap<>();
	private static final Map<UUID, Integer> STUCK = new HashMap<>();
	/** troop -> where its boat is going */
	private static final Map<UUID, double[]> SAILING = new HashMap<>();

	private static boolean water(ServerLevel level, double x, double z) {
		int bx = floor(x);
		int bz = floor(z);

		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return false;
		}

		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
		return level.getBlockState(new net.minecraft.core.BlockPos(bx, y - 1, bz)).liquid();
	}

	/**
	 * Soldiers who have to cross water get a boat; on the far shore they step out and the boat is gone.
	 * Returns true while the soldier is handled by its boat.
	 */
	private static boolean boats(ServerLevel level, UUID id, Mob mob, double[] at, double dist) {
		UUID boatId = BOATS.get(id);
		Entity boat = boatId != null ? level.getEntity(boatId) : null;

		if (boat != null && mob.isPassenger()) {
			boolean shore = !water(level, at[0], at[1]) && dist < 6;

			if (shore || dist < 2) {
				mob.stopRiding();
				boat.discard();
				BOATS.remove(id);
				SAILING.remove(id);
				return false;
			}

			SAILING.put(id, at);
			return true;
		}

		if (boat != null) {
			boat.discard(); // fell out of it
			BOATS.remove(id);
			SAILING.remove(id);
		}

		boolean wet = mob.isInWater() || (dist > 3 && water(level, (mob.getX() + at[0]) / 2, (mob.getZ() + at[1]) / 2) && water(level, at[0], at[1]));

		if (!wet || dist < 3) {
			return false;
		}

		Entity newBoat = WarsWar.spawnEntity(level, "OAK_BOAT", floor(mob.getX()), floor(mob.getY()), floor(mob.getZ()));

		if (newBoat == null) {
			return false;
		}

		if (!mob.startRiding(newBoat)) {
			// too big for a boat (golems, ravagers): they wade and swim instead, or jump ahead
			newBoat.discard();
			return false;
		}

		BOATS.put(id, newBoat.getUUID());
		SAILING.put(id, at);
		return true;
	}

	/** Boats are rowed a little every half second towards where their soldier has to go. */
	private static void row(MinecraftServer server) {
		for (Map.Entry<UUID, UUID> e : new ArrayList<>(BOATS.entrySet())) {
			Troop t = TROOPS.get(e.getKey());
			ServerLevel level = t != null ? WarsWorld.levelOf(server, t.dimension()) : null;
			Entity boat = level != null ? level.getEntity(e.getValue()) : null;
			double[] to = SAILING.get(e.getKey());

			if (boat == null || to == null) {
				continue;
			}

			double dx = to[0] - boat.getX();
			double dz = to[1] - boat.getZ();
			double d = Math.hypot(dx, dz);

			if (d > 0.5) {
				boat.setDeltaMovement(dx / d * 0.45, 0, dz / d * 0.45);
				boat.setYRot((float) (Math.toDegrees(Math.atan2(-dx, dz))));
			}
		}
	}

	/** The closest enemy soldier, defender, or hostile player within reach. */
	private static LivingEntity enemyNear(MinecraftServer server, ServerLevel level, Mob mob, Troop t) {
		LivingEntity best = null;
		double bestD = ENGAGE;

		for (Map.Entry<UUID, Troop> e : TROOPS.entrySet()) {
			Troop o = e.getValue();

			if (!o.dimension().equals(t.dimension()) || !WarsWar.hostile(t.nation(), o.nation())) {
				continue;
			}

			Entity other = level.getEntity(e.getKey());

			if (other instanceof LivingEntity le && le.isAlive()) {
				double d = mob.distanceTo(le);

				if (d < bestD) {
					bestD = d;
					best = le;
				}
			}
		}

		NationData n = ServerNations.nation(t.nation());

		for (ServerPlayer p : level.players()) {
			if (n != null && !p.isSpectator() && !p.isCreative() && WarsPeople.hostileTo(p, n)) {
				double d = mob.distanceTo(p);

				if (d < Math.min(bestD, 16)) {
					bestD = d;
					best = p;
				}
			}
		}

		return best;
	}

	/** Villager soldiers: walk up and strike with the sword, or keep a distance and shoot. */
	private static void villagersFight(MinecraftServer server) {
		for (Iterator<Map.Entry<UUID, UUID>> it = ENGAGED.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, UUID> e = it.next();
			Troop t = TROOPS.get(e.getKey());
			ServerLevel level = t != null ? WarsWorld.levelOf(server, t.dimension()) : null;
			Entity self = level != null ? level.getEntity(e.getKey()) : null;
			Entity target = level != null ? level.getEntity(e.getValue()) : null;

			if (!(self instanceof Mob mob) || !(target instanceof LivingEntity enemy) || !enemy.isAlive() || !mob.isAlive()) {
				it.remove();
				continue;
			}

			double d = mob.distanceTo(enemy);
			long now = server.overworld().getGameTime();
			boolean ready = now >= COOLDOWN.getOrDefault(e.getKey(), 0L);

			if (t.archer()) {
				if (d > 14) {
					mob.getNavigation().moveTo(enemy, 0.8);
				} else {
					mob.getNavigation().stop();

					if (ready) {
						Arrow arrow = new Arrow(level, mob, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
						double dx = enemy.getX() - mob.getX();
						double dy = enemy.getY() + 1.0 - (mob.getY() + 1.5);
						double dz = enemy.getZ() - mob.getZ();
						arrow.shoot(dx, dy + Math.hypot(dx, dz) * 0.18, dz, 1.6f, 8f);
						level.addFreshEntity(arrow);
						COOLDOWN.put(e.getKey(), now + 30);
					}
				}
			} else if (d <= 2.4) {
				mob.getNavigation().stop();

				if (ready) {
					enemy.hurtServer(level, level.damageSources().mobAttack(mob), 5f);
					COOLDOWN.put(e.getKey(), now + 16);
				}
			} else {
				mob.getNavigation().moveTo(enemy, 0.85);
			}
		}
	}

	// ---------------------------------------------------------------- going away

	private static void discard(ServerLevel level, UUID id) {
		Entity e = level.getEntity(id);

		if (e != null) {
			e.discard();
		}

		dropBoat(level, id);
		LAST_POS.remove(id);
		STUCK.remove(id);

		TROOPS.remove(id);
		ENGAGED.remove(id);
		COOLDOWN.remove(id);
		WarsWar.untrack(id);
	}

	private static void dropBoat(ServerLevel level, UUID troop) {
		UUID boatId = BOATS.remove(troop);
		SAILING.remove(troop);

		if (boatId != null && level != null) {
			Entity boat = level.getEntity(boatId);

			if (boat != null) {
				boat.discard();
			}

			WarsWar.untrack(boatId);
		}
	}

	/** Removes the soldiers of a division (or the defenders of a province). */
	static void forget(MinecraftServer server, UUID group) {
		forgetIf(server, t -> t.group().equals(group));
	}

	private static void forgetIf(MinecraftServer server, java.util.function.Predicate<Troop> which) {
		for (Map.Entry<UUID, Troop> e : new ArrayList<>(TROOPS.entrySet())) {
			if (which.test(e.getValue())) {
				ServerLevel level = WarsWorld.levelOf(server, e.getValue().dimension());

				if (level != null) {
					discard(level, e.getKey());
				} else {
					TROOPS.remove(e.getKey());
				}
			}
		}
	}

	private static void removeAll(MinecraftServer server) {
		forgetIf(server, t -> true);
		ENGAGED.clear();
		COOLDOWN.clear();
	}

	/** Is this entity one of the war's soldiers? (they can't be traded with) */
	static boolean isTroop(UUID id) {
		return TROOPS.containsKey(id);
	}

	static int count() {
		return TROOPS.size();
	}
}
