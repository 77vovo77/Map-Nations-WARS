package com.mapnationswars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.nation.Ranks;

/**
 * Map Nations WARS stage 3: life inside a nation.
 * Merit for serving, automatic promotions in AI-ruled nations, daily salaries,
 * and elections in nations whose ideology has them (the villages vote).
 */
public final class WarsPolitics {
	/** Merit for spending 5 minutes in your nation's land. */
	private static final int MERIT_PRESENCE = 1;
	/** Merit for killing a monster in your nation's land. */
	private static final int MERIT_KILL = 2;
	private static int ticks = 0;

	private WarsPolitics() {
	}

	static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 6000 == 0) { // every 5 minutes
				presence(server);
			}
		});

		// defending the nation's land from monsters earns merit
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof Enemy) || !(source.getEntity() instanceof ServerPlayer player)) {
				return;
			}

			NationData n = ServerNations.nationOf(player.getUUID());

			if (n == null) {
				return;
			}

			NationData land = ServerNations.nationAt(entity.level().dimension().identifier().toString(),
					Mth.floor(entity.getX()) >> 4, Mth.floor(entity.getZ()) >> 4);

			if (land == n) {
				addMerit(player.level().getServer(), n, player.getUUID(), MERIT_KILL);
			}
		});
	}

	private static void presence(MinecraftServer server) {
		boolean changed = false;

		for (ServerPlayer p : PlayerLookup.all(server)) {
			NationData n = ServerNations.nationOf(p.getUUID());

			if (n == null) {
				continue;
			}

			NationData land = ServerNations.nationAt(p.level().dimension().identifier().toString(), Mth.floor(p.getX()) >> 4, Mth.floor(p.getZ()) >> 4);

			if (land == n) {
				n.merit.merge(p.getUUID(), MERIT_PRESENCE, Integer::sum);
				changed |= promote(server, n, p.getUUID());
				changed = true;
			}
		}

		if (changed) {
			ServerNations.saveNow(server);
			ServerNations.broadcast(server);
		}
	}

	/** Adds merit (and maybe a promotion). */
	static void addMerit(MinecraftServer server, NationData n, UUID player, int amount) {
		if (server == null || amount <= 0 || !n.isMember(player)) {
			return;
		}

		n.merit.merge(player, amount, Integer::sum);
		promote(server, n, player);
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
	}

	/** AI-ruled nations promote by merit. Returns true if the rank changed. */
	private static boolean promote(MinecraftServer server, NationData n, UUID player) {
		if (!n.aiRuled() || player.equals(n.leader)) {
			return false;
		}

		int rank = n.rankOf(player);
		int merit = n.merit.getOrDefault(player, 0);

		if (rank + 1 < Ranks.NAMES.length && merit >= Ranks.MERIT[rank + 1]) {
			n.ranks.put(player, rank + 1);
			ServerNations.syncOfficers(n);
			ServerNations.notifyPlayer(server, player, n.ideology.leaderTitle + " " + n.leaderName() + " made you a "
					+ Ranks.name(rank + 1) + " of " + n.name + "! Salary: " + Ranks.SALARY[rank + 1] + " emeralds a day.");
			return true;
		}

		return false;
	}

	// ---------------------------------------------------------------- once a day (called by the economy)

	static void runDay(MinecraftServer server, long day) {
		for (NationData n : new ArrayList<>(ServerNations.allNations())) {
			paySalaries(n);

			if (n.ai && Ranks.hasElections(n.ideology)) {
				if (n.nextElection == 0) {
					n.nextElection = day + Ranks.ELECTION_DAYS;
				} else if (day >= n.nextElection) {
					election(server, n);
					n.nextElection = day + Ranks.ELECTION_DAYS;
				}
			}
		}
	}

	/** Salaries are saved up; members collect them at any mayor of their nation. */
	private static void paySalaries(NationData n) {
		for (NationData.Member m : n.members) {
			int salary = m.id().equals(n.leader) ? Ranks.LEADER_SALARY : Ranks.SALARY[Math.min(Ranks.SALARY.length - 1, n.rankOf(m.id()))];

			if (salary > 0 && n.treasury >= salary) {
				n.treasury -= salary;
				n.owed.merge(m.id(), salary, Integer::sum);
				n.book("Salaries", -salary);
			}
		}
	}

	/**
	 * The villages vote. Every province gives its votes (its villagers) to the candidate it likes most:
	 * players are liked for their merit, the current ruler for keeping the villages happy.
	 */
	private static void election(MinecraftServer server, NationData n) {
		Random random = new Random();
		Map<UUID, Integer> votes = new HashMap<>();
		UUID ai = Ranks.nobody(); // stands for the game's own ruler
		List<UUID> running = new ArrayList<>();

		for (UUID c : n.candidates) {
			if (n.isMember(c)) {
				running.add(c);
			}
		}

		if (running.isEmpty()) {
			n.lastElection = "No candidates - " + n.leaderName() + " stays " + n.ideology.leaderTitle + ".";
			return;
		}

		int happiness = 0;
		int provinces = 0;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation)) {
				happiness += p.happiness;
				provinces++;
			}
		}

		double avgHappiness = provinces == 0 ? 50 : happiness / (double) provinces;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (!n.id.equals(p.nation) || p.abandoned) {
				continue;
			}

			UUID best = ai;
			double bestScore = n.aiRuled() ? avgHappiness / 5.0 + random.nextDouble() * 10 : -1;

			for (UUID c : running) {
				// merit, the campaign (speeches, feasts) and, for the one in power, how happy the villages are
				double score = n.merit.getOrDefault(c, 0) / 10.0 + n.campaign.getOrDefault(c, 0) / 8.0 + random.nextDouble() * 10
						+ (c.equals(n.leader) ? avgHappiness / 10.0 : 0);

				if (score > bestScore) {
					bestScore = score;
					best = c;
				}
			}

			votes.merge(best, Math.max(1, p.population), Integer::sum);
		}

		// the members vote too (each vote is worth 3)
		for (Map.Entry<UUID, UUID> v : n.votes.entrySet()) {
			if (n.isMember(v.getKey()) && (running.contains(v.getValue()) || v.getValue().equals(ai))) {
				votes.merge(v.getValue(), 3, Integer::sum);
			}
		}

		// the result, for everyone to see
		n.lastResults.clear();
		votes.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> {
			NationData.Member m = n.member(e.getKey());
			String name = e.getKey().equals(ai) ? n.rulerName + " (the crown)" : m != null ? m.name() : "?";
			n.lastResults.put(name, e.getValue());
		});

		UUID winner = ai;
		int most = -1;

		for (Map.Entry<UUID, Integer> e : votes.entrySet()) {
			if (e.getValue() > most) {
				most = e.getValue();
				winner = e.getKey();
			}
		}

		if (winner.equals(ai)) {
			if (!n.aiRuled()) {
				n.leader = UUID.randomUUID(); // the players lost: the game rules again
			}

			n.lastElection = "The villages re-elected " + n.rulerName + " (" + most + " votes).";
		} else {
			UUID oldLeader = n.leader;
			n.leader = winner;
			n.ranks.put(winner, Ranks.MINISTER);

			if (n.isMember(oldLeader) && !oldLeader.equals(winner)) {
				n.ranks.put(oldLeader, Ranks.MINISTER);
			}

			ServerNations.syncOfficers(n);
			NationData.Member m = n.member(winner);
			n.lastElection = (m != null ? m.name() : "A player") + " won the election with " + most + " votes!";
			ServerNations.notifyPlayer(server, winner, "You won the election! You are now the " + n.ideology.leaderTitle + " of " + n.name + ".");
		}

		n.candidates.clear();
		n.campaign.clear();
		n.votes.clear();
		WarsDiplomacy.news(server, "\u2611 Election in " + n.name + ": " + n.lastElection);
	}

	// ---------------------------------------------------------------- campaigning and voting (2.1)

	private static final Map<UUID, Long> LAST_CAMPAIGN = new HashMap<>();

	/** A candidate spends 10 emeralds they carry on their campaign: +10 campaign points (once a minute). */
	static void campaign(MinecraftServer server, ServerPlayer player) {
		NationData n = ServerNations.nationOf(player.getUUID());

		if (n == null || !n.candidates.contains(player.getUUID())) {
			ServerNations.status(player, "Run for office first, then campaign.", false);
			return;
		}

		long now = server.overworld().getGameTime();

		if (now - LAST_CAMPAIGN.getOrDefault(player.getUUID(), -10000L) < 1200) {
			ServerNations.status(player, "Your speech still echoes. Campaign again in a minute.", false);
			return;
		}

		if (WarsItems.takeEmeralds(player, 10) < 10) {
			ServerNations.status(player, "A campaign costs 10 emeralds (posters, speeches, a feast). You need to carry them.", false);
			return;
		}

		LAST_CAMPAIGN.put(player.getUUID(), now);
		int points = n.campaign.merge(player.getUUID(), 10, Integer::sum);
		ServerNations.status(player, "You campaign across " + n.name + "! Campaign: " + points + " (every 8 points count like 10 merit with the voters).", true);
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
	}

	/** A member votes for a candidate (or for the crown: an empty / unknown target). */
	static void vote(MinecraftServer server, ServerPlayer player, String target) {
		NationData n = ServerNations.nationOf(player.getUUID());

		if (n == null || !Ranks.hasElections(n.ideology)) {
			ServerNations.status(player, "Your nation holds no elections.", false);
			return;
		}

		UUID choice;

		try {
			choice = UUID.fromString(target);
		} catch (IllegalArgumentException e) {
			choice = Ranks.nobody();
		}

		if (!choice.equals(Ranks.nobody()) && !n.candidates.contains(choice)) {
			ServerNations.status(player, "That one isn't running.", false);
			return;
		}

		n.votes.put(player.getUUID(), choice);
		NationData.Member m = n.member(choice);
		ServerNations.status(player, "You vote for " + (m != null ? m.name() : n.rulerName + " (the crown)") + ".", true);
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
	}

	// ---------------------------------------------------------------- collecting salary, the treasury

	/** Gives the player the salary they saved up (real emeralds). */
	static void collectSalary(MinecraftServer server, ServerPlayer player, ProvinceData p) {
		NationData n = ServerNations.nation(p.nation);

		if (n == null || !n.isMember(player.getUUID())) {
			ServerNations.status(player, "Collect your salary at a mayor of your own nation.", false);
			return;
		}

		int owed = n.owed.getOrDefault(player.getUUID(), 0);

		if (owed <= 0) {
			ServerNations.status(player, "No salary waiting for you.", false);
			return;
		}

		n.owed.remove(player.getUUID());
		giveEmeralds(player, owed);
		ServerNations.status(player, "Mayor " + p.mayorName + " paid you " + owed + " emeralds.", true);
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
	}

	/** Ministers and the leader can put emeralds into the treasury or take them out, at the capital. */
	static void treasury(MinecraftServer server, ServerPlayer player, ProvinceData p, int amount, boolean deposit) {
		NationData n = ServerNations.nation(p.nation);

		if (n == null || !n.isMember(player.getUUID()) || !p.capital) {
			ServerNations.status(player, "The treasury is kept at your nation's capital.", false);
			return;
		}

		boolean allowed = n.leader.equals(player.getUUID()) || n.rankOf(player.getUUID()) >= Ranks.MINISTER;

		if (!allowed) {
			ServerNations.status(player, "Only Ministers and the " + n.ideology.leaderTitle + " can use the treasury.", false);
			return;
		}

		if (deposit) {
			int taken = WarsItems.takeEmeralds(player, amount);

			if (taken <= 0) {
				ServerNations.status(player, "You have no emeralds.", false);
				return;
			}

			n.treasury += taken;
			ServerNations.status(player, "You put " + taken + " emeralds into the treasury of " + n.name + ".", true);
		} else {
			int take = (int) Math.min(n.treasury, Math.max(1, amount));

			if (take <= 0) {
				ServerNations.status(player, "The treasury is empty.", false);
				return;
			}

			n.treasury -= take;
			giveEmeralds(player, take);
			ServerNations.status(player, "You took " + take + " emeralds from the treasury of " + n.name + ".", true);
		}

		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
	}

	static void giveEmeralds(ServerPlayer player, int amount) {
		while (amount > 0) {
			int n = Math.min(64, amount);
			ItemStack stack = new ItemStack(Items.EMERALD, n);

			if (!player.getInventory().add(stack) && !stack.isEmpty()) {
				// inventory full: drop the rest at the player's feet
				player.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), stack));
			}

			amount -= n;
		}
	}
}
