package com.mapnationswars;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import com.mapnationswars.nation.Ideology;
import com.mapnationswars.nation.NationColors;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.network.ClaimAreaPayload;
import com.mapnationswars.network.NationActionPayload;
import com.mapnationswars.network.NationsSyncPayload;
import com.mapnationswars.network.PlayersPayload;
import com.mapnationswars.network.StatusPayload;
import com.mapnationswars.network.ToggleClaimPayload;

/**
 * Server side of nations: who is in which nation, which chunks belong to which nation.
 * Saved in the world folder as mapnationswars_nations.json.
 * In singleplayer this runs in the built-in server, so it works there too.
 */
public final class ServerNations {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final Map<UUID, NationData> NATIONS = new LinkedHashMap<>();
	/**
	 * dimension id -> (chunk key -> nations claiming it).
	 * The first nation in the list owns the land; more than one = a conflict zone.
	 */
	private static final Map<String, Map<Long, List<UUID>>> CLAIMS = new HashMap<>();

	private static Path file;
	private static final Map<UUID, Long> LAST_AREA_CLAIM = new HashMap<>();
	private static final Map<UUID, Long> LAST_CLICK_CLAIM = new HashMap<>();
	/** No land limit - only these short waits stop claim spam. */
	private static final long CLICK_COOLDOWN_MS = 50;

	/** Land an officer proposed; the leader accepts (annexes) or denies it. */
	private record Proposal(UUID nation, UUID proposer, String proposerName) {
	}

	/** dimension -> (chunk key -> proposal) */
	private static final Map<String, Map<Long, Proposal>> PROPOSALS = new HashMap<>();
	private static int tickCounter = 0;

	private ServerNations() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(ServerNations::load);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save(server);
			NATIONS.clear();
			CLAIMS.clear();
			PROPOSALS.clear();
			file = null;
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;

