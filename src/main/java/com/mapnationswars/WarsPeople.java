package com.mapnationswars;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import com.mapnationswars.nation.Faction;
import com.mapnationswars.nation.LetterData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.nation.Ranks;
import com.mapnationswars.network.PersonalActionPayload;
import com.mapnationswars.network.PersonalSyncPayload;

/**
 * Map Nations WARS 1.9: politics for one person.
 * Every nation has an opinion of every player (standing, -100 .. 100). Players write their own letters to any nation:
 * messages, gifts, asking to join, asking for promotion, a personal declaration of war (their guards will hunt you)
 * and offers of peace. Outlaws (personal war, or standing -60 or lower) are attacked by village guards.
 * Saved in the world folder as mapnationswars_people.json.
 */
public final class WarsPeople {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** At or below this, a nation's guards attack you even without a war. */
	public static final int OUTLAW = -60;
	private static final int PEACE_PRICE = 30;

	/** player -> nation -> standing */
	private static final Map<UUID, Map<UUID, Integer>> STANDING = new HashMap<>();
	/** player -> nations they are personally at war with */
	private static final Map<UUID, Set<UUID>> WARS = new HashMap<>();
	private static final Map<UUID, Long> LAST_SENT = new HashMap<>();
	private static final Map<UUID, Long> LAST_STIR = new HashMap<>();
	private static Path file;

