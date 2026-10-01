package com.mapnationswars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.Enemy;

import com.mapnationswars.nation.DutyData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;

/**
 * Map Nations WARS 1.9: duties - the clear way to rise.
 * Members get duties from their nation (hunt monsters in our land, bring emeralds to a village, visit a province,
 * patrol, fight the enemy) and earn merit and pay. Players without a nation get duties from the villages around them
 * and earn those villages' support (the road to founding your own nation). Up to 3 at a time; new ones come by themselves.
 * Duties are kept while the server runs (they are small tasks).
 */
public final class WarsDuties {
	private static final int MAX = 3;
	private static final Random RANDOM = new Random();
	private static final Map<UUID, List<DutyData>> DUTIES = new HashMap<>();
	/** player -> game time a new duty may be offered */
	private static final Map<UUID, Long> NEXT_OFFER = new HashMap<>();
	private static int ticks = 0;

	private WarsDuties() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			DUTIES.clear();
			NEXT_OFFER.clear();
		});

		// every 30 seconds: new duties, visits and patrols
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 600 == 0 && WarsWorld.isGenerated()) {
				for (ServerPlayer p : PlayerLookup.all(server)) {
					presence(server, p);
					offer(server, p, false);
				}
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof Enemy && source.getEntity() instanceof ServerPlayer player) {
				onMonsterKilled(player.level().getServer(), player, entity.level().dimension().identifier().toString(), entity.getX(), entity.getZ());
			}
		});
	}

	static List<DutyData> of(UUID player) {
		return new ArrayList<>(DUTIES.getOrDefault(player, List.of()));
	}

	// ---------------------------------------------------------------- new duties

	/** Gives the player a new duty if they have room (forced = they asked for one). */
	static void offer(MinecraftServer server, ServerPlayer player, boolean forced) {
		UUID me = player.getUUID();
		List<DutyData> list = DUTIES.computeIfAbsent(me, k -> new ArrayList<>());
		long now = server.overworld().getGameTime();

		if (list.size() >= MAX) {
			if (forced) {
				ServerNations.status(player, "You already have " + MAX + " duties. Finish or abandon one first.", false);
			}

			return;
		}

		if (!forced && now < NEXT_OFFER.getOrDefault(me, 0L)) {
			return;
		}

		DutyData d = make(server, player);

		if (d == null) {
			if (forced) {
				ServerNations.status(player, "Nobody has work for you here. Go near a village, or join a nation.", false);
			}

			return;
		}

		list.add(d);
		NEXT_OFFER.put(me, now + 2400); // a new one at most every 2 minutes
		player.sendSystemMessage(Component.literal("⚑ New duty: " + d.text + " (" + d.reward() + ")").withColor(0x9CE0FF));
		WarsPeople.sync(player);
	}

	private static DutyData make(MinecraftServer server, ServerPlayer player) {
		NationData n = ServerNations.nationOf(player.getUUID());
		List<DutyData> have = DUTIES.getOrDefault(player.getUUID(), List.of());
		DutyData d = new DutyData(UUID.randomUUID());

		if (n != null) {
			d.nation = n.id;
			List<ProvinceData> ours = new ArrayList<>();

			for (ProvinceData p : WarsWorld.provinces()) {
				if (n.id.equals(p.nation) && !p.abandoned) {
					ours.add(p);
				}
			}

			boolean war = !WarsDiplomacy.enemiesOf(n).isEmpty();
			List<DutyData.Type> types = new ArrayList<>(List.of(DutyData.Type.KILL, DutyData.Type.PATROL));

			if (!ours.isEmpty()) {
				types.add(DutyData.Type.DONATE);
				types.add(DutyData.Type.VISIT);
			}

			if (war) {
				types.add(DutyData.Type.FIGHT);
				types.add(DutyData.Type.FIGHT);
			}

			// not the same kind twice
			types.removeIf(t -> have.stream().anyMatch(h -> h.type == t));

			if (types.isEmpty()) {
				return null;
			}

			d.type = types.get(RANDOM.nextInt(types.size()));
			ProvinceData p = ours.isEmpty() ? null : ours.get(RANDOM.nextInt(ours.size()));

			switch (d.type) {
				case KILL -> {
					d.needed = 3 + RANDOM.nextInt(6);
					d.text = "Kill " + d.needed + " monsters in the land of " + n.name;
					d.rewardMerit = 2 + d.needed;
				}
				case PATROL -> {
					d.needed = 6; // half-minutes
					d.text = "Patrol the land of " + n.name + " for 3 minutes";
					d.rewardMerit = 6;
				}
				case DONATE -> {
					d.province = p.id;
					d.needed = 5 + RANDOM.nextInt(11);
					d.text = "Bring " + d.needed + " emeralds to the mayor of " + p.name;
					d.rewardMerit = d.needed + 4;
				}
				case VISIT -> {
					d.province = farthest(ours, player);
					ProvinceData v = WarsWorld.province(d.province);
					d.text = "Visit " + (v != null ? v.name : "a province") + " and check on its people";
					d.rewardMerit = 8;
				}
				case FIGHT -> {
					d.needed = 3 + RANDOM.nextInt(4);
					d.text = "Kill " + d.needed + " enemy soldiers or guards (go to a battle, a siege or enemy villages)";
					d.rewardMerit = 6 + d.needed * 2;
				}
			}

			d.rewardEmeralds = n.treasury >= 60 ? 2 + d.rewardMerit / 3 : 0;
			return d;
		}

		// no nation: the nearest village asks for help (that is how its people come to back you)
		ProvinceData village = WarsRevolts.provinceNear(player.level().dimension().identifier().toString(), player.getX(), player.getZ());

		if (village == null) {
			village = nearestVillage(player, 400);
		}

		if (village == null) {
			return null;
		}

		ProvinceData v = village;
		List<DutyData.Type> types = new ArrayList<>(List.of(DutyData.Type.KILL, DutyData.Type.DONATE, DutyData.Type.VISIT));
		types.removeIf(t -> have.stream().anyMatch(h -> h.type == t && v.id.equals(h.province)));

		if (types.isEmpty()) {
			return null;
		}

		d.type = types.get(RANDOM.nextInt(types.size()));
		d.province = v.id;

		switch (d.type) {
			case KILL -> {
				d.needed = 3 + RANDOM.nextInt(5);
				d.text = "Kill " + d.needed + " monsters around " + v.name;
				d.rewardSupport = 10 + d.needed * 2;
			}
			case DONATE -> {
				d.needed = 3 + RANDOM.nextInt(8);
				d.text = "Bring " + d.needed + " emeralds to the mayor of " + v.name;
				d.rewardSupport = 10 + d.needed * 2;
			}
			default -> {
				d.type = DutyData.Type.VISIT;
				d.text = "Visit " + v.name + " and listen to its people";
				d.rewardSupport = 12;
			}
		}

		return d;
	}

	private static UUID farthest(List<ProvinceData> list, ServerPlayer player) {
		ProvinceData best = list.get(0);
		double bestD = -1;

		for (ProvinceData p : list) {
			double d = Math.hypot(p.x - player.getX(), p.z - player.getZ());

			if (d > bestD && d < 1500) {
				bestD = d;
				best = p;
			}
		}

		return best.id;
	}

	private static ProvinceData nearestVillage(ServerPlayer player, double within) {
		String dim = player.level().dimension().identifier().toString();
		ProvinceData best = null;
		double bestD = within;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (p.type == ProvinceData.Type.VILLAGE && !p.abandoned && p.dimension.equals(dim)) {
				double d = Math.hypot(p.x - player.getX(), p.z - player.getZ());

				if (d < bestD) {
					bestD = d;
					best = p;
				}
			}
		}

		return best;
	}

	// ---------------------------------------------------------------- progress

	private static void presence(MinecraftServer server, ServerPlayer player) {
		List<DutyData> list = DUTIES.get(player.getUUID());

		if (list == null || list.isEmpty()) {
			return;
		}

		// duties of a nation you left (or of villages, once you joined one) are void
		NationData mine = ServerNations.nationOf(player.getUUID());

		if (list.removeIf(d -> mine == null ? d.nation != null : !mine.id.equals(d.nation))) {
			WarsPeople.sync(player);
		}

		String dim = player.level().dimension().identifier().toString();

		for (DutyData d : new ArrayList<>(list)) {
			if (d.type == DutyData.Type.VISIT) {
				ProvinceData p = WarsWorld.province(d.province);

				if (p != null && p.dimension.equals(dim) && Math.hypot(p.x - player.getX(), p.z - player.getZ()) < 64) {
					d.progress = d.needed;
					complete(server, player, d);
				}
			} else if (d.type == DutyData.Type.PATROL) {
				NationData land = ServerNations.nationAt(dim, Mth.floor(player.getX()) >> 4, Mth.floor(player.getZ()) >> 4);

				if (land != null && land.id.equals(d.nation)) {
					progress(server, player, d, 1);
				}
			}
		}
	}

	private static void onMonsterKilled(MinecraftServer server, ServerPlayer player, String dim, double x, double z) {
		List<DutyData> list = DUTIES.get(player.getUUID());

		if (server == null || list == null) {
			return;
		}

		for (DutyData d : new ArrayList<>(list)) {
			if (d.type != DutyData.Type.KILL) {
				continue;
			}

			if (d.nation != null) {
				NationData land = ServerNations.nationAt(dim, Mth.floor(x) >> 4, Mth.floor(z) >> 4);

				if (land != null && land.id.equals(d.nation)) {
					progress(server, player, d, 1);
				}
			} else {
				ProvinceData p = WarsWorld.province(d.province);

				if (p != null && p.dimension.equals(dim) && Math.hypot(p.x - x, p.z - z) < 120) {
					progress(server, player, d, 1);
				}
			}
		}
	}

	/** An enemy soldier, guard or invader killed (WarsWar, WarsGuards). */
	static void onEnemyKilled(MinecraftServer server, ServerPlayer player) {
		List<DutyData> list = DUTIES.get(player.getUUID());

		if (list != null) {
			for (DutyData d : new ArrayList<>(list)) {
				if (d.type == DutyData.Type.FIGHT) {
					progress(server, player, d, 1);
				}
			}
		}
	}

	/** Emeralds given to a village's mayor (WarsEconomy.donate). */
	static void onDonate(MinecraftServer server, ServerPlayer player, ProvinceData p, int amount) {
		List<DutyData> list = DUTIES.get(player.getUUID());

		if (list != null) {
			for (DutyData d : new ArrayList<>(list)) {
				if (d.type == DutyData.Type.DONATE && p.id.equals(d.province)) {
					progress(server, player, d, amount);
				}
			}
		}
	}

	private static void progress(MinecraftServer server, ServerPlayer player, DutyData d, int amount) {
		d.progress = Math.min(d.needed, d.progress + amount);

		if (d.progress >= d.needed) {
			complete(server, player, d);
		} else {
			WarsPeople.sync(player);
		}
	}

	private static void complete(MinecraftServer server, ServerPlayer player, DutyData d) {
		List<DutyData> list = DUTIES.get(player.getUUID());

		if (list == null || !list.remove(d)) {
			return;
		}

		NationData n = ServerNations.nation(d.nation);

		if (n != null && n.isMember(player.getUUID())) {
			WarsPolitics.addMerit(server, n, player.getUUID(), d.rewardMerit);

			if (d.rewardEmeralds > 0 && n.treasury >= d.rewardEmeralds) {
				n.treasury -= d.rewardEmeralds;
				WarsPolitics.giveEmeralds(player, d.rewardEmeralds);
			}
		}

		ProvinceData p = WarsWorld.province(d.province);

		if (d.rewardSupport > 0 && p != null) {
			WarsRevolts.addSupport(player, p, d.rewardSupport);
			WarsPeople.changeStanding(player, p.nation, 4); // their nation notices too
		}

		player.sendSystemMessage(Component.literal("✔ Duty done: " + d.text + " (" + d.reward() + ")").withColor(0x7CFF7C));
		ServerNations.status(player, "✔ Duty done! " + d.reward(), true);
		WarsPeople.sync(player);
	}

	static void abandon(ServerPlayer player, String id) {
		List<DutyData> list = DUTIES.get(player.getUUID());

		if (list != null && list.removeIf(d -> d.id.toString().equals(id))) {
			ServerNations.status(player, "Duty abandoned.", true);
			WarsPeople.sync(player);
		}
	}
}
