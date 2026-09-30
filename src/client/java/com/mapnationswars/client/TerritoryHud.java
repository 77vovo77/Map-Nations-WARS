package com.mapnationswars.client;

import java.util.UUID;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.NationData;

/**
 * "You entered <nation>" / "You left <nation>" above the hotbar,
 * plus server messages (like "X wants to join your nation") when no map is open.
 */
final class TerritoryHud {
	private static final long SHOW_MS = 3500;
	private static final long FADE_MS = 800;

	private static UUID currentNation = null;
	private static String currentDimension = null;
	private static boolean initialized = false;
	private static boolean inConflict = false;

	private static Component message = null;
	private static ItemStack banner = ItemStack.EMPTY;
	private static int accent = 0xFFFFFF;
	private static long shownAt = 0;

	private static long lastStatusUntil = 0;

	// settlements (city, village, capital...) you walk into
	private static UUID currentPlace = null;
	private static Component placeMessage = null;
	private static com.mapnationswars.nation.MarkerData placeMarker = null;
	private static long placeShownAt = 0;

	private TerritoryHud() {
	}

	static void register() {
		HudElementRegistry.addLast(MapNationsMod.id("territory_message"), TerritoryHud::extract);
	}

	/** Called every client tick: checks if you walked into / out of a nation. */
	static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			initialized = false;
			currentNation = null;
			return;
		}

		String dim = mc.level.dimension().identifier().toString();
		int cx = Mth.floor(mc.player.getX()) >> 4;
		int cz = Mth.floor(mc.player.getZ()) >> 4;
		NationData here = ClientNations.nationAt(dim, cx, cz);
		UUID hereId = here != null ? here.id : null;
		tickPlaces(dim, cx, cz);

		if (!initialized || !dim.equals(currentDimension)) {
			// just joined / changed dimension: remember silently, then announce if inside a nation
			initialized = true;
			currentDimension = dim;
			currentNation = hereId;

			if (here != null) {
				show(entered(here), here);
			}

			return;
		}

		// walking into a conflict zone
		boolean contested = ClientNations.contested(dim, cx, cz);

		if (contested && !inConflict) {
			MutableComponent text = Component.literal("\u26A0 Conflict zone: ").withColor(0xFF5555);
			java.util.List<UUID> sides = ClientNations.claimants(dim, cx, cz);

			for (int i = 0; i < sides.size(); i++) {
				NationData n = ClientNations.get(sides.get(i));

				if (n != null) {
					text = text.append(Component.literal(i == 0 ? "" : " vs ").withColor(0xAAAAAA))
							.append(Component.literal(n.name).withColor(n.color));
				}
			}

			message = text;
			banner = ItemStack.EMPTY;
			accent = 0xFF1A1A;
			shownAt = System.currentTimeMillis();
		}

		inConflict = contested;

		if (hereId == null ? currentNation != null : !hereId.equals(currentNation)) {
			NationData left = ClientNations.get(currentNation);
			currentNation = hereId;

			if (here != null && !contested) {
				show(entered(here), here);
			} else if (here == null && left != null) {
				MutableComponent text = Component.literal("You left ").withColor(0xDDDDDD)
						.append(Component.literal(left.name).withColor(left.color))
						.append(Component.literal(" - Wilderness").withColor(0x9AA0A6));
				show(text, left);
			}
		}

		// server messages while no map/nations screen is open
		long until = ClientNations.statusUntil();

		if (until != lastStatusUntil) {
			lastStatusUntil = until;

			if (!(mc.gui.screen() instanceof MapNationsBaseScreen) && !ClientNations.status().isEmpty()) {
				message = Component.literal(ClientNations.status()).withColor(ClientNations.statusOk() ? 0x9CFF9C : 0xFF8080);
				banner = ItemStack.EMPTY;
				accent = 0xFFFFFF;
				shownAt = System.currentTimeMillis();
			}
		}
	}

	/** "Welcome to Nova Praha" when you walk into a settlement's borders, "You left ..." when you leave. */
	private static void tickPlaces(String dim, int cx, int cz) {
		com.mapnationswars.nation.MarkerData place = ClientMarkers.settlementAt(dim, cx, cz);
		UUID placeId = place != null ? place.id : null;

		if (placeId == null ? currentPlace == null : placeId.equals(currentPlace)) {
			return;
		}

		com.mapnationswars.nation.MarkerData left = null;

		for (com.mapnationswars.nation.MarkerData m : ClientMarkers.all()) {
			if (m.id.equals(currentPlace)) {
				left = m;
			}
		}

		currentPlace = placeId;

		if (place != null) {
			NationData owner = MarkerIcons.owner(place);
			MutableComponent text = Component.literal("Welcome to ").withColor(0xDDDDDD)
					.append(Component.literal(place.title()).withStyle(style -> style.withColor(place.type.color).withBold(true)));

			if (!place.label.isBlank()) {
				text = text.append(Component.literal("  " + place.type.displayName).withColor(0x999999));
			}

			if (owner != null) {
				text = text.append(Component.literal("  of ").withColor(0x999999)).append(Component.literal(owner.name).withColor(owner.color));
			}

			placeMessage = text;
			placeMarker = place;
			placeShownAt = System.currentTimeMillis();
		} else if (left != null) {
			placeMessage = Component.literal("You left ").withColor(0xDDDDDD).append(Component.literal(left.title()).withColor(left.type.color));
			placeMarker = left;
			placeShownAt = System.currentTimeMillis();
		}
	}

	/** Small text in the bottom-left corner: which nation's land you are standing in. */
	private static void drawCorner(GuiGraphicsExtractor graphics, Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}

		String dim = mc.level.dimension().identifier().toString();
		int cx = Mth.floor(mc.player.getX()) >> 4;
		int cz = Mth.floor(mc.player.getZ()) >> 4;
		java.util.List<UUID> sides = ClientNations.claimants(dim, cx, cz);
		MutableComponent text;

		if (sides.isEmpty()) {
			text = Component.literal("Wilderness").withColor(0xB0B0B0);
		} else if (sides.size() > 1) {
			text = Component.literal("\u26A0 Conflict zone").withColor(0xFF6060);
		} else {
			NationData n = ClientNations.get(sides.get(0));
			text = n != null ? Component.literal("\u2691 " + n.name).withColor(n.color) : Component.literal("?");
		}

		com.mapnationswars.nation.MarkerData place = ClientMarkers.settlementAt(dim, cx, cz);

		if (place != null) {
			text = text.append(Component.literal("  \u00B7  " + place.title()).withColor(0xE0E0E0));
		}

		org.joml.Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(4, graphics.guiHeight() - 11);
		pose.scale(0.85f);
		graphics.text(mc.font, text, 0, 0, 0xFFFFFFFF, true);
		pose.popMatrix();
	}

	private static MutableComponent entered(NationData n) {
		return Component.literal("You just entered ").withColor(0xDDDDDD)
				.append(Component.literal(n.name).withStyle(style -> style.withColor(n.color).withBold(true)));
	}

	private static void drawPlaceMessage(GuiGraphicsExtractor graphics, Minecraft mc) {
		if (placeMessage == null || placeMarker == null) {
			return;
		}

		long age = System.currentTimeMillis() - placeShownAt;

		if (age > SHOW_MS) {
			placeMessage = null;
			return;
		}

		float alpha = age > SHOW_MS - FADE_MS ? (SHOW_MS - age) / (float) FADE_MS : 1f;
		int a = Math.max(8, (int) (alpha * 255));
		int textW = mc.font.width(placeMessage.getString());
		int boxW = textW + 30;
		int x = graphics.guiWidth() / 2 - boxW / 2;
		int y = graphics.guiHeight() - 122;

		graphics.fill(x, y, x + boxW, y + 20, ((int) (alpha * 0x90) << 24));
		MarkerIcons.sprite(graphics, placeMarker.type.ordinal(), x + 11, y + 10, 14);
		graphics.text(mc.font, placeMessage, x + 22, y + 6, (a << 24) | 0xFFFFFF, true);
	}

	private static void show(Component text, NationData n) {
		message = text;
		banner = n.banner;
		accent = n.color;
		shownAt = System.currentTimeMillis();
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		drawCorner(graphics, client);
		drawPlaceMessage(graphics, client);

		if (message == null) {
			return;
		}

		long age = System.currentTimeMillis() - shownAt;

		if (age > SHOW_MS) {
			message = null;
			return;
		}

		float alpha = age > SHOW_MS - FADE_MS ? (SHOW_MS - age) / (float) FADE_MS : 1f;
		int a = Math.max(8, (int) (alpha * 255));

		Minecraft mc = Minecraft.getInstance();
		int textW = mc.font.width(message.getString());
		boolean hasBanner = !banner.isEmpty();
		int boxW = textW + 16 + (hasBanner ? 20 : 0);
		int x = graphics.guiWidth() / 2 - boxW / 2;
		int y = graphics.guiHeight() - 96;

		graphics.fill(x, y, x + boxW, y + 22, ((int) (alpha * 0x90) << 24));
		graphics.fill(x, y + 21, x + boxW, y + 22, (a << 24) | accent);

		int textX = x + 8;

		if (hasBanner) {
			graphics.item(banner, x + 5, y + 3);
			textX += 20;
		}

		graphics.text(mc.font, message, textX, y + 7, (a << 24) | 0xFFFFFF, true);
	}
}
