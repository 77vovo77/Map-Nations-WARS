package com.mapnationswars;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;

/**
 * Map Nations WARS stage 2: the economy.
 * Once every Minecraft day each province grows food, makes emeralds and pays taxes to its nation;
 * the nation pays the upkeep of the buildings. Villages build houses, farms and workshops when ordered
 * (AI nations decide by themselves). Happy villages grow, starving or neglected ones shrink.
 */
public final class WarsEconomy {
	/** One Minecraft day. */
	public static final int DAY_TICKS = 24000;
	/** Share of a village's income that goes to its nation as tax. */
	public static final double TAX = 0.5;

	public enum Build {
		HOUSE("House", 12, "2 more beds: the village can grow"),
		FARM("Farm", 8, "+5 food every day"),
		WORKSHOP("Workshop", 20, "+4 emeralds every day");

		public final String displayName;
		public final int cost;
		public final String effect;

		Build(String displayName, int cost, String effect) {
			this.displayName = displayName;
			this.cost = cost;
			this.effect = effect;
		}

		public static Build byName(String name) {
			for (Build b : values()) {
				if (b.name().equals(name)) {
					return b;
				}
			}

			return null;
		}
	}

	private static long lastDay = -1;

	private WarsEconomy() {
	}

	/** Buildings a province has when the world starts. */
	static void startingBuildings(ProvinceData p, Random random) {
		if (p.type == ProvinceData.Type.VILLAGE) {
			p.houses = (p.population + 1) / 2 + 1 + random.nextInt(2);
			p.farms = Math.max(1, p.population / 4 + random.nextInt(2));
			p.workshops = p.population >= 10 ? 1 : 0;
			p.food = p.population * 4;
			p.funds = 5 + random.nextInt(15);
		} else {
			p.houses = 0;
			p.farms = 0;
			p.workshops = 1;
			p.food = 0;
			p.funds = 10 + random.nextInt(20);
		}

		p.happiness = 55 + random.nextInt(20);
	}

	static void tick(MinecraftServer server) {
		long day = server.overworld().getGameTime() / DAY_TICKS;

		if (lastDay < 0) {
			lastDay = day; // just started: the first day ends at the next day boundary
			return;
		}

		if (day != lastDay) {
			lastDay = day;
			runDay(server);
		}
	}

	/** Food a province makes per day. */
	public static int foodMade(ProvinceData p) {
		return p.type == ProvinceData.Type.VILLAGE ? p.farms * 5 + 2 : p.population;
	}

	/** Food a province eats per day. */
	public static int foodEaten(ProvinceData p) {
		return p.abandoned ? 0 : p.population;
	}

	/** Emeralds a province makes per day (before tax). */
	public static int income(ProvinceData p) {
		if (p.abandoned) {
			return 0;
		}

		int base = p.type == ProvinceData.Type.VILLAGE ? p.population : p.population * 2;
		int work = p.workshops * 4;
		// unhappy villagers work less
		return (int) Math.round((base + work) * (0.5 + p.happiness / 200.0));
	}

	/** What the nation pays every day to keep the buildings running. */
	public static int upkeep(ProvinceData p) {
		return p.abandoned ? 0 : 1 + p.workshops + p.farms / 2;
	}

