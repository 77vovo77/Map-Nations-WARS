package com.mapnationswars.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.joml.Matrix3x2fStack;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.MarkerData;
import com.mapnationswars.nation.NationData;

/**
 * Small map in a corner of the screen (turn it on in the map's View menu).
 * Shows the land around you, nations' land, settlement borders, other players, the world border and you in the middle.
 * In the bottom-left corner the chat is drawn over it, so it never hides messages.
 */
final class Minimap {
	private static final int[] SIZES = {80, 104, 132};
	/** Screen pixels per block: about 115 / 150 / 190 blocks across. */
	private static final double ZOOM = 0.7;

	private static long lastRequest = 0;

	private Minimap() {
	}

	static void register() {
		// drawn just before the chat, so chat messages appear on top of the minimap instead of behind it
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, MapNationsMod.id("minimap"), Minimap::extract);
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();

		if (!ClientConfig.minimap() || mc.player == null || mc.level == null || mc.gui.screen() instanceof MapNationsBaseScreen) {
			return;
		}

		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		String dim = level.dimension().identifier().toString();

		int mapSize = SIZES[ClientConfig.minimapSize()];
		int corner = ClientConfig.minimapCorner();
		int x1 = corner == 2 ? g.guiWidth() - mapSize - 6 : 6;
		// bottom-left sits above the "which nation am I in" text; top corners leave room for the compass letters
		int y1 = corner == 0 ? g.guiHeight() - 18 - mapSize : 10;
		int x2 = x1 + mapSize;
		int y2 = y1 + mapSize;
		double midX = x1 + mapSize / 2.0;
		double midY = y1 + mapSize / 2.0;
		double px = player.getX();
		double pz = player.getZ();
		double half = mapSize / 2.0 / ZOOM;

