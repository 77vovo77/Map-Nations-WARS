package com.mapnationswars;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import com.mapnationswars.nation.Faction;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.nation.Ranks;
import com.mapnationswars.network.CourtPayload;

/**
 * Map Nations WARS 2.0: real kings.
 * The ruler of every nation ruled by the game stands at its capital: a villager king in purple robes, an evoker lord,
 * a piglin warlord, a wither-skeleton lich. Talk to them (right-click) to open the royal court: ask to join, ask for
 * promotion or a duty, bring gifts. Killing a king throws the nation into chaos - and makes its people hate you,
 * unless you are at war with it.
 */
public final class WarsKings {
	private static final String KING_PREFIX = "♛ ";
	private static final double SHOW = 96;
	private static final double HIDE = 140;

	/** nation -> its king's entity */
	private static final Map<UUID, UUID> KINGS = new HashMap<>();
	/** nation -> game time when a new king may appear (after one died) */
	private static final Map<UUID, Long> MOURNING = new HashMap<>();
	private static final Random RANDOM = new Random();
	private static int ticks = 0;

	private WarsKings() {
	}

	static void init() {
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (UUID nation : new java.util.ArrayList<>(KINGS.keySet())) {
				remove(server, nation);
			}

			MOURNING.clear();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 40 == 0 && WarsWorld.isGenerated()) {
				tick(server);
			}
		});

		// talking to a king opens the court; the war's soldiers can't be traded with
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide()) {
				String name = entity.hasCustomName() && entity.getCustomName() != null ? entity.getCustomName().getString() : "";
				return name.startsWith(KING_PREFIX) || name.startsWith(WarsWar.soldierPrefix()) ? InteractionResult.SUCCESS : InteractionResult.PASS;
			}

			if (!(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}

			if (WarsTroops.isTroop(entity.getUUID())) {
				ServerNations.status(sp, "A soldier: \"Move along.\"", true);
				return InteractionResult.SUCCESS;
			}

			UUID nation = nationOfKing(entity.getUUID());

			if (nation == null) {
				return InteractionResult.PASS;
			}

			NationData n = ServerNations.nation(nation);

			if (n != null && ServerPlayNetworking.canSend(sp, CourtPayload.TYPE)) {
				ServerPlayNetworking.send(sp, new CourtPayload(n.id, greeting(sp, n)));
			}

			return InteractionResult.SUCCESS;
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			UUID nation = nationOfKing(entity.getUUID());

			if (nation == null) {
				return;
			}

			KINGS.remove(nation);
			WarsWar.untrack(entity.getUUID());
			MinecraftServer server = entity.level().getServer();
			NationData n = ServerNations.nation(nation);

			if (server == null || n == null) {
				return;
			}

			kingDied(server, n, source.getEntity() instanceof ServerPlayer p ? p : null);
		});
	}

	private static UUID nationOfKing(UUID entity) {
		for (Map.Entry<UUID, UUID> e : KINGS.entrySet()) {
			if (e.getValue().equals(entity)) {
				return e.getKey();
			}
		}

		return null;
	}

	private static String greeting(ServerPlayer player, NationData n) {
		int standing = WarsPeople.standing(player.getUUID(), n.id);
		boolean member = n.isMember(player.getUUID());
		String who = n.ideology.leaderTitle + " " + n.rulerName;

		if (WarsPeople.hostileTo(player, n)) {
			return who + ": \"You dare stand before me? Guards!\"";
		}

		if (member) {
			int rank = n.rankOf(player.getUUID());
			return who + ": \"Ah, my loyal " + Ranks.name(rank).toLowerCase() + ". What do you need?\"";
		}

		return switch (n.faction) {
			case ILLAGER -> who + ": \"" + (standing >= 20 ? "You may speak, outsider." : "Speak quickly, human, before I lose patience.") + "\"";
			case PIGLIN -> who + ": \"" + (standing >= 30 ? "Friend of gold. Speak." : "Gold? You bring gold? No? Grrr.") + "\"";
			case UNDEAD -> who + ": \"...the living... speak...\"";
			default -> who + ": \"" + (standing >= 25 ? "Welcome, friend of " + n.name + "!" : standing <= -20 ? "We remember what you did." : "Welcome, traveller. How may the crown help you?") + "\"";
		};
	}

	// ---------------------------------------------------------------- the king stands at the capital

	private static void tick(MinecraftServer server) {
		long now = server.overworld().getGameTime();

		for (NationData n : new java.util.ArrayList<>(ServerNations.allNations())) {
			ProvinceData capital = capital(n);
			UUID kingId = KINGS.get(n.id);
			ServerLevel level = capital != null ? WarsWorld.levelOf(server, capital.dimension) : null;
			Entity king = level != null && kingId != null ? level.getEntity(kingId) : null;

			if (kingId != null && (king == null || !king.isAlive())) {
				KINGS.remove(n.id);
				kingId = null;
				king = null;
			}

			// only nations ruled by the game have a king in the world (a player leader is their own king)
			boolean wanted = n.aiRuled() && capital != null && !capital.abandoned && level != null && now >= MOURNING.getOrDefault(n.id, 0L);

			if (!wanted || !near(server, capital, HIDE)) {
				if (kingId != null) {
					remove(server, n.id);
				}

				continue;
			}

			if (king == null && near(server, capital, SHOW) && level.getChunkSource().hasChunk(capital.x >> 4, capital.z >> 4)) {
				spawnKing(level, n, capital);
			} else if (king instanceof Mob m && Math.hypot(m.getX() - capital.x, m.getZ() - capital.z) > 12) {
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, capital.x + 2, capital.z + 2);
				m.teleportTo(capital.x + 2.5, y, capital.z + 2.5);
			}

			// the name follows the ruler (a new one after a death or an election)
			if (king != null) {
				king.setCustomName(kingName(n));
			}
		}
	}

	private static ProvinceData capital(NationData n) {
		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation) && p.capital) {
				return p;
			}
		}

		return null;
	}

	private static boolean near(MinecraftServer server, ProvinceData p, double range) {
		for (ServerPlayer pl : PlayerLookup.all(server)) {
			if (pl.level().dimension().identifier().toString().equals(p.dimension) && Math.hypot(pl.getX() - p.x, pl.getZ() - p.z) < range) {
				return true;
			}
		}

		return false;
	}

	private static Component kingName(NationData n) {
		return Component.literal(KING_PREFIX + n.ideology.leaderTitle + " " + n.rulerName + " of " + n.name).withColor(0xFFD54F);
	}

	private static void spawnKing(ServerLevel level, NationData n, ProvinceData capital) {
		int x = capital.x + 2;
		int z = capital.z + 2;
		boolean nether = "minecraft:the_nether".equals(capital.dimension);
		int y = nether ? 64 : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		String type = switch (n.faction) {
			case ILLAGER -> "EVOKER";
			case PIGLIN -> "PIGLIN_BRUTE";
			case UNDEAD -> "WITHER_SKELETON";
			default -> "VILLAGER";
		};
		Mob king = WarsWar.spawnMob(level, type, x, y, z);

		if (king == null) {
			return;
		}

		if (king instanceof Villager v) {
			try {
				v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.CLERIC));
				v.setVillagerDataFinalized(true);
			} catch (Exception ignored) {
				// keeps its looks
			}

			// no villager business (after the profession is set, so nothing rebuilds it)
			v.getBrain().removeAllBehaviors();
			v.getBrain().clearMemories();
		}

		try {
			king.getClass().getMethod("setImmuneToZombification", boolean.class).invoke(king, true);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// not a piglin
		}

		king.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD)); // the sceptre
		king.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET)); // the crown
		king.setDropChance(EquipmentSlot.MAINHAND, 0f);
		king.setDropChance(EquipmentSlot.HEAD, 0f);
		AttributeInstance scale = king.getAttribute(Attributes.SCALE);

		if (scale != null) {
			scale.setBaseValue(1.25);
		}

		AttributeInstance health = king.getAttribute(Attributes.MAX_HEALTH);

		if (health != null) {
			health.setBaseValue(80);
			king.setHealth(80);
		}

		king.setNoAi(true); // the king holds court; he doesn't wander
		king.setPersistenceRequired();
		king.setCustomName(kingName(n));
		king.setCustomNameVisible(true);
		KINGS.put(n.id, king.getUUID());
	}

	private static void remove(MinecraftServer server, UUID nation) {
		UUID id = KINGS.remove(nation);

		if (id == null) {
			return;
		}

		for (ServerLevel level : server.getAllLevels()) {
			Entity e = level.getEntity(id);

			if (e != null) {
				e.discard();
			}
		}

		WarsWar.untrack(id);
	}

	// ---------------------------------------------------------------- regicide

	private static void kingDied(MinecraftServer server, NationData n, ServerPlayer killer) {
		String old = n.ideology.leaderTitle + " " + n.rulerName;
		n.rulerName = n.faction == Faction.VILLAGER || n.faction == Faction.PLAYER ? Names.person(RANDOM) : Names.warlord(RANDOM, ProvinceData.Type.MANSION);
		MOURNING.put(n.id, server.overworld().getGameTime() + 6000); // a new ruler is crowned in 5 minutes

		for (ProvinceData p : WarsWorld.provinces()) {
			if (n.id.equals(p.nation)) {
				p.unrest = Math.min(100, p.unrest + 20);
				p.happiness = Math.max(0, p.happiness - 10);
			}
		}

		if (killer != null) {
			NationData kn = ServerNations.nationOf(killer.getUUID());
			boolean war = kn != null && WarsDiplomacy.atWar(kn.id, n.id);

			if (war) {
				WarsPolitics.addMerit(server, kn, killer.getUUID(), 30);
				WarsDiplomacy.news(server, "♛ " + old + " of " + n.name + " was slain in battle by " + killer.getName().getString()
						+ " of " + kn.name + "! " + n.rulerName + " takes the throne.");
			} else {
				// murder: they will hunt you
				WarsPeople.changeStanding(killer, n.id, -200);
				WarsDiplomacy.news(server, "☠ " + old + " of " + n.name + " was murdered by " + killer.getName().getString()
						+ "! Every guard of " + n.name + " hunts the killer. " + n.rulerName + " takes the throne.");

				if (n.isMember(killer.getUUID())) {
					n.members.removeIf(m -> m.id().equals(killer.getUUID()));
					ServerNations.removeMemberData(n, killer.getUUID());
				}
			}
		} else {
			WarsDiplomacy.news(server, "♛ " + old + " of " + n.name + " has died. " + n.rulerName + " takes the throne.");
		}

		WarsWorld.saveNow();
		ServerNations.saveNow(server);
		ServerNations.broadcast(server);
		WarsWorld.broadcast(server);
	}
}
