package com.mapnationswars;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import com.mapnationswars.nation.LetterData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.nation.Ranks;
import com.mapnationswars.nation.Relations;
import com.mapnationswars.network.DiplomacySyncPayload;
import com.mapnationswars.network.LetterActionPayload;

/**
 * Map Nations WARS stage 4: letters and diplomacy.
 * Leaders and Ministers write letters to other nations: messages, gifts, trade, alliances, peace, demands and war.
 * Nations ruled by the game answer straight away, depending on how much they like you and how strong you are;
 * nations ruled by players answer in their Letters tab.
 * Saved in the world folder as mapnationswars_diplomacy.json.
 */
public final class WarsDiplomacy {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_LETTERS = 300;
	private static final int TRADE_INCOME = 3;

	private static final Map<String, Relations.Relation> RELATIONS = new HashMap<>();
	private static final Map<String, UUID[]> PAIRS = new HashMap<>();
	private static final List<LetterData> LETTERS = new ArrayList<>();
	private static final Map<UUID, Long> LAST_SENT = new HashMap<>();
	/** The world's chronicle: wars, battles, conquests, revolts (newest last). */
	private static final List<String> NEWS = new ArrayList<>();
	private static final int MAX_NEWS = 60;
	private static long today = 0;
	private static Path file;