	static void runDay(MinecraftServer server) {
		Map<UUID, Integer> balance = new HashMap<>();

		for (NationData n : ServerNations.allNations()) {
			n.ledger.clear(); // a new day's accounts
		}

		for (ProvinceData p : WarsWorld.provinces()) {
			NationData n = ServerNations.nation(p.nation);

			// food
			int made = foodMade(p);
			int eaten = foodEaten(p);
			p.lastFood = made - eaten;
			p.food = Math.min(p.food + made - eaten, 30 + p.farms * 25);
			boolean starving = p.food < 0;

			if (starving) {
				p.food = 0;
			}

			// emeralds: the village keeps part, the nation gets the tax
			int income = income(p);
			int tax = (int) Math.round(income * (n != null ? n.taxRate() : TAX));
			p.lastIncome = income;
			p.lastTax = tax;
			p.funds += income - tax;
			p.lastUpkeep = upkeep(p);

			if (n != null) {
				balance.merge(n.id, tax - p.lastUpkeep, Integer::sum);
				n.book("Taxes", tax);
				n.book("Buildings upkeep", -p.lastUpkeep);
			}

			// building
			if (!p.building.isEmpty() && --p.buildDays <= 0) {
				Build b = Build.byName(p.building);

				// and it will stand in the world, too (WarsBuild)
				if (b == Build.HOUSE) {
					p.houses++;
					p.pendingHouses++;
				} else if (b == Build.FARM) {
					p.farms++;
					p.pendingFarms++;
				} else if (b == Build.WORKSHOP) {
					p.workshops++;
					p.pendingWorkshops++;
				}

				p.building = "";
				p.buildDays = 0;
				p.happiness = Math.min(100, p.happiness + 3);
			}

			// happiness moves towards what the village feels
			int target = 62 + (n != null ? NationData.TAX_MOOD[Math.max(0, Math.min(3, n.taxLevel))] : 0);
			target += starving ? -35 : (p.lastFood > 0 ? 8 : 0);
			target += p.population > p.beds() && p.type == ProvinceData.Type.VILLAGE ? -10 : 0;
			p.happiness += (int) Math.round((target - p.happiness) * 0.25);

			// growth or decline (real villagers are counted instead while someone is near)
			if (p.type == ProvinceData.Type.VILLAGE && !p.abandoned && !WarsWorld.isNearPlayer(server, p)) {
				if (starving && p.population > 1) {
					p.population--;
				} else if (p.food > p.population && p.population < p.beds() && p.happiness >= 50) {
					p.population++;
				}
			}

			// AI nations decide what their villages build
			if (n != null && n.aiRuled() && p.building.isEmpty() && !p.abandoned && p.type == ProvinceData.Type.VILLAGE) {
				Build next = null;

				if (made <= eaten) {
					next = Build.FARM;
				} else if (p.population >= p.beds() - 1) {
					next = Build.HOUSE;
				} else if (p.funds >= Build.WORKSHOP.cost + 20) {
					next = Build.WORKSHOP;
				}

				if (next != null) {
					startBuilding(p, n, next);
				}
			}
		}

		// nations: taxes in, upkeep out; if the treasury runs dry, the villages suffer
		for (Map.Entry<UUID, Integer> e : balance.entrySet()) {
			NationData n = ServerNations.nation(e.getKey());

			if (n == null) {
				continue;
			}

			n.lastBalance = e.getValue();
			n.treasury += e.getValue();

			if (n.treasury < 0) {
				n.treasury = 0;

				for (ProvinceData p : WarsWorld.provinces()) {
					if (n.id.equals(p.nation)) {
						p.happiness = Math.max(0, p.happiness - 6); // unpaid upkeep
					}
				}
			}
		}

		WarsPolitics.runDay(server, server.overworld().getGameTime() / DAY_TICKS);
		WarsDiplomacy.runDay(server);
		WarsRevolts.runDay(server);
		WarsLand.runDay(server);
		WarsAI.runDay(server);
		WarsWar.runDay(server);
		WarsWar.broadcast(server);

		// the day's balance is everything in the ledger
		for (NationData n : ServerNations.allNations()) {
			int sum = 0;

			for (int v : n.ledger.values()) {
				sum += v;
			}

			n.lastBalance = sum;
		}

		WarsWorld.saveNow();
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
		WarsWorld.broadcast(server);
	}

	/** Pays for a building (the village's own emeralds first, then the nation's treasury) and starts it. */
	static String startBuilding(ProvinceData p, NationData n, Build b) {
		if (!p.building.isEmpty()) {
			return p.name + " is already building a " + Build.byName(p.building).displayName.toLowerCase() + ".";
		}

		long available = p.funds + (n != null ? n.treasury : 0);

		if (available < b.cost) {
			return "Not enough emeralds: a " + b.displayName.toLowerCase() + " costs " + b.cost + ".";
		}

		int fromVillage = Math.min(p.funds, b.cost);
		p.funds -= fromVillage;

		if (n != null) {
			n.treasury -= b.cost - fromVillage;
		}

		p.building = b.name();
		p.buildDays = 1;
		return null;
	}

	// ---------------------------------------------------------------- the treasury (2.0)

	private static final Map<UUID, Long> LAST_SPEND = new HashMap<>();

	private static NationData ruled(ServerPlayer player) {
		NationData n = ServerNations.nationOf(player.getUUID());

		if (n == null || !(n.leader.equals(player.getUUID()) || n.rankOf(player.getUUID()) >= com.mapnationswars.nation.Ranks.MINISTER)) {
			ServerNations.status(player, "Only the leader and Ministers handle the treasury.", false);
			return null;
		}

		return n;
	}