		// frame: soft shadow, dark edge, thin light line inside
		g.fill(x1 - 3, y1 - 3, x2 + 4, y2 + 4, 0x50000000);
		g.fill(x1 - 2, y1 - 2, x2 + 2, y2 + 2, 0xFF1A1D21);
		g.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, 0xFF6B6F75);
		g.fill(x1, y1, x2, y2, 0xFF15181C);

		g.enableScissor(x1, y1, x2, y2);
		MapData.beginFrame();
		int[] border = MapData.borderBox(level, px, pz);

		// terrain: first the server-painted land (only inside the world border), then what you explored
		int size = MapRegion.SIZE;
		int minRX = Math.floorDiv(Mth.floor(px - half), size);
		int maxRX = Math.floorDiv(Mth.floor(px + half), size);
		int minRZ = Math.floorDiv(Mth.floor(pz - half), size);
		int maxRZ = Math.floorDiv(Mth.floor(pz + half), size);
		Matrix3x2fStack pose = g.pose();

		for (int layer = 0; layer < 2; layer++) {
			boolean clipped = false;

			if (layer == 0) {
				if (border == null) {
					continue;
				}

				int bl = clamp(midX + (border[0] - px) * ZOOM, x1, x2);
				int bt = clamp(midY + (border[1] - pz) * ZOOM, y1, y2);
				int br = clamp(midX + (border[2] - px) * ZOOM, x1, x2);
				int bb = clamp(midY + (border[3] - pz) * ZOOM, y1, y2);

				if (br <= bl || bb <= bt) {
					continue;
				}

				g.enableScissor(bl, bt, br, bb);
				clipped = true;
			}

			for (int rz = minRZ; rz <= maxRZ; rz++) {
				for (int rx = minRX; rx <= maxRX; rx++) {
					MapRegion region = layer == 0 ? MapData.getPreviewRegion(rx, rz) : MapData.getRegion(rx, rz);

					if (region == null) {
						continue;
					}

					Identifier texture = region.prepareTexture(mc, region.textureDirty && MapData.tryUseUpload());

					if (texture == null) {
						continue;
					}

					pose.pushMatrix();
					pose.translate((float) (midX + (rx * (double) size - px) * ZOOM), (float) (midY + (rz * (double) size - pz) * ZOOM));
					pose.scale((float) ZOOM);
					g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0F, 0.0F, size, size, size, size);
					pose.popMatrix();
				}
			}

			if (clipped) {
				g.disableScissor();
			}
		}

		// nations' land
		int minCX = Math.floorDiv(Mth.floor(px - half), 16);
		int maxCX = Math.floorDiv(Mth.floor(px + half), 16);
		int minCZ = Math.floorDiv(Mth.floor(pz - half), 16);
		int maxCZ = Math.floorDiv(Mth.floor(pz + half), 16);

		for (int cz = minCZ; cz <= maxCZ; cz++) {
			for (int cx = minCX; cx <= maxCX; cx++) {
				List<UUID> claimants = ClientNations.claimants(dim, cx, cz);

				if (claimants.isEmpty()) {
					continue;
				}

				int color;

				if (claimants.size() > 1) {
					color = 0x80FF2020; // conflict zone
				} else {
					NationData n = ClientNations.get(claimants.get(0));

					if (n == null) {
						continue;
					}

					color = 0x80000000 | n.color;
				}

				int[] r = chunkRect(cx, cz, px, pz, midX, midY);
				g.fill(r[0], r[1], r[2], r[3], color);
			}
		}

		// settlement borders (cities, villages, castles...)
		for (MarkerData m : ClientMarkers.all()) {
			if (!m.dimension.equals(dim) || !(m.type.settlement || m.province != null) || m.area.isEmpty()) {
				continue;
			}

			Set<Long> area = new HashSet<>(m.area);

			for (long key : area) {
				int cx = MapNationsMod.keyX(key);
				int cz = MapNationsMod.keyZ(key);

				if (cx < minCX - 1 || cx > maxCX + 1 || cz < minCZ - 1 || cz > maxCZ + 1) {
					continue;
				}

				int[] r = chunkRect(cx, cz, px, pz, midX, midY);
				int line = 0xFFFFD54F;

				if (!area.contains(MapNationsMod.chunkKey(cx, cz - 1))) {
					g.fill(r[0], r[1], r[2], r[1] + 1, line);
				}
				if (!area.contains(MapNationsMod.chunkKey(cx, cz + 1))) {
					g.fill(r[0], r[3] - 1, r[2], r[3], line);
				}
				if (!area.contains(MapNationsMod.chunkKey(cx - 1, cz))) {
					g.fill(r[0], r[1], r[0] + 1, r[3], line);
				}
				if (!area.contains(MapNationsMod.chunkKey(cx + 1, cz))) {
					g.fill(r[2] - 1, r[1], r[2], r[3], line);
				}
			}
		}

		// world border
		if (border != null) {
			int bl = (int) Math.round(midX + (border[0] - px) * ZOOM);
			int bt = (int) Math.round(midY + (border[1] - pz) * ZOOM);
			int br = (int) Math.round(midX + (border[2] - px) * ZOOM);
			int bb = (int) Math.round(midY + (border[3] - pz) * ZOOM);
			int red = 0xFFFF3030;
			g.fill(bl - 1, bt, bl + 1, bb, red);
			g.fill(br - 1, bt, br + 1, bb, red);
			g.fill(bl, bt - 1, br, bt + 1, red);
			g.fill(bl, bb - 1, br, bb + 1, red);
		}

		// settlement icons
		for (MarkerData m : ClientMarkers.all()) {
			if (!m.dimension.equals(dim) || !(m.type.settlement || m.province != null)) {
				continue;
			}

			double sx = midX + (m.x + 0.5 - px) * ZOOM;
			double sy = midY + (m.z + 0.5 - pz) * ZOOM;

			if (sx > x1 - 8 && sx < x2 + 8 && sy > y1 - 8 && sy < y2 + 8) {
				MarkerIcons.draw(g, m, (float) sx, (float) sy, 10);
			}
		}

		// other players (from the server, or the ones close enough to be loaded)
		for (com.mapnationswars.network.PlayersPayload.Entry e : ClientNations.players()) {
			if (e.id().equals(player.getUUID()) || !e.dimension().equals(dim)) {
				continue;
			}

			int sx = (int) Math.round(midX + (e.x() - px) * ZOOM);
			int sy = (int) Math.round(midY + (e.z() - pz) * ZOOM);

			if (sx > x1 - 4 && sx < x2 + 4 && sy > y1 - 4 && sy < y2 + 4) {
				int ring = 0xFF000000 | Faces.nationColor(e.id());
				g.fill(sx - 5, sy - 5, sx + 5, sy + 5, ring);
				Faces.draw(g, e.id(), sx - 4, sy - 4, 8);
			}
		}

		g.disableScissor();

		// you, in the middle
		float angle = (float) Math.toRadians(player.getYRot() + 180.0F);
		int arrow = ArrowTexture.SIZE;
		pose.pushMatrix();
		pose.translate((float) midX, (float) midY);
		pose.rotate(angle);
		pose.scale(11f / arrow);
		g.blit(RenderPipelines.GUI_TEXTURED, ArrowTexture.get(mc), -arrow / 2, -arrow / 2, 0.0F, 0.0F, arrow, arrow, arrow, arrow);
		pose.popMatrix();

		// compass letters on the frame
		int letterBg = 0xE0101317;
		drawLetter(g, mc, "N", (int) midX, y1 - 1, 0xFFFF5A5A, letterBg);
		drawLetter(g, mc, "S", (int) midX, y2 - 7, 0xFFDDDDDD, letterBg);
		drawLetter(g, mc, "W", x1 + 3, (int) midY - 4, 0xFFDDDDDD, letterBg);
		drawLetter(g, mc, "E", x2 - 3, (int) midY - 4, 0xFFDDDDDD, letterBg);

		// gold corners
		int gold = 0xFFD9A93A;
		g.fill(x1 - 2, y1 - 2, x1 + 5, y1 - 1, gold);
		g.fill(x1 - 2, y1 - 2, x1 - 1, y1 + 5, gold);
		g.fill(x2 - 5, y1 - 2, x2 + 2, y1 - 1, gold);
		g.fill(x2 + 1, y1 - 2, x2 + 2, y1 + 5, gold);
		g.fill(x1 - 2, y2 + 1, x1 + 5, y2 + 2, gold);
		g.fill(x1 - 2, y2 - 5, x1 - 1, y2 + 2, gold);
		g.fill(x2 - 5, y2 + 1, x2 + 2, y2 + 2, gold);
		g.fill(x2 + 1, y2 - 5, x2 + 2, y2 + 2, gold);

		// coordinates under (or above) the map
		String coords = Mth.floor(px) + ", " + Mth.floor(player.getY()) + ", " + Mth.floor(pz);
		int cw = mc.font.width(coords);
		int cy = corner == 0 ? y1 - 12 : y2 + 4;
		int cx = (int) midX - cw / 2;
		g.fill(cx - 3, cy - 2, cx + cw + 3, cy + 9, 0xB0000000);
		g.text(mc.font, coords, cx, cy, 0xFFE0E0E0);

		// fill in land you haven't explored (inside the world border) once a second
		long now = System.currentTimeMillis();

		if (border != null && now - lastRequest > 1000) {
			lastRequest = now;
			int pcx = Math.floorDiv(Mth.floor(px), 16);
			int pcz = Math.floorDiv(Mth.floor(pz), 16);
			int bMinCX = Math.max(minCX, Math.floorDiv(border[0], 16));
			int bMinCZ = Math.max(minCZ, Math.floorDiv(border[1], 16));
			int bMaxCX = Math.min(maxCX, Math.floorDiv(border[2] - 1, 16));
			int bMaxCZ = Math.min(maxCZ, Math.floorDiv(border[3] - 1, 16));

			if (bMinCX <= bMaxCX && bMinCZ <= bMaxCZ) {
				MapData.requestPreview(pcx, pcz, bMinCX, bMinCZ, bMaxCX, bMaxCZ, 8);
			}
		}
	}

	private static int[] chunkRect(int cx, int cz, double px, double pz, double midX, double midY) {
		int l = (int) Math.floor(midX + (cx * 16.0 - px) * ZOOM);
		int t = (int) Math.floor(midY + (cz * 16.0 - pz) * ZOOM);
		int r = (int) Math.floor(midX + (cx * 16.0 + 16 - px) * ZOOM);
		int b = (int) Math.floor(midY + (cz * 16.0 + 16 - pz) * ZOOM);
		return new int[] {l, t, Math.max(r, l + 1), Math.max(b, t + 1)};
	}

	private static int clamp(double v, int min, int max) {
		return (int) Math.max(min, Math.min(max, Math.round(v)));
	}

	/** A compass letter on a small dark plate. */
	private static void drawLetter(GuiGraphicsExtractor g, Minecraft mc, String letter, int centerX, int y, int color, int bg) {
		int w = mc.font.width(letter);
		g.fill(centerX - w / 2 - 2, y - 1, centerX + w / 2 + 3, y + 8, bg);
		g.text(mc.font, letter, centerX - w / 2 + 1, y, color);
	}
}