	private WarsDiplomacy() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(WarsDiplomacy::load);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save();
			RELATIONS.clear();
			PAIRS.clear();
			LETTERS.clear();
			NEWS.clear();
			file = null;
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendTo(handler.player));

		ServerPlayNetworking.registerGlobalReceiver(LetterActionPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.level().getServer();

			if (server != null) {
				server.execute(() -> handle(server, player, payload));
			}
		});
	}

	// ---------------------------------------------------------------- relations

	private static Relations.Relation relation(UUID a, UUID b) {
		String key = Relations.key(a, b);
		PAIRS.putIfAbsent(key, new UUID[] {a, b});
		return RELATIONS.computeIfAbsent(key, k -> new Relations.Relation());
	}

	/** How much nation a likes nation b (-100 .. 100). */
	static int opinion(NationData a, NationData b) {
		Relations.Relation r = RELATIONS.get(Relations.key(a.id, b.id));
		int o = Relations.base(a, b);

		if (r != null) {
			o += r.modifier + (r.war ? -30 : 0) + (r.trade ? 5 : 0);
		}

		if (a.allies.contains(b.id)) {
			o += 20;
		}

		return Relations.clamp(o);
	}

	static boolean atWar(UUID a, UUID b) {
		Relations.Relation r = RELATIONS.get(Relations.key(a, b));
		return r != null && r.war;
	}

	/** Rough strength of a nation: its villagers, provinces and players. */
	static int strength(NationData n) {
		int s = n.members.size() * 10 + WarsWar.armyStrength(n.id) / 3;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation)) {
				s += 5 + p.population;
			}
		}

		return s;
	}

	private static void change(UUID a, UUID b, int amount) {
		Relations.Relation r = relation(a, b);
		r.modifier = Relations.clamp(r.modifier + amount);
	}

	// ---------------------------------------------------------------- letters

	private static boolean canWrite(NationData n, UUID player) {
		return n != null && (n.leader.equals(player) || (n.isMember(player) && n.rankOf(player) >= Ranks.MINISTER));
	}

	private static void handle(MinecraftServer server, ServerPlayer player, LetterActionPayload a) {
		UUID me = player.getUUID();
		NationData mine = ServerNations.nationOf(me);

		// a letter from one person (1.9): anyone can write those
		if (a.action() == LetterActionPayload.SEND && a.personal()) {
			NationData to;

			try {
				to = ServerNations.nation(UUID.fromString(a.target()));
			} catch (IllegalArgumentException e) {
				to = null;
			}

			if (to == null) {
				ServerNations.status(player, "Pick a nation to write to.", false);
				return;
			}

			WarsPeople.sendPersonal(server, player, to, LetterData.Type.byName(a.letterType()), Math.max(0, a.amount()), a.text());
			return;
		}

		if (!canWrite(mine, me)) {
			ServerNations.status(player, "Only your nation's leader and its Ministers can handle letters.", false);
			return;
		}

		if (a.action() == LetterActionPayload.SEND) {
			send(server, player, mine, a);
		} else {
			answer(server, player, mine, a.target(), a.action() == LetterActionPayload.ACCEPT);
		}
	}

	private static void send(MinecraftServer server, ServerPlayer player, NationData from, LetterActionPayload a) {
		long now = System.currentTimeMillis();

		if (now - LAST_SENT.getOrDefault(player.getUUID(), 0L) < 3000) {
			ServerNations.status(player, "Wait a moment before sending another letter.", false);
			return;
		}

		NationData to;

		try {
			to = ServerNations.nation(UUID.fromString(a.target()));
		} catch (IllegalArgumentException e) {
			to = null;
		}

		if (to == null || to == from) {
			ServerNations.status(player, "Pick a nation to write to.", false);
			return;
		}

		LetterData.Type type = LetterData.Type.byName(a.letterType());

		if (!type.forNations) {
			ServerNations.status(player, "That is a letter you write for yourself, not for your nation.", false);
			return;
		}

		Relations.Relation r = relation(from.id, to.id);
		int amount = Math.max(0, a.amount());
		String problem = switch (type) {
			case GIFT -> amount <= 0 ? "How many emeralds do you want to send?" : (from.treasury < amount ? "Your treasury only has " + from.treasury + " emeralds." : null);
			case TRIBUTE -> amount <= 0 ? "How many emeralds do you demand?" : null;
			case WAR -> r.war ? "You are already at war with " + to.name + "." : null;
			case PEACE -> !r.war ? "You are not at war with " + to.name + "." : null;
			case ALLIANCE -> r.war ? "You can't ally with a nation you are at war with." : (from.allies.contains(to.id) ? "You are already allies." : null);
			case TRADE -> r.war ? "No trade during a war." : (r.trade ? "You already trade with " + to.name + "." : null);
			default -> null;
		};

		if (problem != null) {
			ServerNations.status(player, problem, false);
			return;
		}

		LAST_SENT.put(player.getUUID(), now);
		LetterData l = new LetterData(UUID.randomUUID());
		l.from = from.id;
		l.to = to.id;
		l.sender = player.getName().getString() + ", " + (from.leader.equals(player.getUUID()) ? from.ideology.leaderTitle : "Minister");
		l.type = type;
		l.amount = amount;
		l.text = a.text().length() > 240 ? a.text().substring(0, 240) : a.text();
		l.day = server.overworld().getGameTime() / WarsEconomy.DAY_TICKS;

		if (type == LetterData.Type.GIFT) {
			from.treasury -= amount; // held until they answer
		}

		LETTERS.add(l);

		if (type == LetterData.Type.WAR) {
			// a declaration needs no answer
			l.status = LetterData.Status.DONE;
			apply(server, l, true);
			l.reply = warReply(to);
			announceWar(server, from, to);
		} else if (to.aiRuled()) {
			aiAnswer(server, l, from, to);
		} else {
			ServerNations.notifyPlayer(server, to.leader, "A letter from " + from.name + " arrived (" + type.displayName + "). Open the Letters tab.");
		}

		ServerNations.status(player, type == LetterData.Type.WAR ? "You declared war on " + to.name + "!" : "Your letter to " + to.name + " was sent.", true);
		trim();
		save();
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
	}

	private static void answer(MinecraftServer server, ServerPlayer player, NationData mine, String letterId, boolean accept) {
		LetterData l = null;

		for (LetterData x : LETTERS) {
			if (x.id.toString().equals(letterId)) {
				l = x;
			}
		}

		if (l == null || !mine.id.equals(l.to) || l.status != LetterData.Status.PENDING) {
			return;
		}

		if (l.personal && l.from.equals(player.getUUID())) {
			ServerNations.status(player, "You can't answer your own letter.", false);
			return;
		}

		l.status = accept ? LetterData.Status.ACCEPTED : LetterData.Status.REFUSED;

		if (l.personal) {
			WarsPeople.answered(server, l, accept);
			save();
			ServerNations.saveNow(server);
			ServerNations.broadcast(server);
			return;
		}

		NationData from = ServerNations.nation(l.from);
		l.reply = (accept ? "Accepted" : "Refused") + " by " + player.getName().getString() + ".";
		apply(server, l, accept);

		if (from != null) {
			ServerNations.notifyPlayer(server, from.leader, mine.name + " " + (accept ? "accepted" : "refused") + " your letter (" + l.type.displayName + ").");
		}

		save();
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
	}

	/** What a letter does once it is answered. */
	private static void apply(MinecraftServer server, LetterData l, boolean accepted) {
		NationData from = ServerNations.nation(l.from);
		NationData to = ServerNations.nation(l.to);

		if (from == null || to == null) {
			return;
		}

		Relations.Relation r = relation(from.id, to.id);

		switch (l.type) {
			case MESSAGE -> change(from.id, to.id, accepted ? 2 : 0);
			case GIFT -> {
				if (accepted) {
					to.treasury += l.amount;
					change(from.id, to.id, Math.min(30, 2 + l.amount / 2));
				} else {
					from.treasury += l.amount; // sent back
				}
			}
			case TRADE -> {
				if (accepted) {
					r.trade = true;
					change(from.id, to.id, 5);
				}
			}
			case ALLIANCE -> {
				if (accepted) {
					if (!from.allies.contains(to.id)) {
						from.allies.add(to.id);
					}

					if (!to.allies.contains(from.id)) {
						to.allies.add(from.id);
					}

					change(from.id, to.id, 10);
				} else {
					change(from.id, to.id, -2);
				}
			}
			case PEACE -> {
				if (accepted) {
					r.war = false;
					change(from.id, to.id, 5);
					WarsWar.onPeace(server, from, to);
				}
			}
			case TRIBUTE -> {
				if (accepted) {
					long paid = Math.min(l.amount, to.treasury);
					to.treasury -= paid;
					from.treasury += paid;
					l.reply = l.reply + " Paid " + paid + " emeralds.";
					change(from.id, to.id, -20);
				} else {
					change(from.id, to.id, -10);
				}
			}
			case WAR -> startWar(server, from, to, true);
			default -> {
			}
		}
	}

	/**
	 * Two nations go to war. Everyone who likes the victim thinks less of the attacker,
	 * and the victim's allies are called to arms: allies ruled by the game join at once, players are asked.
	 */
	static void startWar(MinecraftServer server, NationData from, NationData to, boolean callAllies) {
		Relations.Relation r = relation(from.id, to.id);

		if (r.war) {
			return;
		}

		r.war = true;
		r.trade = false;
		from.allies.remove(to.id);
		to.allies.remove(from.id);
		change(from.id, to.id, -40);
		WarsWar.onWarStarted(server, from, to);

		for (NationData other : new ArrayList<>(ServerNations.allNations())) {
			if (other != from && other != to && opinion(other, to) >= 25) {
				change(other.id, from.id, -10);
			}
		}

		if (!callAllies) {
			return;
		}

		for (UUID allyId : new ArrayList<>(to.allies)) {
			NationData ally = ServerNations.nation(allyId);

			if (ally == null || ally == from || atWar(ally.id, from.id)) {
				continue;
			}

			if (ally.aiRuled()) {
				if (opinion(ally, to) >= 30) {
					startWar(server, ally, from, false);
					news(server, "\u2694 " + ally.name + " honours its alliance with " + to.name + " and declares war on " + from.name + "!");
				} else {
					change(to.id, ally.id, -15); // abandoned by an ally
				}
			} else {
				ServerNations.notifyPlayer(server, ally.leader, "Your ally " + to.name + " was attacked by " + from.name
						+ "! Declare war in the Letters tab to help them.");
			}
		}
	}

	/** Ends a war (peace letter, or a nation fell). */
	static void endWar(UUID a, UUID b) {
		Relations.Relation r = RELATIONS.get(Relations.key(a, b));

		if (r != null && r.war) {
			r.war = false;
			r.modifier = Relations.clamp(r.modifier + 5);
		}
	}

	/** Every nation this one is at war with. */
	static List<NationData> enemiesOf(NationData n) {
		List<NationData> list = new ArrayList<>();

		for (NationData other : ServerNations.allNations()) {
			if (other != n && atWar(n.id, other.id)) {
				list.add(other);
			}
		}

		return list;
	}

	/** Changes how much nation a likes nation b (and the other way round: relations are shared). */
	static void changeOpinion(UUID a, UUID b, int amount) {
		change(a, b, amount);
	}

	/** A message in every player's chat, kept in the world's chronicle (War tab). */
	static void news(MinecraftServer server, String text) {
		today = server.overworld().getGameTime() / WarsEconomy.DAY_TICKS;
		NEWS.add("Day " + today + ": " + text);

		while (NEWS.size() > MAX_NEWS) {
			NEWS.remove(0);
		}

		for (ServerPlayer p : PlayerLookup.all(server)) {
			p.sendSystemMessage(net.minecraft.network.chat.Component.literal(text).withColor(0xFFD27A));
		}

		save();
		broadcast(server);
	}

	static boolean trading(UUID a, UUID b) {
		Relations.Relation r = RELATIONS.get(Relations.key(a, b));
		return r != null && r.trade;
	}

	/**
	 * A nation ruled by the game writes a letter (stage 6). Nations ruled by the game answer at once,
	 * players find it in their Letters tab. A declaration of war needs no answer.
	 */
	static void aiLetter(MinecraftServer server, NationData from, NationData to, LetterData.Type type, int amount, String text) {
		LetterData l = new LetterData(UUID.randomUUID());
		l.from = from.id;
		l.to = to.id;
		l.sender = from.leaderName() + ", " + from.ideology.leaderTitle;
		l.type = type;
		l.amount = Math.max(0, amount);
		l.text = text;
		l.day = server.overworld().getGameTime() / WarsEconomy.DAY_TICKS;

		if (type == LetterData.Type.GIFT) {
			if (from.treasury < l.amount) {
				return;
			}

			from.treasury -= l.amount;
		}

		LETTERS.add(l);

		if (type == LetterData.Type.WAR) {
			l.status = LetterData.Status.DONE;
			apply(server, l, true);
			l.reply = warReply(to);
		} else if (to.aiRuled()) {
			aiAnswer(server, l, from, to);
		} else {
			for (NationData.Member m : to.members) {
				if (canWrite(to, m.id())) {
					ServerNations.notifyPlayer(server, m.id(), "\u2709 A letter from " + from.name + ": " + type.displayName + ". Open the Letters tab.");
				}
			}
		}

		trim();
		save();
	}

	static void addLetter(LetterData l) {
		LETTERS.add(l);
		trim();
	}

	static void saveNow() {
		save();
	}

	/** Is a letter of this type from a to b still waiting for an answer? */
	static boolean pendingLetter(UUID from, UUID to, LetterData.Type type) {
		for (LetterData l : LETTERS) {
			if (l.status == LetterData.Status.PENDING && l.type == type && l.from.equals(from) && l.to.equals(to)) {
				return true;
			}
		}

		return false;
	}

	/** Nations ruled by the game answer straight away. */
	private static void aiAnswer(MinecraftServer server, LetterData l, NationData from, NationData to) {
		int o = opinion(to, from);
		Random random = new Random();
		boolean stronger = strength(from) > strength(to);
		boolean accept = switch (l.type) {
			case MESSAGE, GIFT -> true;
			case TRADE -> o >= -10;
			case ALLIANCE -> o >= 40;
			case PEACE -> WarsAI.acceptsPeace(server, to, from, o) || stronger;
			case TRIBUTE -> strength(from) > strength(to) * 2 && o > -70;
			default -> true;
		};

		l.status = accept ? LetterData.Status.ACCEPTED : LetterData.Status.REFUSED;
		apply(server, l, accept);
		l.reply = aiReply(to, l.type, accept, o) + (l.reply.isEmpty() ? "" : " " + l.reply);
		NationData sender = from;

		for (NationData.Member m : sender.members) {
			if (canWrite(sender, m.id())) {
				ServerNations.notifyPlayer(server, m.id(), to.name + " answered your letter: " + (accept ? "accepted" : "refused") + ".");
			}
		}
	}

	/** What a ruler writes back, in their own style. */
	private static String aiReply(NationData n, LetterData.Type type, boolean accept, int opinion) {
		String who = n.leaderName() + ", " + n.ideology.leaderTitle + " of " + n.name;

		String body = switch (n.faction) {
			case ILLAGER -> accept ? "Hmph. Very well. Do not make us regret it." : "The Illagers bow to no one. Go away, or be raided.";
			case PIGLIN -> accept ? "Gold? Gold is good. Deal." : "Grrr... no gold, no deal. Leave!";
			case UNDEAD -> accept ? "...the dead... agree... for now..." : "...join us... or perish...";
			default -> switch (type) {
				case MESSAGE -> opinion >= 25 ? "Thank you for your kind words, friend." : (opinion > -25 ? "We have read your letter." : "Your words mean little to us.");
				case GIFT -> "Your generous gift is most welcome. Our people will remember it.";
				case TRADE -> accept ? "Gladly! May our merchants grow rich together." : "We do not trust you enough to trade.";
				case ALLIANCE -> accept ? "It is an honour. From today we stand together." : "We are not close enough for an alliance. Not yet.";
				case PEACE -> accept ? "Enough blood has been spilled. Peace it is." : "No peace while our grievances remain!";
				case TRIBUTE -> accept ? "We... have no choice. Take your emeralds, and leave us be." : "You will get nothing from us but steel!";
				default -> "So be it.";
			};
		};

		return "\"" + body + "\" - " + who;
	}

	private static String warReply(NationData to) {
		return to.aiRuled() ? "\"You will regret this.\" - " + to.leaderName() + ", " + to.ideology.leaderTitle + " of " + to.name : "";
	}

	private static void announceWar(MinecraftServer server, NationData from, NationData to) {
		for (ServerPlayer p : PlayerLookup.all(server)) {
			ServerNations.status(p, "⚔ " + from.name + " declared war on " + to.name + "!", false);
		}
	}

	private static void trim() {
		while (LETTERS.size() > MAX_LETTERS) {
			LETTERS.remove(0);
		}
	}

	// ---------------------------------------------------------------- daily

	static void runDay(MinecraftServer server) {
		for (Map.Entry<String, Relations.Relation> e : RELATIONS.entrySet()) {
			Relations.Relation r = e.getValue();
			UUID[] pair = PAIRS.get(e.getKey());

			if (r.trade && pair != null) {
				NationData a = ServerNations.nation(pair[0]);
				NationData b = ServerNations.nation(pair[1]);

				if (a != null && b != null) {
					a.treasury += TRADE_INCOME;
					b.treasury += TRADE_INCOME;
				}
			}

			// old grudges and favours slowly fade
			if (!r.war && r.modifier != 0) {
				r.modifier -= Integer.signum(r.modifier);
			}
		}

		save();
	}

	// ---------------------------------------------------------------- sync and saving

	static void broadcast(MinecraftServer server) {
		for (ServerPlayer p : PlayerLookup.all(server)) {
			sendTo(p);
		}
	}

	private static void sendTo(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, DiplomacySyncPayload.TYPE)) {
			return;
		}

		List<DiplomacySyncPayload.Entry> relations = new ArrayList<>();

		for (Map.Entry<String, Relations.Relation> e : RELATIONS.entrySet()) {
			UUID[] pair = PAIRS.get(e.getKey());
			Relations.Relation r = e.getValue();

			if (pair != null && (r.modifier != 0 || r.war || r.trade)) {
				relations.add(new DiplomacySyncPayload.Entry(pair[0], pair[1], r.modifier, r.war, r.trade));
			}
		}

		NationData mine = ServerNations.nationOf(player.getUUID());
		List<LetterData> letters = new ArrayList<>();

		for (int i = LETTERS.size() - 1; i >= 0 && letters.size() < 80; i--) {
			LetterData l = LETTERS.get(i);
			boolean ours = mine != null && (mine.id.equals(l.to) || (!l.personal && mine.id.equals(l.from)));

			if (ours || (l.personal && player.getUUID().equals(l.from))) {
				letters.add(l);
			}
		}

		ServerPlayNetworking.send(player, new DiplomacySyncPayload(relations, letters, new ArrayList<>(NEWS)));
	}

	private static void load(MinecraftServer server) {
		RELATIONS.clear();
		PAIRS.clear();
		LETTERS.clear();
		NEWS.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("mapnationswars_diplomacy.json");

		if (!Files.exists(file)) {
			return;
		}

		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

			for (JsonElement el : root.getAsJsonArray("relations")) {
				JsonObject o = el.getAsJsonObject();
				UUID a = UUID.fromString(o.get("a").getAsString());
				UUID b = UUID.fromString(o.get("b").getAsString());
				Relations.Relation r = relation(a, b);
				r.modifier = o.get("modifier").getAsInt();
				r.war = o.get("war").getAsBoolean();
				r.trade = o.get("trade").getAsBoolean();
			}

			for (JsonElement el : root.getAsJsonArray("letters")) {
				JsonObject o = el.getAsJsonObject();
				LetterData l = new LetterData(UUID.fromString(o.get("id").getAsString()));
				l.from = UUID.fromString(o.get("from").getAsString());
				l.to = UUID.fromString(o.get("to").getAsString());
				l.sender = o.get("sender").getAsString();
				l.type = LetterData.Type.byName(o.get("type").getAsString());
				l.amount = o.get("amount").getAsInt();
				l.text = o.get("text").getAsString();
				l.day = o.get("day").getAsLong();
				l.status = LetterData.Status.valueOf(o.get("status").getAsString());
				l.reply = o.get("reply").getAsString();
				l.personal = o.has("personal") && o.get("personal").getAsBoolean();
				LETTERS.add(l);
			}

			if (root.has("news")) {
				for (JsonElement el : root.getAsJsonArray("news")) {
					NEWS.add(el.getAsString());
				}
			}
		} catch (Exception e) {
			MapNationsMod.LOGGER.error("Could not read {}", file, e);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}

		JsonArray relations = new JsonArray();

		for (Map.Entry<String, Relations.Relation> e : RELATIONS.entrySet()) {
			UUID[] pair = PAIRS.get(e.getKey());
			Relations.Relation r = e.getValue();

			if (pair == null || (r.modifier == 0 && !r.war && !r.trade)) {
				continue;
			}

			JsonObject o = new JsonObject();
			o.addProperty("a", pair[0].toString());
			o.addProperty("b", pair[1].toString());
			o.addProperty("modifier", r.modifier);
			o.addProperty("war", r.war);
			o.addProperty("trade", r.trade);
			relations.add(o);
		}

		JsonArray letters = new JsonArray();

		for (LetterData l : LETTERS) {
			JsonObject o = new JsonObject();
			o.addProperty("id", l.id.toString());
			o.addProperty("from", l.from.toString());
			o.addProperty("to", l.to.toString());
			o.addProperty("sender", l.sender);
			o.addProperty("type", l.type.name());
			o.addProperty("amount", l.amount);
			o.addProperty("text", l.text);
			o.addProperty("day", l.day);
			o.addProperty("status", l.status.name());
			o.addProperty("reply", l.reply);
			o.addProperty("personal", l.personal);
			letters.add(o);
		}

		JsonObject root = new JsonObject();
		root.add("relations", relations);
		root.add("letters", letters);
		JsonArray news = new JsonArray();

		for (String line : NEWS) {
			news.add(line);
		}

		root.add("news", news);

		try {
			Path tmp = file.resolveSibling("mapnationswars_diplomacy.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			MapNationsMod.LOGGER.error("Could not save {}", file, e);
		}
	}
}