	static void setTax(MinecraftServer server, ServerPlayer player, int level) {
		NationData n = ruled(player);

		if (n == null) {
			return;
		}

		n.taxLevel = Math.max(0, Math.min(3, level));
		ServerNations.status(player, "Taxes are now " + NationData.TAX_NAMES[n.taxLevel].toLowerCase() + " (" + (int) (n.taxRate() * 100)
				+ "% of the villages' income). " + (n.taxLevel >= 2 ? "The villages won't like it." : n.taxLevel == 0 ? "The villages are glad." : ""), true);
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
	}

	/** Spending the treasury on the people: a festival (+happiness, -unrest) or grain imports (+food). Once a day each. */
	static void treasuryAction(MinecraftServer server, ServerPlayer player, String action) {
		NationData n = ruled(player);

		if (n == null) {
			return;
		}

		java.util.List<ProvinceData> ours = new java.util.ArrayList<>();

		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation) && !p.abandoned) {
				ours.add(p);
			}
		}

		long day = server.overworld().getGameTime() / DAY_TICKS;
		UUID key = new UUID(n.id.getMostSignificantBits(), n.id.getLeastSignificantBits() ^ action.hashCode());

		if (LAST_SPEND.getOrDefault(key, -1L) == day) {
			ServerNations.status(player, "That was already done today.", false);
			return;
		}

		boolean festival = "FESTIVAL".equals(action);
		int cost = Math.max(5, ours.size() * (festival ? 6 : 3));

		if (n.treasury < cost) {
			ServerNations.status(player, "That costs " + cost + " emeralds; the treasury has " + n.treasury + ".", false);
			return;
		}

		n.treasury -= cost;
		n.book(festival ? "Festival" : "Grain imports", -cost);
		LAST_SPEND.put(key, day);

		for (ProvinceData p : ours) {
			if (festival) {
				p.happiness = Math.min(100, p.happiness + 10);
				p.unrest = Math.max(0, p.unrest - 12);
			} else {
				p.food += 20;
			}
		}

		if (festival) {
			WarsDiplomacy.news(server, "\u2728 " + n.name + " holds a great festival! The villages celebrate.");
		} else {
			ServerNations.status(player, "Grain was bought for every village (+20 food each) for " + cost + " emeralds.", true);
		}

		WarsWorld.saveNow();
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
		WarsWorld.broadcast(server);
	}

	// ---------------------------------------------------------------- player actions

	/** Who may give orders to a village: only its own nation's leader and Ministers. */
	static boolean canOrder(ServerPlayer player, ProvinceData p) {
		NationData n = ServerNations.nation(p.nation);
		return (n != null && (n.leader.equals(player.getUUID())
				|| (n.isMember(player.getUUID()) && n.rankOf(player.getUUID()) >= com.mapnationswars.nation.Ranks.MINISTER)));
	}

	static void order(MinecraftServer server, ServerPlayer player, ProvinceData p, Build b) {
		if (!canOrder(player, p)) {
			ServerNations.status(player, "Only the leaders of " + nationName(p) + " can give orders to " + p.name + ".", false);
			return;
		}

		String problem = startBuilding(p, ServerNations.nation(p.nation), b);

		if (problem != null) {
			ServerNations.status(player, problem, false);
			return;
		}

		ServerNations.status(player, p.name + " is building a " + b.displayName.toLowerCase() + ". It will be ready tomorrow.", true);
		WarsWorld.saveNow();
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
		WarsWorld.broadcast(server);
	}

	/** Gives real emeralds from the player's inventory to the village. Villagers like gifts. */
	static void donate(MinecraftServer server, ServerPlayer player, ProvinceData p, int amount) {
		int taken = WarsItems.takeEmeralds(player, amount);

		if (taken <= 0) {
			ServerNations.status(player, "You have no emeralds to give.", false);
			return;
		}

		p.funds += taken;
		p.happiness = Math.min(100, p.happiness + Math.min(10, (taken + 1) / 2));
		NationData owner = ServerNations.nation(p.nation);

		WarsRevolts.addSupport(player, p, taken); // the people remember who helped them
		WarsPeople.changeStanding(player, p.nation, Math.max(1, taken / 3));
		WarsDuties.onDonate(server, player, p, taken);

		if (owner != null && owner.isMember(player.getUUID())) {
			WarsPolitics.addMerit(server, owner, player.getUUID(), taken); // helping your own villages is service too
		}
		ServerNations.status(player, "You gave " + taken + " emerald" + (taken == 1 ? "" : "s") + " to " + p.name + ". The villagers are grateful.", true);
		WarsWorld.saveNow();
		WarsWorld.broadcast(server);
	}

	private static String nationName(ProvinceData p) {
		NationData n = ServerNations.nation(p.nation);
		return n != null ? n.name : "its nation";
	}
}
