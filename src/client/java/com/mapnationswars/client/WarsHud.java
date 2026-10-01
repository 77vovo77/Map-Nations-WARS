package com.mapnationswars.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.PortalSite;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.network.WarSyncPayload;

/**
 * In the world (Map Nations WARS stage 8):
 * - look at a portal and its awakening meter appears under the crosshair;
 * - a line at the top tells you where your nation is fighting nearby (battles and sieges), with the direction.
 */
final class WarsHud {
	private static final double ALERT_RANGE = 700;

	private WarsHud() {
	}

	static void register() {
		HudElementRegistry.addLast(MapNationsMod.id("wars_hud"), WarsHud::extract);
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;

		if (player == null || mc.level == null || mc.gui.screen() != null) {
			return;
		}

		String dim = mc.level.dimension().identifier().toString();
		boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
		drawPortalMeter(g, mc, player, dim, blink);
		drawAlert(g, mc, player, dim, blink);
	}

	/** The portal you are looking at (close, and roughly in front of you). */
	private static PortalSite lookedAt(LocalPlayer player, String dim) {
		double yaw = Math.toRadians(player.getYRot());
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		PortalSite best = null;
		double bestD = 26;

		for (PortalSite s : ClientPortals.all()) {
			if (!ClientPortals.visibleIn(s, dim)) {
				continue;
			}

			double dx = s.xIn(dim) - player.getX();
			double dz = s.zIn(dim) - player.getZ();
			double d = Math.hypot(dx, dz);

			if (d >= bestD) {
				continue;
			}

			double facing = d < 1 ? 1 : (dx * fx + dz * fz) / d;

			if (d < 8 || facing > 0.75) {
				bestD = d;
				best = s;
			}
		}

		return best;
	}

	private static void drawPortalMeter(GuiGraphicsExtractor g, Minecraft mc, LocalPlayer player, String dim, boolean blink) {
		PortalSite s = lookedAt(player, dim);

		if (s == null) {
			return;
		}

		int cx = g.guiWidth() / 2;
		int y = g.guiHeight() / 2 + 14;
		String title = s.name;
		String sub = s.open ? "TORN OPEN!" : "Awakening " + (int) s.activation + "%  ·  " + ClientPortals.state(s);
		int w = Math.max(110, Math.max(mc.font.width(title), mc.font.width(sub)) + 12);
		int glow = ClientPortals.color(s);
		g.fill(cx - w / 2 - 1, y - 1, cx + w / 2 + 1, y + 31, 0xFF000000 | (glow & 0xFFFFFF));
		g.fill(cx - w / 2, y, cx + w / 2, y + 30, 0xE0100816);
		g.centeredText(mc.font, title, cx, y + 3, 0xFFE0B0FF);
		g.centeredText(mc.font, sub, cx, y + 13, s.open && blink ? 0xFFFF6040 : 0xFFDDDDDD);
		int bw = w - 12;
		g.fill(cx - bw / 2, y + 24, cx + bw / 2, y + 27, 0xFF2A1A36);
		g.fill(cx - bw / 2, y + 24, cx - bw / 2 + (int) (bw * Math.min(100, s.activation) / 100), y + 27, glow);
	}

	/** "⚔ Battle 140 blocks NE: A vs B" - the closest fight of your nation. */
	private static void drawAlert(GuiGraphicsExtractor g, Minecraft mc, LocalPlayer player, String dim, boolean blink) {
		NationData mine = ClientNations.myNation();

		if (mine == null) {
			return;
		}

		String text = null;
		double best = ALERT_RANGE;
		double px = player.getX();
		double pz = player.getZ();

		for (WarSyncPayload.Battle b : ClientWar.battles()) {
			if (!b.dimension().equals(dim) || !(mine.id.equals(b.a()) || mine.id.equals(b.b()))) {
				continue;
			}

			double d = Math.hypot(b.x() - px, b.z() - pz);

			if (d < best) {
				best = d;
				NationData enemy = ClientNations.get(mine.id.equals(b.a()) ? b.b() : b.a());
				text = "⚔ Battle " + (int) d + " blocks " + direction(b.x() - px, b.z() - pz) + " against " + (enemy != null ? enemy.name : "the enemy");
			}
		}

		for (WarSyncPayload.Siege s : ClientWar.sieges()) {
			ProvinceData p = ClientMarkers.province(s.province());

			if (p == null || !p.dimension.equals(dim) || !(mine.id.equals(p.nation) || mine.id.equals(s.attacker()))) {
				continue;
			}

			double d = Math.hypot(p.x - px, p.z - pz);

			if (d < best) {
				best = d;
				boolean ours = mine.id.equals(p.nation);
				text = (ours ? "⚠ " + p.name + " is under siege" : "⚔ Our siege of " + p.name) + " (" + (int) s.progress() + "%) - "
						+ (int) d + " blocks " + direction(p.x - px, p.z - pz);
			}
		}

		if (text == null) {
			return;
		}

		int w = mc.font.width(text) + 14;
		int x = g.guiWidth() / 2 - w / 2;
		int y = 22;
		g.fill(x - 1, y - 1, x + w + 1, y + 15, blink ? 0xFFFF4030 : 0xFF802018);
		g.fill(x, y, x + w, y + 14, 0xE0140C0A);
		g.text(mc.font, text, x + 7, y + 3, 0xFFFFD0B0, false);
	}

	private static String direction(double dx, double dz) {
		double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north, 90 = east
		String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
		return names[(int) Math.round(((angle % 360) + 360) % 360 / 45) % 8];
	}
}