	private WarsPeople() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(WarsPeople::load);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save();
			STANDING.clear();
			WARS.clear();
			file = null;
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			sync(handler.player);
			handler.player.sendSystemMessage(Component.literal("⚑ Map Nations WARS: press M and open the \"You\" tab to see everything you can do - "
					+ "join a nation, duties, ranks, letters, revolts, your own nation.").withColor(0xFFD27A));
		});

		ServerPlayNetworking.registerGlobalReceiver(PersonalActionPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.level().getServer();

			if (server != null) {
				server.execute(() -> handle(server, player, payload));
			}
		});
	}

	// ---------------------------------------------------------------- standing

	static int standing(UUID player, UUID nation) {
		Map<UUID, Integer> m = STANDING.get(player);
		return m == null ? 0 : m.getOrDefault(nation, 0);
	}

	static void changeStanding(ServerPlayer player, UUID nation, int amount) {
		if (nation == null || amount == 0) {
			return;
		}

		STANDING.computeIfAbsent(player.getUUID(), k -> new HashMap<>())
				.merge(nation, amount, (a, b) -> Math.max(-100, Math.min(100, a + b)));
		sync(player);
	}

	static boolean atPersonalWar(UUID player, UUID nation) {
		Set<UUID> s = WARS.get(player);
		return s != null && s.contains(nation);
	}

	/** Does this nation's guards attack the player? (war with their nation, personal war, or outlaw) */
	static boolean hostileTo(ServerPlayer player, NationData n) {
		if (n == null || n.isMember(player.getUUID())) {
			return false;
		}

		NationData mine = ServerNations.nationOf(player.getUUID());
		return atPersonalWar(player.getUUID(), n.id) || standing(player.getUUID(), n.id) <= OUTLAW
				|| (mine != null && WarsDiplomacy.atWar(mine.id, n.id));
	}

	/** Why an AI nation won't take this player in (null = it will). */
	static String joinRefusal(UUID player, NationData n) {
		int s = standing(player, n.id);

		if (atPersonalWar(player, n.id)) {
			return "You are at war with " + n.name + ". Make peace first (Letters: Peace).";
		}

		return switch (n.faction) {
			case UNDEAD -> "The dead take in no one alive.";
			case ILLAGER -> s >= 20 ? null : "The illagers of " + n.name + " don't trust you (opinion " + s + ", they want 20). Send them gifts.";
			case PIGLIN -> s >= 30 ? null : "The piglins of " + n.name + " want gold first (opinion " + s + ", they want 30). Send them gifts.";
			default -> s >= -10 ? null : n.name + " doesn't want you (opinion " + s + "). Win them back with gifts or duties.";
		};
	}

	// ---------------------------------------------------------------- personal letters

	/** A player writes a letter for themselves (not for their nation). */
	static void sendPersonal(MinecraftServer server, ServerPlayer player, NationData to, LetterData.Type type, int amount, String text) {
		UUID me = player.getUUID();
		long now = System.currentTimeMillis();

		if (now - LAST_SENT.getOrDefault(me, 0L) < 3000) {
			ServerNations.status(player, "Wait a moment before sending another letter.", false);
			return;
		}

		if (!type.forPeople) {
			ServerNations.status(player, "Only nations write that kind of letter.", false);
			return;
		}

		NationData mine = ServerNations.nationOf(me);
		String problem = switch (type) {
			case JOIN -> mine != null ? "Leave " + mine.name + " first." : (to.aiRuled() ? joinRefusal(me, to) : null);
			case PROMOTION -> !to.isMember(me) ? "You can only ask your own nation for a promotion." : to.leader.equals(me) ? "You already lead it." : null;
			case WAR -> to.isMember(me) ? "You can't fight your own nation - try a coup or a revolt." : atPersonalWar(me, to.id) ? "You are already at war with them." : null;
			case PEACE -> !atPersonalWar(me, to.id) ? "You are not at war with " + to.name + "." : amount < 1 ? "Offer some emeralds." : null;
			case GIFT -> amount < 1 ? "How many emeralds?" : null;
			default -> null;
		};

		if (problem != null) {
			ServerNations.status(player, problem, false);
			return;
		}

		// emeralds change hands now (and come back if refused)
		if (type == LetterData.Type.GIFT || type == LetterData.Type.PEACE) {
			int taken = WarsItems.takeEmeralds(player, amount);

			if (taken <= 0) {
				ServerNations.status(player, "You carry no emeralds.", false);
				return;
			}

			amount = taken;
		}

		LAST_SENT.put(me, now);
		LetterData l = new LetterData(UUID.randomUUID());
		l.personal = true;
		l.from = me;
		l.to = to.id;
		l.sender = player.getName().getString();
		l.type = type;
		l.amount = amount;
		l.text = text.length() > 240 ? text.substring(0, 240) : text;
		l.day = server.overworld().getGameTime() / WarsEconomy.DAY_TICKS;
		WarsDiplomacy.addLetter(l);

		if (type == LetterData.Type.WAR || type == LetterData.Type.GIFT || type == LetterData.Type.MESSAGE) {
			// no answer needed
			l.status = LetterData.Status.DONE;
			apply(server, player, l, true);
		} else if (to.aiRuled()) {
			boolean accept = aiAccepts(player, to, l);
			l.status = accept ? LetterData.Status.ACCEPTED : LetterData.Status.REFUSED;
			apply(server, player, l, accept);
		} else {
			for (NationData.Member m : to.members) {
				if (to.leader.equals(m.id()) || to.rankOf(m.id()) >= Ranks.MINISTER) {
					ServerNations.notifyPlayer(server, m.id(), "✉ " + l.sender + " wrote to " + to.name + ": " + type.displayName + ". Open the Letters tab.");
				}
			}

			ServerNations.status(player, "Your letter to " + to.name + " was sent. Its leaders will answer.", true);
		}

		WarsDiplomacy.saveNow();
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
	}

	private static boolean aiAccepts(ServerPlayer player, NationData to, LetterData l) {
		UUID me = player.getUUID();

		return switch (l.type) {
			case JOIN -> joinRefusal(me, to) == null;
			case PROMOTION -> {
				int rank = to.rankOf(me);
				yield rank + 1 < Ranks.NAMES.length && to.merit.getOrDefault(me, 0) >= Ranks.MERIT[rank + 1];
			}
			case PEACE -> l.amount >= PEACE_PRICE;
			default -> true;
		};
	}

	/** What a personal letter does (accepted = the nation said yes). */
	static void apply(MinecraftServer server, ServerPlayer player, LetterData l, boolean accepted) {
		NationData to = ServerNations.nation(l.to);
		UUID me = l.from;

		if (to == null) {
			return;
		}

		String voice = to.aiRuled() ? to.leaderName() + ", " + to.ideology.leaderTitle + " of " + to.name : to.name;

		switch (l.type) {
			case MESSAGE -> {
				changeStanding(player, to.id, 1);
				l.reply = "\"We have read your words.\" - " + voice;
			}
			case GIFT -> {
				to.treasury += l.amount;
				changeStanding(player, to.id, Math.min(25, 1 + l.amount / 2));
				l.reply = "\"Your generosity will be remembered.\" - " + voice;
				ServerNations.status(player, "You gave " + l.amount + " emeralds to " + to.name + ". They like you more.", true);
			}
			case WAR -> {
				WARS.computeIfAbsent(me, k -> new HashSet<>()).add(to.id);
				STANDING.computeIfAbsent(me, k -> new HashMap<>()).put(to.id, -100);
				l.reply = "\"Then you will die at our gates.\" - " + voice;
				WarsDiplomacy.news(server, "⚔ " + l.sender + " declared a personal war on " + to.name + "! Its guards will hunt them.");
				sync(player);
			}
			case PEACE -> {
				if (accepted) {
					Set<UUID> wars = WARS.get(me);

					if (wars != null) {
						wars.remove(to.id);
					}

					to.treasury += l.amount;
					STANDING.computeIfAbsent(me, k -> new HashMap<>()).put(to.id, -30);
					l.reply = "\"Very well. Do not cross us again.\" - " + voice;
					ServerNations.status(player, "Peace with " + to.name + ". Their guards will leave you alone.", true);
				} else {
					WarsPolitics.giveEmeralds(player, l.amount);
					l.reply = "\"That is not enough. We want at least " + PEACE_PRICE + " emeralds.\" - " + voice;
					ServerNations.status(player, to.name + " refused your peace offer.", false);
				}

				sync(player);
			}
			case JOIN -> {
				if (accepted && ServerNations.nationOf(me) == null) {
					ServerNations.addMember(to, me, l.sender);
					l.reply = "\"Welcome, citizen. Serve us well.\" - " + voice;
					ServerNations.status(player, "You joined " + to.name + "! Open the You tab to see your duties.", true);
				} else {
					String why = to.aiRuled() ? joinRefusal(me, to) : null;
					l.reply = "\"No.\" - " + voice + (why != null ? " (" + why + ")" : "");
					ServerNations.status(player, to.name + " refused you." + (why != null ? " " + why : ""), false);
				}
			}
			case PROMOTION -> {
				int rank = to.rankOf(me);

				if (accepted && rank + 1 < Ranks.NAMES.length) {
					to.ranks.put(me, rank + 1);
					ServerNations.syncOfficers(to);
					l.reply = "\"You have earned it. You are now a " + Ranks.name(rank + 1) + ".\" - " + voice;
					ServerNations.status(player, "Promoted to " + Ranks.name(rank + 1) + " of " + to.name + "!", true);
				} else {
					int need = rank + 1 < Ranks.NAMES.length ? Ranks.MERIT[rank + 1] - to.merit.getOrDefault(me, 0) : 0;
					l.reply = "\"Not yet.\" - " + voice + (to.aiRuled() && need > 0 ? " (you need " + need + " more merit - do duties)" : "");
					ServerNations.status(player, "No promotion yet." + (need > 0 ? " You need " + need + " more merit." : ""), false);
				}
			}
			default -> {
			}
		}
	}

	/** A player-led nation's leader answered a personal letter. */
	static void answered(MinecraftServer server, LetterData l, boolean accept) {
		ServerPlayer writer = null;

		for (ServerPlayer p : PlayerLookup.all(server)) {
			if (p.getUUID().equals(l.from)) {
				writer = p;
			}
		}

		if (writer == null) {
			// the writer is away: only what doesn't need them
			NationData to = ServerNations.nation(l.to);

			if (to != null && accept && l.type == LetterData.Type.JOIN && ServerNations.nationOf(l.from) == null) {
				ServerNations.addMember(to, l.from, l.sender);
			} else if (to != null && accept && l.type == LetterData.Type.PROMOTION && to.isMember(l.from)) {
				to.ranks.put(l.from, Math.min(Ranks.MINISTER, to.rankOf(l.from) + 1));
				ServerNations.syncOfficers(to);
			} else if (l.type == LetterData.Type.PEACE && accept) {
				Set<UUID> wars = WARS.get(l.from);

				if (wars != null) {
					wars.remove(l.to);
				}

				if (to != null) {
					to.treasury += l.amount;
				}
			}

			l.reply = accept ? "Accepted." : "Refused.";
			save();
			return;
		}

		apply(server, writer, l, accept);
		save();
	}

	// ---------------------------------------------------------------- actions

	private static void handle(MinecraftServer server, ServerPlayer player, PersonalActionPayload a) {
		switch (a.action()) {
			case PersonalActionPayload.ABANDON_DUTY -> WarsDuties.abandon(player, a.target());
			case PersonalActionPayload.NEW_DUTY -> WarsDuties.offer(server, player, true);
			case PersonalActionPayload.STIR_UNREST -> {
				ProvinceData p;

				try {
					p = WarsWorld.province(UUID.fromString(a.target()));
				} catch (IllegalArgumentException e) {
					p = null;
				}

				if (p != null) {
					stir(server, player, p);
				}
			}
			default -> {
			}
		}
	}

	/**
	 * Stirring up a village against its rulers: costs 10 emeralds, needs some of its people's support,
	 * raises unrest a lot. If the rulers catch you, they hate you - maybe enough to set the guards on you.
	 */
	static void stir(MinecraftServer server, ServerPlayer player, ProvinceData p) {
		UUID me = player.getUUID();
		NationData owner = ServerNations.nation(p.nation);
		NationData mine = ServerNations.nationOf(me);

		if (owner == null || p.abandoned || p.type != ProvinceData.Type.VILLAGE) {
			ServerNations.status(player, "There is nobody here to stir up.", false);
			return;
		}

		if (owner == mine) {
			ServerNations.status(player, "That is your own nation. To take it over, try an election or a coup.", false);
			return;
		}

		if (!player.level().dimension().identifier().toString().equals(p.dimension) || Math.hypot(player.getX() - p.x, player.getZ() - p.z) > 96) {
			ServerNations.status(player, "Go to " + p.name + " and talk to its mayor.", false);
			return;
		}

		long now = server.overworld().getGameTime();

		if (now - LAST_STIR.getOrDefault(me, -10000L) < 600) {
			ServerNations.status(player, "Give the people time to talk (30 seconds).", false);
			return;
		}

		int support = WarsRevolts.support(me, p.id);

		if (support < 20) {
			ServerNations.status(player, "Nobody in " + p.name + " listens to you yet (support " + support + "/20). Help them first: duties, gifts.", false);
			return;
		}

		if (p.capital) {
			ServerNations.status(player, p.name + " is a capital - its people stay loyal.", false);
			return;
		}

		if (WarsItems.countEmeralds(player) < 10) {
			ServerNations.status(player, "Stirring up the people costs 10 emeralds (bribes, beer, pamphlets).", false);
			return;
		}

		WarsItems.takeEmeralds(player, 10);
		LAST_STIR.put(me, now);
		p.unrest = Math.min(100, p.unrest + 12);
		p.happiness = Math.max(0, p.happiness - 3);
		double caught = Math.max(0.05, 0.3 - support / 400.0);

		if (Math.random() < caught) {
			changeStanding(player, owner.id, -35);
			ServerNations.status(player, "⚠ The guards of " + owner.name + " caught you stirring up trouble! They hate you now"
					+ (standing(me, owner.id) <= OUTLAW ? " - you are an OUTLAW, their guards will attack you." : "."), false);
		} else {
			ServerNations.status(player, "The people of " + p.name + " listen to you. Unrest " + p.unrest + "%." + (p.unrest >= 100 ? "" : " At 100% they rise up."), true);
		}

		if (p.unrest >= 100) {
			WarsRevolts.revolt(server, p); // you are the one they follow, if they back you enough
		} else {
			WarsWorld.saveNow();
			WarsWorld.broadcast(server);
		}
	}

	// ---------------------------------------------------------------- sync and saving

	static void sync(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, PersonalSyncPayload.TYPE)) {
			return;
		}

		List<PersonalSyncPayload.Standing> list = new ArrayList<>();
		Map<UUID, Integer> m = STANDING.getOrDefault(player.getUUID(), Map.of());
		Set<UUID> wars = WARS.getOrDefault(player.getUUID(), Set.of());
		Set<UUID> all = new HashSet<>(m.keySet());
		all.addAll(wars);

		for (UUID n : all) {
			list.add(new PersonalSyncPayload.Standing(n, m.getOrDefault(n, 0), wars.contains(n)));
		}

		ServerPlayNetworking.send(player, new PersonalSyncPayload(list, WarsDuties.of(player.getUUID())));
	}

	private static void load(MinecraftServer server) {
		STANDING.clear();
		WARS.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("mapnationswars_people.json");

		if (!Files.exists(file)) {
			return;
		}

		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject standing = root.getAsJsonObject("standing");

			for (String player : standing.keySet()) {
				Map<UUID, Integer> m = new HashMap<>();
				JsonObject o = standing.getAsJsonObject(player);

				for (String n : o.keySet()) {
					m.put(UUID.fromString(n), o.get(n).getAsInt());
				}

				STANDING.put(UUID.fromString(player), m);
			}

			JsonObject wars = root.getAsJsonObject("wars");

			for (String player : wars.keySet()) {
				Set<UUID> s = new HashSet<>();

				for (JsonElement el : wars.getAsJsonArray(player)) {
					s.add(UUID.fromString(el.getAsString()));
				}

				WARS.put(UUID.fromString(player), s);
			}
		} catch (Exception e) {
			MapNationsMod.LOGGER.error("Could not read {}", file, e);
		}
	}

	static void save() {
		if (file == null) {
			return;
		}

		JsonObject standing = new JsonObject();

		for (Map.Entry<UUID, Map<UUID, Integer>> e : STANDING.entrySet()) {
			JsonObject o = new JsonObject();

			for (Map.Entry<UUID, Integer> s : e.getValue().entrySet()) {
				o.addProperty(s.getKey().toString(), s.getValue());
			}

			standing.add(e.getKey().toString(), o);
		}

		JsonObject wars = new JsonObject();

		for (Map.Entry<UUID, Set<UUID>> e : WARS.entrySet()) {
			JsonArray arr = new JsonArray();

			for (UUID n : e.getValue()) {
				arr.add(n.toString());
			}

			wars.add(e.getKey().toString(), arr);
		}

		JsonObject root = new JsonObject();
		root.add("standing", standing);
		root.add("wars", wars);

		try {
			Path tmp = file.resolveSibling("mapnationswars_people.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			MapNationsMod.LOGGER.error("Could not save {}", file, e);
		}
	}

	/** Every player's personal wars (for the guards). */
	static boolean anyPersonalWar() {
		for (Set<UUID> s : WARS.values()) {
			if (!s.isEmpty()) {
				return true;
			}
		}

		return false;
	}

	static Faction factionOf(UUID nation) {
		NationData n = ServerNations.nation(nation);
		return n == null ? Faction.VILLAGER : n.faction;
	}
}