			if (updateName(player.getUUID(), player.getName().getString())) {
				save(server);
				broadcast(server);
			} else if (ServerPlayNetworking.canSend(player, NationsSyncPayload.TYPE)) {
				sender.sendPacket(buildSync());
			}
		});

		// Send everyone's position to players with the mod, twice a second.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++tickCounter % 10 == 0) {
				sendPlayerPositions(server);
			}

		});

		ServerPlayNetworking.registerGlobalReceiver(ToggleClaimPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.level().getServer();

			if (server != null) {
				server.execute(() -> toggleClaim(server, player, payload.chunkX(), payload.chunkZ()));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(ClaimAreaPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.level().getServer();

			if (server != null) {
				server.execute(() -> claimArea(server, player, payload));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(NationActionPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.level().getServer();

			if (server != null) {
				server.execute(() -> handleAction(server, player, payload));
			}
		});
	}

	// ---------------------------------------------------------------- helpers

	static NationData nationOf(UUID player) {
		for (NationData n : NATIONS.values()) {
			if (n.isMember(player)) {
				return n;
			}
		}

		return null;
	}

	private static NationData nationById(String id) {
		try {
			return NATIONS.get(UUID.fromString(id));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static UUID parseUuid(String id) {
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static void status(ServerPlayer player, String message, boolean success) {
		if (ServerPlayNetworking.canSend(player, StatusPayload.TYPE)) {
			ServerPlayNetworking.send(player, new StatusPayload(message, success));
		}
	}

	static void notifyPlayer(MinecraftServer server, UUID playerId, String message) {
		for (ServerPlayer p : PlayerLookup.all(server)) {
			if (p.getUUID().equals(playerId)) {
				status(p, message, true);
			}
		}
	}

	static String cleanName(String name) {
		String n = name.replace("§", "").trim();
		return n.length() > 24 ? n.substring(0, 24) : n;
	}

	private static String checkNameColor(String name, int color, NationData self) {
		if (name.length() < 3) {
			return "The name needs at least 3 letters.";
		}

		if (!NationColors.isValid(color)) {
			return "Please pick a colour.";
		}

		for (NationData other : NATIONS.values()) {
			if (other == self) {
				continue;
			}

			if (other.name.equalsIgnoreCase(name)) {
				return "A nation called " + other.name + " already exists.";
			}

			if (other.color == color) {
				return "That colour already belongs to " + other.name + ".";
			}
		}

		return null;
	}

	/** Returns a copy of the banner in that inventory slot, EMPTY if it is not a banner. */
	static ItemStack bannerFromSlot(ServerPlayer player, int slot) {
		List<ItemStack> items = player.getInventory().getNonEquipmentItems();

		if (slot < 0 || slot >= items.size()) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = items.get(slot);

		if (stack.isEmpty() || !stack.is(ItemTags.BANNERS)) {
			return ItemStack.EMPTY;
		}

		return stack.copyWithCount(1);
	}

	private static boolean updateName(UUID uuid, String name) {
		boolean changed = false;

		for (NationData n : NATIONS.values()) {
			for (int i = 0; i < n.members.size(); i++) {
				NationData.Member m = n.members.get(i);

				if (m.id().equals(uuid) && !m.name().equals(name)) {
					n.members.set(i, new NationData.Member(uuid, name));
					changed = true;
				}
			}
		}

		return changed;
	}

	private static void removeRequestsEverywhere(UUID player) {
		for (NationData n : NATIONS.values()) {
			n.removeRequest(player);
		}
	}

	/** A nation by its id (null if it doesn't exist). */
	static NationData nation(UUID id) {
		return id == null ? null : NATIONS.get(id);
	}

	/** Which nation owns this chunk (null = nobody). */
	static NationData nationAt(String dimension, int chunkX, int chunkZ) {
		Map<Long, List<UUID>> dimClaims = CLAIMS.get(dimension);
		List<UUID> list = dimClaims == null ? null : dimClaims.get(MapNationsMod.chunkKey(chunkX, chunkZ));
		return list == null || list.isEmpty() ? null : NATIONS.get(list.get(0));
	}

	private static void disband(NationData nation) {
		NATIONS.remove(nation.id);
		ServerAlliances.onNationDisbanded(nation.id);
		ServerMarkers.onNationDisbanded(nation.id);

		for (Map<Long, List<UUID>> dimClaims : CLAIMS.values()) {
			for (List<UUID> list : dimClaims.values()) {
				list.remove(nation.id);
			}

			dimClaims.values().removeIf(List::isEmpty);
		}

		for (NationData other : NATIONS.values()) {
			other.allies.remove(nation.id);
			other.allyRequests.remove(nation.id);
		}

		for (Map<Long, Proposal> dimProposals : PROPOSALS.values()) {
			dimProposals.values().removeIf(p -> p.nation().equals(nation.id));
		}
	}

	/** Every claimed chunk of a dimension. */
	static java.util.Set<Long> claimedChunks(String dimension) {
		Map<Long, List<UUID>> dimClaims = CLAIMS.get(dimension);
		return dimClaims == null ? java.util.Set.of() : dimClaims.keySet();
	}

	private static void notifyLeader(MinecraftServer server, NationData nation, String message) {
		notifyPlayer(server, nation.leader, message);
	}

	// ---------------------------------------------------------------- claims

	private static boolean isAllyLand(NationData nation, List<UUID> claimants) {
		if (claimants == null) {
			return false;
		}

		for (UUID other : claimants) {
			if (nation.allies.contains(other)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Officers don't claim land - they propose it. Clicking the same chunk again takes the proposal back.
	 * Returns true if something changed.
	 */
	private static boolean propose(ServerPlayer player, NationData nation, String dim, int chunkX, int chunkZ, boolean quiet) {
		long key = MapNationsMod.chunkKey(chunkX, chunkZ);
		Map<Long, Proposal> dimProposals = PROPOSALS.computeIfAbsent(dim, d -> new HashMap<>());
		Proposal existing = dimProposals.get(key);
		List<UUID> claimants = CLAIMS.getOrDefault(dim, Map.of()).get(key);

		if (existing != null) {
			if (existing.nation().equals(nation.id)) {
				dimProposals.remove(key);

				if (!quiet) {
					status(player, "Proposal taken back.", true);
				}

				return true;
			}

			if (!quiet) {
				status(player, "Another nation already has a proposal on this chunk.", false);
			}

			return false;
		}

		if (claimants != null && claimants.contains(nation.id)) {
			if (!quiet) {
				status(player, "That's already " + nation.name + "'s land.", false);
			}

			return false;
		}

		if (isAllyLand(nation, claimants)) {
			if (!quiet) {
				status(player, "That land belongs to an ally.", false);
			}

			return false;
		}

		dimProposals.put(key, new Proposal(nation.id, player.getUUID(), player.getName().getString()));

		if (!quiet) {
			status(player, "Proposed chunk " + chunkX + ", " + chunkZ + " - the " + nation.ideology.leaderTitle + " has to accept it.", true);
		}

		return true;
	}

	/** Leader: really claim (or contest) one chunk. Returns an error message, or null if it worked. */
	private static String claimOne(NationData nation, String dim, long key) {
		Map<Long, List<UUID>> dimClaims = CLAIMS.computeIfAbsent(dim, d -> new HashMap<>());
		List<UUID> claimants = dimClaims.get(key);

		if (claimants != null && claimants.contains(nation.id)) {
			return null;
		}

		if (isAllyLand(nation, claimants)) {
			return "That land belongs to an ally.";
		}

		dimClaims.computeIfAbsent(key, k -> new ArrayList<>()).add(nation.id);
		return null;
	}

	private static void toggleClaim(MinecraftServer server, ServerPlayer player, int chunkX, int chunkZ) {
		NationData nation = nationOf(player.getUUID());

		if (nation == null) {
			status(player, "Join or create a nation first (Nations tab).", false);
			return;
		}

		Level level = player.level();
		String dim = level.dimension().identifier().toString();

		if (!level.getWorldBorder().isWithinBounds(new BlockPos(chunkX * 16 + 8, 0, chunkZ * 16 + 8))) {
			status(player, "That chunk is outside the world border.", false);
			return;
		}

		UUID me = player.getUUID();
		long now = System.currentTimeMillis();
		Long lastClick = LAST_CLICK_CLAIM.get(me);

		if (lastClick != null && now - lastClick < CLICK_COOLDOWN_MS) {
			return; // clicking too fast
		}

		LAST_CLICK_CLAIM.put(me, now);

		if (!nation.leader.equals(me)) {
			if (!nation.isOfficer(me)) {
				status(player, "Only the " + nation.ideology.leaderTitle + " claims land. Officers can propose land.", false);
				return;
			}

			if (propose(player, nation, dim, chunkX, chunkZ, false)) {
				save(server);
				broadcast(server);
			}

			return;
		}

		Map<Long, List<UUID>> dimClaims = CLAIMS.computeIfAbsent(dim, d -> new HashMap<>());
		long key = MapNationsMod.chunkKey(chunkX, chunkZ);
		List<UUID> claimants = dimClaims.get(key);
		Proposal proposal = PROPOSALS.getOrDefault(dim, Map.of()).get(key);

		if (proposal != null && proposal.nation().equals(nation.id)) {
			// leader clicked a proposal: annex it
			String problem = claimOne(nation, dim, key);

			if (problem != null) {
				status(player, problem, false);
				return;
			}

			PROPOSALS.get(dim).remove(key);
			status(player, "Annexed chunk " + chunkX + ", " + chunkZ + " (proposed by " + proposal.proposerName() + ").", true);
			notifyPlayer(server, proposal.proposer(), "Your proposed land at " + chunkX + ", " + chunkZ + " was annexed!");
		} else if (claimants != null && claimants.contains(nation.id)) {
			claimants.remove(nation.id);
			status(player, "Unclaimed chunk " + chunkX + ", " + chunkZ + ".", true);

			if (claimants.isEmpty()) {
				dimClaims.remove(key);
			}
		} else {
			boolean contest = claimants != null && !claimants.isEmpty();
			String problem = claimOne(nation, dim, key);

			if (problem != null) {
				status(player, problem, false);
				return;
			}

			if (contest) {
				NationData owner = NATIONS.get(claimants.get(0));
				String ownerName = owner != null ? owner.name : "another nation";
				status(player, "You now contest " + ownerName + "'s land - it is a conflict zone!", true);

				if (owner != null) {
					notifyLeader(server, owner, nation.name + " is contesting your land at chunk " + chunkX + ", " + chunkZ + "!");
				}
			} else {
				status(player, "Claimed chunk " + chunkX + ", " + chunkZ + " for " + nation.name + ".", true);
			}
		}

		save(server);
		broadcast(server);
	}

	/**
	 * Drag claiming. Leader: claims every free chunk in the rectangle (other nations' land is skipped -
	 * contesting land is done one chunk at a time on purpose), or unclaims our chunks and denies proposals in it.
	 * Officer: proposes the rectangle, or takes proposals back.
	 */
	private static void claimArea(MinecraftServer server, ServerPlayer player, ClaimAreaPayload a) {
		NationData nation = nationOf(player.getUUID());

		if (nation == null) {
			status(player, "Join or create a nation first (Nations tab).", false);
			return;
		}

		UUID me = player.getUUID();
		boolean leader = nation.leader.equals(me);

		if (!leader && !nation.isOfficer(me)) {
			status(player, "Only the " + nation.ideology.leaderTitle + " claims land. Officers can propose land.", false);
			return;
		}

		long now = System.currentTimeMillis();
		Long last = LAST_AREA_CLAIM.get(me);

		if (last != null && now - last < ClaimAreaPayload.COOLDOWN_MS) {
			status(player, "Slow down - wait a moment between drag claims.", false);
			return;
		}

		int minX = Math.min(a.fromX(), a.toX());
		int maxX = Math.max(a.fromX(), a.toX());
		int minZ = Math.min(a.fromZ(), a.toZ());
		int maxZ = Math.max(a.fromZ(), a.toZ());
		long area = (long) (maxX - minX + 1) * (maxZ - minZ + 1);

		if (area > ClaimAreaPayload.MAX_CHUNKS) {
			status(player, "That area is too big - at most " + ClaimAreaPayload.MAX_CHUNKS + " chunks at once.", false);
			return;
		}

		LAST_AREA_CLAIM.put(me, now);
		Level level = player.level();
		String dim = level.dimension().identifier().toString();
		Map<Long, List<UUID>> dimClaims = CLAIMS.computeIfAbsent(dim, d -> new HashMap<>());
		Map<Long, Proposal> dimProposals = PROPOSALS.computeIfAbsent(dim, d -> new HashMap<>());
		int changed = 0;
		int skipped = 0;
		String limitProblem = null;

		for (int cx = minX; cx <= maxX; cx++) {
			for (int cz = minZ; cz <= maxZ; cz++) {
				long key = MapNationsMod.chunkKey(cx, cz);
				List<UUID> claimants = dimClaims.get(key);
				Proposal proposal = dimProposals.get(key);
				boolean ownProposal = proposal != null && proposal.nation().equals(nation.id);

				if (!a.claim()) {
					if (ownProposal) {
						dimProposals.remove(key); // withdraw / deny the proposal
						changed++;
					} else if (leader && claimants != null && claimants.remove(nation.id)) {
						changed++;

						if (claimants.isEmpty()) {
							dimClaims.remove(key);
						}
					}

					continue;
				}

				boolean free = claimants == null || claimants.isEmpty();
				boolean inside = level.getWorldBorder().isWithinBounds(new BlockPos(cx * 16 + 8, 0, cz * 16 + 8));

				if (!inside || (!free && !claimants.contains(nation.id))) {
					skipped++;
				} else if (!free) {
					continue; // already ours
				} else if (leader) {
					String problem = claimOne(nation, dim, key);

					if (problem != null) {
						limitProblem = problem;
					} else {
						if (ownProposal) {
							dimProposals.remove(key);
						}

						changed++;
					}
				} else if (!ownProposal && proposal == null) {
					dimProposals.put(key, new Proposal(nation.id, me, player.getName().getString()));
					changed++;
				}
			}
		}

		String extra = skipped > 0 ? " (" + skipped + " skipped - other nations' land or outside the border)" : "";

		if (!a.claim()) {
			status(player, (leader ? "Unclaimed / removed " : "Took back ") + changed + " chunks.", changed > 0);
		} else if (limitProblem != null) {
			status(player, "Claimed " + changed + " chunks, then: " + limitProblem, false);
		} else if (leader) {
			status(player, "Claimed " + changed + " chunks for " + nation.name + extra + ".", changed > 0);
		} else {
			status(player, "Proposed " + changed + " chunks" + extra + " - the " + nation.ideology.leaderTitle + " has to accept them.", changed > 0);

			if (changed > 0) {
				notifyLeader(server, nation, player.getName().getString() + " proposed " + changed + " chunks of new land (Nations tab / Claims tab).");
			}
		}

		if (changed > 0) {
			save(server);
			broadcast(server);
		}
	}

	/** Leader: accept (annex) or deny every proposal of the nation. */
	private static boolean handleProposals(MinecraftServer server, ServerPlayer player, NationData nation, boolean accept) {
		int done = 0;
		int left = 0;
		String problem = null;

		for (Map.Entry<String, Map<Long, Proposal>> dimEntry : PROPOSALS.entrySet()) {
			java.util.Iterator<Map.Entry<Long, Proposal>> it = dimEntry.getValue().entrySet().iterator();

			while (it.hasNext()) {
				Map.Entry<Long, Proposal> e = it.next();

				if (!e.getValue().nation().equals(nation.id)) {
					continue;
				}

				if (!accept) {
					it.remove();
					done++;
					continue;
				}

				String p = claimOne(nation, dimEntry.getKey(), e.getKey());

				if (p == null) {
					it.remove();
					done++;
				} else {
					problem = p;
					left++;
				}
			}
		}

		if (!accept) {
			status(player, "Denied " + done + " proposed chunks.", true);
		} else if (left > 0) {
			status(player, "Annexed " + done + " chunks. " + left + " stay proposed: " + problem, done > 0);
		} else {
			status(player, "Annexed " + done + " chunks!", true);
		}

		return done > 0;
	}

	// ---------------------------------------------------------------- nation actions

	private static void handleAction(MinecraftServer server, ServerPlayer player, NationActionPayload a) {
		UUID me = player.getUUID();
		String myName = player.getName().getString();
		NationData mine = nationOf(me);
		boolean changed = false;

		switch (a.action()) {
			case NationActionPayload.CREATE -> {
				if (mine != null) {
					status(player, "Leave your nation before creating a new one.", false);
					return;
				}

				String name = cleanName(a.name());
				String problem = checkNameColor(name, a.color(), null);

				if (problem == null && !Ideology.exists(a.ideology())) {
					problem = "Please choose an ideology.";
				}

				if (problem != null) {
					status(player, problem, false);
					return;
				}

				NationData n = new NationData(UUID.randomUUID());
				n.name = name;
				n.color = a.color();
				n.ideology = Ideology.byName(a.ideology());
				n.leader = me;
				n.members.add(new NationData.Member(me, myName));

				if (a.bannerSlot() >= 0) {
					n.banner = bannerFromSlot(player, a.bannerSlot());
				}

				removeRequestsEverywhere(me);
				NATIONS.put(n.id, n);
				status(player, "The nation of " + n.name + " was founded!", true);
				changed = true;
			}

			case NationActionPayload.EDIT -> {
				if (mine == null || !mine.leader.equals(me)) {
					status(player, "Only the leader can edit the nation.", false);
					return;
				}

				String name = cleanName(a.name());
				String problem = checkNameColor(name, a.color(), mine);

				if (problem == null && !Ideology.exists(a.ideology())) {
					problem = "Please choose an ideology.";
				}

				if (problem != null) {
					status(player, problem, false);
					return;
				}

				mine.name = name;
				mine.color = a.color();
				mine.ideology = Ideology.byName(a.ideology());

				if (a.bannerSlot() == -2) {
					mine.banner = ItemStack.EMPTY;
				} else if (a.bannerSlot() >= 0) {
					ItemStack banner = bannerFromSlot(player, a.bannerSlot());

					if (!banner.isEmpty()) {
						mine.banner = banner;
					}
				}

				status(player, "Saved.", true);
				changed = true;
			}

			case NationActionPayload.REQUEST_JOIN -> {
				NationData target = nationById(a.target());

				if (target == null) {
					status(player, "That nation doesn't exist anymore.", false);
					return;
				}

				if (mine != null) {
					status(player, "You have to leave " + mine.name + " first.", false);
					return;
				}

				if (!target.hasRequest(me)) {
					target.requests.add(new NationData.Member(me, myName));
					notifyPlayer(server, target.leader, myName + " wants to join " + target.name + ".");
				}

				status(player, "Request sent to " + target.name + ".", true);
				changed = true;
			}

			case NationActionPayload.CANCEL_REQUEST -> {
				NationData target = nationById(a.target());

				if (target != null && target.removeRequest(me)) {
					status(player, "Request cancelled.", true);
					changed = true;
				}
			}

			case NationActionPayload.ACCEPT, NationActionPayload.DENY -> {
				UUID who = parseUuid(a.target());

				if (mine == null || !mine.leader.equals(me) || who == null) {
					status(player, "Only the leader can do that.", false);
					return;
				}

				NationData.Member request = null;

				for (NationData.Member m : mine.requests) {
					if (m.id().equals(who)) {
						request = m;
					}
				}

				if (request == null) {
					return;
				}

				mine.removeRequest(who);

				if (a.action() == NationActionPayload.ACCEPT) {
					NationData theirs = nationOf(who);

					if (theirs != null) {
						status(player, request.name() + " already joined " + theirs.name + ".", false);
					} else {
						removeRequestsEverywhere(who);
						mine.members.add(request);
						status(player, request.name() + " joined " + mine.name + ".", true);
						notifyPlayer(server, who, "You are now a member of " + mine.name + "!");
					}
				} else {
					notifyPlayer(server, who, mine.name + " declined your request.");
				}

				changed = true;
			}

			case NationActionPayload.KICK -> {
				UUID who = parseUuid(a.target());

				if (mine == null || !mine.leader.equals(me) || who == null || who.equals(me)) {
					return;
				}

				if (mine.members.removeIf(m -> m.id().equals(who))) {
					mine.officers.remove(who);
					status(player, "Member removed.", true);
					notifyPlayer(server, who, "You were removed from " + mine.name + ".");
					changed = true;
				}
			}

			case NationActionPayload.PROMOTE -> {
				UUID who = parseUuid(a.target());

				if (mine == null || !mine.leader.equals(me) || who == null || !mine.isMember(who) || who.equals(me)) {
					return;
				}

				mine.officers.remove(who);
				mine.officers.add(me); // the old leader stays on as an officer
				mine.leader = who;
				NationData.Member m = mine.member(who);
				status(player, m.name() + " is now the " + mine.ideology.leaderTitle + " of " + mine.name + ".", true);
				notifyPlayer(server, who, "You are now the " + mine.ideology.leaderTitle + " of " + mine.name + "!");
				changed = true;
			}

			case NationActionPayload.LEAVE -> {
				if (mine == null) {
					return;
				}

				mine.members.removeIf(m -> m.id().equals(me));
				mine.officers.remove(me);

				if (mine.members.isEmpty()) {
					disband(mine);
					status(player, mine.name + " has been dissolved.", true);
				} else {
					if (mine.leader.equals(me)) {
						// the member who joined first becomes the new leader
						NationData.Member next = mine.members.get(0);
						mine.leader = next.id();
						mine.officers.remove(next.id());
						notifyPlayer(server, next.id(), "You are now the " + mine.ideology.leaderTitle + " of " + mine.name + "!");
					}

					status(player, "You left " + mine.name + ".", true);
				}

				changed = true;
			}

			case NationActionPayload.MAKE_OFFICER, NationActionPayload.REMOVE_OFFICER -> {
				UUID who = parseUuid(a.target());

				if (mine == null || !mine.leader.equals(me) || who == null || who.equals(me) || !mine.isMember(who)) {
					return;
				}

				NationData.Member m = mine.member(who);

				if (a.action() == NationActionPayload.MAKE_OFFICER) {
					if (!mine.officers.contains(who)) {
						mine.officers.add(who);
					}

					status(player, m.name() + " is now an Officer.", true);
					notifyPlayer(server, who, "You are now an Officer of " + mine.name + " - you can propose new land.");
				} else {
					mine.officers.remove(who);
					status(player, m.name() + " is no longer an Officer.", true);
				}

				changed = true;
			}

			case NationActionPayload.ACCEPT_PROPOSALS, NationActionPayload.DENY_PROPOSALS -> {
				if (mine == null || !mine.leader.equals(me)) {
					status(player, "Only the leader can decide on proposals.", false);
					return;
				}

				changed = handleProposals(server, player, mine, a.action() == NationActionPayload.ACCEPT_PROPOSALS);
			}

			case NationActionPayload.PROPOSE_ALLY, NationActionPayload.ACCEPT_ALLY,
					NationActionPayload.DECLINE_ALLY, NationActionPayload.BREAK_ALLY -> {
				NationData other = nationById(a.target());

				if (mine == null || !mine.leader.equals(me)) {
					status(player, "Only your nation's leader can handle alliances.", false);
					return;
				}

				if (other == null || other == mine) {
					return;
				}

				changed = handleAlliance(server, player, mine, other, a.action());
			}

			default -> {
			}
		}

		if (changed) {
			save(server);
			broadcast(server);
		}
	}

	private static boolean handleAlliance(MinecraftServer server, ServerPlayer player, NationData mine, NationData other, int action) {
		switch (action) {
			case NationActionPayload.PROPOSE_ALLY -> {
				if (mine.allies.contains(other.id)) {
					status(player, "You are already allied with " + other.name + ".", false);
					return false;
				}

				if (mine.allyRequests.contains(other.id)) {
					// they already asked us -> this is a yes
					return handleAlliance(server, player, mine, other, NationActionPayload.ACCEPT_ALLY);
				}

				if (!other.allyRequests.contains(mine.id)) {
					other.allyRequests.add(mine.id);
				}

				status(player, "Alliance offered to " + other.name + ".", true);
				notifyLeader(server, other, mine.name + " offers you an alliance! (Nations tab)");
				return true;
			}

			case NationActionPayload.ACCEPT_ALLY -> {
				if (!mine.allyRequests.remove(other.id)) {
					return false;
				}

				other.allyRequests.remove(mine.id);
				mine.allies.add(other.id);
				other.allies.add(mine.id);
				status(player, mine.name + " and " + other.name + " are now allies!", true);
				notifyLeader(server, other, other.name + " and " + mine.name + " are now allies!");
				return true;
			}

			case NationActionPayload.DECLINE_ALLY -> {
				if (mine.allyRequests.remove(other.id)) {
					status(player, "Declined the alliance with " + other.name + ".", true);
					notifyLeader(server, other, mine.name + " declined your alliance.");
					return true;
				}

				return false;
			}

			case NationActionPayload.BREAK_ALLY -> {
				if (mine.allies.remove(other.id)) {
					other.allies.remove(mine.id);
					status(player, "The alliance with " + other.name + " is over.", true);
					notifyLeader(server, other, mine.name + " broke the alliance with " + other.name + ".");
					return true;
				}

				if (other.allyRequests.remove(mine.id)) {
					status(player, "Alliance offer taken back.", true);
					return true;
				}

				return false;
			}

			default -> {
				return false;
			}
		}
	}

	// ---------------------------------------------------------------- sync

	private static NationsSyncPayload buildSync() {
		List<NationsSyncPayload.Claim> claims = new ArrayList<>();

		for (Map.Entry<String, Map<Long, List<UUID>>> dimEntry : CLAIMS.entrySet()) {
			for (Map.Entry<Long, List<UUID>> e : dimEntry.getValue().entrySet()) {
				long key = e.getKey();

				// one entry per claiming nation, owner first
				for (UUID nation : e.getValue()) {
					claims.add(new NationsSyncPayload.Claim(dimEntry.getKey(), MapNationsMod.keyX(key), MapNationsMod.keyZ(key), nation));
				}
			}
		}

		// villagers living in each nation's land (the owner of a chunk gets its villagers)
		for (NationData n : NATIONS.values()) {
			n.population = 0;
		}

		for (Map.Entry<String, Map<Long, List<UUID>>> dimEntry : CLAIMS.entrySet()) {
			for (Map.Entry<Long, List<UUID>> e : dimEntry.getValue().entrySet()) {
				if (!e.getValue().isEmpty()) {
					NationData owner = NATIONS.get(e.getValue().get(0));

					if (owner != null) {
						owner.population += ServerPopulation.at(dimEntry.getKey(), e.getKey());
					}
				}
			}
		}

		List<NationsSyncPayload.Proposal> proposals = new ArrayList<>();

		for (Map.Entry<String, Map<Long, Proposal>> dimEntry : PROPOSALS.entrySet()) {
			for (Map.Entry<Long, Proposal> e : dimEntry.getValue().entrySet()) {
				proposals.add(new NationsSyncPayload.Proposal(dimEntry.getKey(), MapNationsMod.keyX(e.getKey()), MapNationsMod.keyZ(e.getKey()),
						e.getValue().nation(), e.getValue().proposerName()));
			}
		}

		return new NationsSyncPayload(new ArrayList<>(NATIONS.values()), claims, proposals, ServerAlliances.all());
	}

	static void broadcast(MinecraftServer server) {
		NationsSyncPayload payload = buildSync();

		for (ServerPlayer p : PlayerLookup.all(server)) {
			if (ServerPlayNetworking.canSend(p, NationsSyncPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}

		// who is in which nation may have changed -> who can see which markers too
		ServerMarkers.broadcast(server);
	}

	private static void sendPlayerPositions(MinecraftServer server) {
		List<PlayersPayload.Entry> list = new ArrayList<>();

		for (ServerPlayer p : PlayerLookup.all(server)) {
			list.add(new PlayersPayload.Entry(p.getUUID(), p.getName().getString(),
					p.level().dimension().identifier().toString(), p.getX(), p.getZ(), p.getYRot()));
		}

		PlayersPayload payload = new PlayersPayload(list);

		for (ServerPlayer p : PlayerLookup.all(server)) {
			if (ServerPlayNetworking.canSend(p, PlayersPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	// ---------------------------------------------------------------- saving / loading

	private static void load(MinecraftServer server) {
		NATIONS.clear();
		CLAIMS.clear();
		PROPOSALS.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("mapnationswars_nations.json");

		if (!Files.exists(file)) {
			return;
		}

		try {
			var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

			for (JsonElement el : root.getAsJsonArray("nations")) {
				JsonObject o = el.getAsJsonObject();
				NationData n = new NationData(UUID.fromString(o.get("id").getAsString()));
				n.name = o.get("name").getAsString();
				n.color = o.get("color").getAsInt();
				n.ideology = Ideology.byName(o.get("ideology").getAsString());
				n.leader = UUID.fromString(o.get("leader").getAsString());
				readMembers(o.getAsJsonArray("members"), n.members);
				readMembers(o.getAsJsonArray("requests"), n.requests);
				readIds(o.getAsJsonArray("allies"), n.allies);
				readIds(o.getAsJsonArray("allyRequests"), n.allyRequests);
				readIds(o.getAsJsonArray("officers"), n.officers);

				if (o.has("banner")) {
					n.banner = ItemStack.CODEC.parse(ops, o.get("banner")).result().orElse(ItemStack.EMPTY);
				}

				if (!n.members.isEmpty()) {
					if (!n.isMember(n.leader)) {
						n.leader = n.members.get(0).id();
					}

					NATIONS.put(n.id, n);
				}
			}

			if (root.has("proposals")) {
				for (JsonElement el : root.getAsJsonArray("proposals")) {
					JsonObject o = el.getAsJsonObject();
					UUID nation = UUID.fromString(o.get("nation").getAsString());

					if (NATIONS.containsKey(nation)) {
						PROPOSALS.computeIfAbsent(o.get("dimension").getAsString(), d -> new HashMap<>())
								.put(MapNationsMod.chunkKey(o.get("x").getAsInt(), o.get("z").getAsInt()),
										new Proposal(nation, UUID.fromString(o.get("proposer").getAsString()), o.get("proposerName").getAsString()));
					}
				}
			}

			for (JsonElement el : root.getAsJsonArray("claims")) {
				JsonObject o = el.getAsJsonObject();
				UUID nation = UUID.fromString(o.get("nation").getAsString());

				if (NATIONS.containsKey(nation)) {
					List<UUID> list = CLAIMS.computeIfAbsent(o.get("dimension").getAsString(), d -> new HashMap<>())
							.computeIfAbsent(MapNationsMod.chunkKey(o.get("x").getAsInt(), o.get("z").getAsInt()), k -> new ArrayList<>());

					if (!list.contains(nation)) {
						list.add(nation);
					}
				}
			}

			// forget alliances with nations that no longer exist
			for (NationData n : NATIONS.values()) {
				n.allies.removeIf(id -> !NATIONS.containsKey(id));
				n.allyRequests.removeIf(id -> !NATIONS.containsKey(id));
			}

			MapNationsMod.LOGGER.info("Loaded {} nations", NATIONS.size());
		} catch (Exception e) {
			MapNationsMod.LOGGER.error("Could not read {}", file, e);
		}
	}

	private static void readMembers(JsonArray arr, List<NationData.Member> into) {
		if (arr == null) {
			return;
		}

		for (JsonElement el : arr) {
			JsonObject o = el.getAsJsonObject();
			into.add(new NationData.Member(UUID.fromString(o.get("id").getAsString()), o.get("name").getAsString()));
		}
	}

	private static void readIds(JsonArray arr, List<UUID> into) {
		if (arr == null) {
			return;
		}

		for (JsonElement el : arr) {
			into.add(UUID.fromString(el.getAsString()));
		}
	}

	private static JsonArray writeIds(List<UUID> list) {
		JsonArray arr = new JsonArray();

		for (UUID id : list) {
			arr.add(id.toString());
		}

		return arr;
	}

	private static JsonArray writeMembers(List<NationData.Member> list) {
		JsonArray arr = new JsonArray();

		for (NationData.Member m : list) {
			JsonObject o = new JsonObject();
			o.addProperty("id", m.id().toString());
			o.addProperty("name", m.name());
			arr.add(o);
		}

		return arr;
	}

	private static void save(MinecraftServer server) {
		if (file == null) {
			return;
		}

		var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		JsonArray nations = new JsonArray();

		for (NationData n : NATIONS.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", n.id.toString());
			o.addProperty("name", n.name);
			o.addProperty("color", n.color);
			o.addProperty("ideology", n.ideology.name());
			o.addProperty("leader", n.leader.toString());
			o.add("members", writeMembers(n.members));
			o.add("requests", writeMembers(n.requests));
			o.add("allies", writeIds(n.allies));
			o.add("allyRequests", writeIds(n.allyRequests));
			o.add("officers", writeIds(n.officers));

			if (!n.banner.isEmpty()) {
				ItemStack.CODEC.encodeStart(ops, n.banner).result().ifPresent(json -> o.add("banner", json));
			}

			nations.add(o);
		}

		JsonArray claims = new JsonArray();

		for (Map.Entry<String, Map<Long, List<UUID>>> dimEntry : CLAIMS.entrySet()) {
			for (Map.Entry<Long, List<UUID>> e : dimEntry.getValue().entrySet()) {
				// one entry per claiming nation, in order (the first one owns the land)
				for (UUID nation : e.getValue()) {
					JsonObject o = new JsonObject();
					o.addProperty("dimension", dimEntry.getKey());
					o.addProperty("x", MapNationsMod.keyX(e.getKey()));
					o.addProperty("z", MapNationsMod.keyZ(e.getKey()));
					o.addProperty("nation", nation.toString());
					claims.add(o);
				}
			}
		}

		JsonObject root = new JsonObject();
		root.add("nations", nations);
		root.add("claims", claims);
		JsonArray proposals = new JsonArray();

		for (Map.Entry<String, Map<Long, Proposal>> dimEntry : PROPOSALS.entrySet()) {
			for (Map.Entry<Long, Proposal> e : dimEntry.getValue().entrySet()) {
				JsonObject o = new JsonObject();
				o.addProperty("dimension", dimEntry.getKey());
				o.addProperty("x", MapNationsMod.keyX(e.getKey()));
				o.addProperty("z", MapNationsMod.keyZ(e.getKey()));
				o.addProperty("nation", e.getValue().nation().toString());
				o.addProperty("proposer", e.getValue().proposer().toString());
				o.addProperty("proposerName", e.getValue().proposerName());
				proposals.add(o);
			}
		}

		root.add("proposals", proposals);

		try {
			Path tmp = file.resolveSibling("mapnationswars_nations.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			MapNationsMod.LOGGER.error("Could not save {}", file, e);
		}
	}
}
