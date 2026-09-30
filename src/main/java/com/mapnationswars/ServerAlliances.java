package com.mapnationswars;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import com.mapnationswars.nation.AllianceData;
import com.mapnationswars.nation.NationColors;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.network.AllianceActionPayload;

/**
 * Alliances: groups of whole nations. Only nation leaders can create, join or leave one,
 * and their whole nation comes with them. The first nation of an alliance is its head.
 * Saved in the world folder as mapnationswars_alliances.json.
 */
public final class ServerAlliances {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, AllianceData> ALLIANCES = new LinkedHashMap<>();
	private static Path file;

	private ServerAlliances() {
	}

	static void init() {
		// registered after ServerNations, so nations are already loaded when this runs
		ServerLifecycleEvents.SERVER_STARTED.register(ServerAlliances::load);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save();
			ALLIANCES.clear();
			file = null;
			serverRef = null;
		});

		ServerPlayNetworking.registerGlobalReceiver(AllianceActionPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.level().getServer();

			if (server != null) {
				server.execute(() -> handle(server, player, payload));
			}
		});
	}

	static List<AllianceData> all() {
		return new ArrayList<>(ALLIANCES.values());
	}

	/** The alliance a nation belongs to, or null. */
	static AllianceData allianceOf(UUID nation) {
		for (AllianceData a : ALLIANCES.values()) {
			if (a.nations.contains(nation)) {
				return a;
			}
		}

		return null;
	}

	static void onNationDisbanded(UUID nation) {
		for (AllianceData a : new ArrayList<>(ALLIANCES.values())) {
			a.requests.remove(nation);

			if (a.nations.remove(nation) && a.nations.isEmpty()) {
				ALLIANCES.remove(a.id);
			}
		}

		save();
	}

	private static AllianceData byId(String id) {
		try {
			return ALLIANCES.get(UUID.fromString(id));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static UUID parse(String id) {
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String checkNameColor(String name, int color, AllianceData self) {
		if (name.length() < 3) {
			return "The name needs at least 3 letters.";
		}

		if (!NationColors.isValid(color)) {
			return "Please pick a colour.";
		}

		for (AllianceData other : ALLIANCES.values()) {
			if (other == self) {
				continue;
			}

			if (other.name.equalsIgnoreCase(name)) {
				return "An alliance called " + other.name + " already exists.";
			}

			if (other.color == color) {
				return "That colour already belongs to the " + other.name + ".";
			}
		}

		return null;
	}

	private static void tellNationLeader(MinecraftServer server, UUID nationId, String message) {
		NationData n = ServerNations.nation(nationId);

		if (n != null) {
			ServerNations.notifyPlayer(server, n.leader, message);
		}
	}

	// ---------------------------------------------------------------- actions

	private static void handle(MinecraftServer server, ServerPlayer player, AllianceActionPayload a) {
		UUID me = player.getUUID();
		NationData mine = ServerNations.nationOf(me);

		if (mine == null || !mine.leader.equals(me)) {
			ServerNations.status(player, "Only a nation's leader can manage alliances.", false);
			return;
		}

		AllianceData myAlliance = allianceOf(mine.id);
		boolean amHead = myAlliance != null && mine.id.equals(myAlliance.head());

		switch (a.action()) {
			case AllianceActionPayload.CREATE -> {
				if (myAlliance != null) {
					ServerNations.status(player, "Leave the " + myAlliance.name + " before founding a new alliance.", false);
					return;
				}

				String name = ServerNations.cleanName(a.name());
				String problem = checkNameColor(name, a.color(), null);

				if (problem != null) {
					ServerNations.status(player, problem, false);
					return;
				}

				AllianceData al = new AllianceData(UUID.randomUUID());
				al.name = name;
				al.color = a.color();
				al.nations.add(mine.id);

				if (a.bannerSlot() >= 0) {
					al.banner = ServerNations.bannerFromSlot(player, a.bannerSlot());
				}

				for (AllianceData other : ALLIANCES.values()) {
					other.requests.remove(mine.id);
				}

				ALLIANCES.put(al.id, al);
				ServerNations.status(player, "The " + al.name + " was founded!", true);
			}

			case AllianceActionPayload.EDIT -> {
				if (!amHead) {
					ServerNations.status(player, "Only the leader of the head nation can edit the alliance.", false);
					return;
				}

				String name = ServerNations.cleanName(a.name());
				String problem = checkNameColor(name, a.color(), myAlliance);

				if (problem != null) {
					ServerNations.status(player, problem, false);
					return;
				}

				myAlliance.name = name;
				myAlliance.color = a.color();

				if (a.bannerSlot() == -2) {
					myAlliance.banner = ItemStack.EMPTY;
				} else if (a.bannerSlot() >= 0) {
					ItemStack banner = ServerNations.bannerFromSlot(player, a.bannerSlot());

					if (!banner.isEmpty()) {
						myAlliance.banner = banner;
					}
				}

				ServerNations.status(player, "Alliance saved.", true);
			}

			case AllianceActionPayload.REQUEST_JOIN -> {
				AllianceData target = byId(a.target());

				if (target == null) {
					return;
				}

				if (myAlliance != null) {
					ServerNations.status(player, "Your nation is already in the " + myAlliance.name + ".", false);
					return;
				}

				if (!target.requests.contains(mine.id)) {
					target.requests.add(mine.id);
				}

				ServerNations.status(player, "Asked to join the " + target.name + ".", true);
				tellNationLeader(server, target.head(), mine.name + " asks to join the " + target.name + " (Alliances tab).");
			}

			case AllianceActionPayload.CANCEL_REQUEST -> {
				AllianceData target = byId(a.target());

				if (target != null && target.requests.remove(mine.id)) {
					ServerNations.status(player, "Request taken back.", true);
				}
			}

			case AllianceActionPayload.ACCEPT, AllianceActionPayload.DENY -> {
				UUID nationId = parse(a.target());

				if (!amHead || nationId == null || !myAlliance.requests.remove(nationId)) {
					return;
				}

				NationData other = ServerNations.nation(nationId);

				if (other == null) {
					break;
				}

				if (a.action() == AllianceActionPayload.ACCEPT) {
					if (allianceOf(nationId) != null) {
						ServerNations.status(player, other.name + " already joined another alliance.", false);
						break;
					}

					myAlliance.nations.add(nationId);

					for (AllianceData al : ALLIANCES.values()) {
						al.requests.remove(nationId);
					}

					ServerNations.status(player, other.name + " joined the " + myAlliance.name + ".", true);
					tellNationLeader(server, nationId, "Your nation joined the " + myAlliance.name + "!");
				} else {
					ServerNations.status(player, "Request from " + other.name + " denied.", true);
					tellNationLeader(server, nationId, "The " + myAlliance.name + " did not accept your nation.");
				}
			}

			case AllianceActionPayload.KICK -> {
				UUID nationId = parse(a.target());

				if (!amHead || nationId == null || nationId.equals(mine.id) || !myAlliance.nations.remove(nationId)) {
					return;
				}

				NationData other = ServerNations.nation(nationId);
				ServerNations.status(player, (other != null ? other.name : "The nation") + " was removed from the alliance.", true);
				tellNationLeader(server, nationId, "Your nation was removed from the " + myAlliance.name + ".");
			}

			case AllianceActionPayload.MAKE_HEAD -> {
				UUID nationId = parse(a.target());

				if (!amHead || nationId == null || !myAlliance.nations.contains(nationId) || nationId.equals(mine.id)) {
					return;
				}

				myAlliance.nations.remove(nationId);
				myAlliance.nations.add(0, nationId);
				NationData other = ServerNations.nation(nationId);
				ServerNations.status(player, (other != null ? other.name : "That nation") + " now leads the " + myAlliance.name + ".", true);
				tellNationLeader(server, nationId, "Your nation now leads the " + myAlliance.name + ".");
			}

			case AllianceActionPayload.LEAVE -> {
				if (myAlliance == null) {
					return;
				}

				myAlliance.nations.remove(mine.id);

				if (myAlliance.nations.isEmpty()) {
					ALLIANCES.remove(myAlliance.id);
					ServerNations.status(player, "The " + myAlliance.name + " was dissolved.", true);
				} else {
					ServerNations.status(player, "Your nation left the " + myAlliance.name + ".", true);
					tellNationLeader(server, myAlliance.head(), mine.name + " left the " + myAlliance.name + ".");
				}
			}

			default -> {
				return;
			}
		}

		save();
		ServerNations.broadcast(server);
	}

	// ---------------------------------------------------------------- saving / loading

	private static void load(MinecraftServer server) {
		ALLIANCES.clear();
		serverRef = server;
		file = server.getWorldPath(LevelResource.ROOT).resolve("mapnationswars_alliances.json");

		if (!Files.exists(file)) {
			return;
		}

		try {
			var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

			for (JsonElement el : root.getAsJsonArray("alliances")) {
				JsonObject o = el.getAsJsonObject();
				AllianceData a = new AllianceData(UUID.fromString(o.get("id").getAsString()));
				a.name = o.get("name").getAsString();
				a.color = o.get("color").getAsInt();

				for (JsonElement n : o.getAsJsonArray("nations")) {
					UUID id = UUID.fromString(n.getAsString());

					if (ServerNations.nation(id) != null) {
						a.nations.add(id);
					}
				}

				if (o.has("requests")) {
					for (JsonElement n : o.getAsJsonArray("requests")) {
						UUID id = UUID.fromString(n.getAsString());

						if (ServerNations.nation(id) != null) {
							a.requests.add(id);
						}
					}
				}

				if (o.has("banner")) {
					a.banner = ItemStack.CODEC.parse(ops, o.get("banner")).result().orElse(ItemStack.EMPTY);
				}

				if (!a.nations.isEmpty()) {
					ALLIANCES.put(a.id, a);
				}
			}

			MapNationsMod.LOGGER.info("Loaded {} alliances", ALLIANCES.size());
			ServerNations.broadcast(server);
		} catch (Exception e) {
			MapNationsMod.LOGGER.error("Could not read {}", file, e);
		}
	}

	private static MinecraftServer serverRef;

	private static void save() {
		if (file == null) {
			return;
		}

		JsonArray list = new JsonArray();

		for (AllianceData a : ALLIANCES.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", a.id.toString());
			o.addProperty("name", a.name);
			o.addProperty("color", a.color);
			JsonArray nations = new JsonArray();
			a.nations.forEach(id -> nations.add(id.toString()));
			o.add("nations", nations);
			JsonArray requests = new JsonArray();
			a.requests.forEach(id -> requests.add(id.toString()));
			o.add("requests", requests);

			if (!a.banner.isEmpty() && serverRef != null) {
				var ops = serverRef.registryAccess().createSerializationContext(JsonOps.INSTANCE);
				ItemStack.CODEC.encodeStart(ops, a.banner).result().ifPresent(json -> o.add("banner", json));
			}

			list.add(o);
		}

		JsonObject root = new JsonObject();
		root.add("alliances", list);

		try {
			Path tmp = file.resolveSibling("mapnationswars_alliances.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			MapNationsMod.LOGGER.error("Could not save {}", file, e);
		}
	}
}
