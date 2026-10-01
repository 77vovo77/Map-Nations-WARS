package com.mapnationswars.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.joml.Matrix3x2fStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import com.mapnationswars.nation.DivisionData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.network.WarSyncPayload;

/**
 * Draws the war on the map (Map Nations WARS stage 5): army counters with their strength and morale,
 * marching lines, battles and sieges. Also finds the army under the mouse.
 */
final class WarMap {
	static final int W = 22;
	static final int H = 13;

	/** World -> screen. */
	interface Projection {
		double sx(double worldX);

		double sy(double worldZ);
	}

	/** The army the player picked on the map (stays picked when the map is closed and opened again). */
	static UUID selected = null;

	private WarMap() {
	}

	static DivisionData selectedDivision() {
		return selected == null ? null : ClientWar.get(selected);
	}

	private record Placed(DivisionData d, int x, int y) {
	}

	/** Where each counter goes on screen; counters on the same spot are fanned out a little. */
	private static List<Placed> place(Projection pr, String dim) {
		List<Placed> out = new ArrayList<>();

		for (DivisionData d : ClientWar.divisions()) {
			if (!d.dimension.equals(dim)) {
				continue;
			}

			double[] pos = ClientWar.position(d);
			int x = (int) Math.round(pr.sx(pos[0]));
			int y = (int) Math.round(pr.sy(pos[1]));
			int shift = 0;

			for (Placed p : out) {
				if (Math.abs(p.x() - x) < 6 && Math.abs(p.y() - y) < 6) {
					shift++;
				}
			}

			out.add(new Placed(d, x + shift * 5, y + shift * 5));
		}

		return out;
	}

	static DivisionData divisionAt(Projection pr, String dim, double mx, double my) {
		List<Placed> placed = place(pr, dim);

		for (int i = placed.size() - 1; i >= 0; i--) {
			Placed p = placed.get(i);

			if (mx >= p.x() - W / 2 - 1 && mx < p.x() + W / 2 + 1 && my >= p.y() - H / 2 - 1 && my < p.y() + H / 2 + 4) {
				return p.d();
			}
		}

		return null;
	}

	/** Draws everything. Returns the army under the mouse (or null). */
	static DivisionData draw(GuiGraphicsExtractor g, Font font, Projection pr, String dim, int mouseX, int mouseY, boolean overMap, UUID me) {
		long now = System.currentTimeMillis();
		boolean blink = (now / 400) % 2 == 0;
		NationData mine = ClientNations.myNation();

		// marching lines: your own armies always, others only when hovered / picked
		DivisionData sel = selectedDivision();

		for (DivisionData d : ClientWar.divisions()) {
			if (!d.dimension.equals(dim) || (d.state != DivisionData.State.MARCHING && d.state != DivisionData.State.RETREATING)) {
				continue;
			}

			boolean own = mine != null && mine.id.equals(d.nation);

			if (!own && d != sel) {
				continue;
			}

			NationData n = ClientNations.get(d.nation);
			double[] pos = ClientWar.position(d);
			int color = d == sel ? 0xFFFFFFFF : 0xC0000000 | (n != null ? n.color : 0xFFFFFF);
			dottedLine(g, pr.sx(pos[0]), pr.sy(pos[1]), pr.sx(d.goalX), pr.sy(d.goalZ), color);
			int gx = (int) Math.round(pr.sx(d.goalX));
			int gy = (int) Math.round(pr.sy(d.goalZ));
			g.fill(gx - 3, gy - 1, gx + 4, gy + 2, color);
			g.fill(gx - 1, gy - 3, gx + 2, gy + 4, color);
		}

		// sieges: a bar under the province
		for (WarSyncPayload.Siege s : ClientWar.sieges()) {
			ProvinceData p = ClientMarkers.province(s.province());

			if (p == null || !p.dimension.equals(dim)) {
				continue;
			}

			int x = (int) Math.round(pr.sx(p.x));
			int y = (int) Math.round(pr.sy(p.z)) + 12;
			NationData a = ClientNations.get(s.attacker());
			g.fill(x - 16, y - 1, x + 16, y + 4, 0xFF000000);
			g.fill(x - 15, y, x + 15, y + 3, 0xFF4A1A1A);
			g.fill(x - 15, y, x - 15 + (int) (30 * Math.min(100, s.progress()) / 100), y + 3, 0xFF000000 | (a != null ? a.color : 0xFF5030));
			String text = "⚔ " + (int) s.progress() + "%";
			g.text(font, text, x - font.width(text) / 2, y + 5, blink ? 0xFFFF6A50 : 0xFFFFC0A0, true);
		}

		// restless provinces (stage 7)
		for (com.mapnationswars.nation.MarkerData m : ClientMarkers.all()) {
			ProvinceData p = m.province;

			if (p == null || p.unrest < 60 || !p.dimension.equals(dim)) {
				continue;
			}

			int x = (int) Math.round(pr.sx(p.x));
			int y = (int) Math.round(pr.sy(p.z)) - 20;
			int color = p.unrest >= 85 ? (blink ? 0xFFFF3020 : 0xFFFFD040) : 0xFFFF9040;
			g.centeredText(font, "\u26A0", x, y, color);
		}

		// battles
		for (WarSyncPayload.Battle b : ClientWar.battles()) {
			if (!b.dimension().equals(dim)) {
				continue;
			}

			int x = (int) Math.round(pr.sx(b.x()));
			int y = (int) Math.round(pr.sy(b.z())) - 14;
			int edge = blink ? 0xFFFF4030 : 0xFFFFD040;
			g.fill(x - 8, y - 8, x + 9, y + 9, edge);
			g.fill(x - 7, y - 7, x + 8, y + 8, 0xFF2A0E0A);
			Matrix3x2fStack pose = g.pose();
			pose.pushMatrix();
			pose.translate(x + 0.5f, y - 5);
			pose.scale(1.4f);
			g.centeredText(font, "⚔", 0, 0, 0xFFFFFFFF);
			pose.popMatrix();
		}

		// counters
		DivisionData hovered = null;
		List<Placed> placed = place(pr, dim);

		for (Placed p : placed) {
			DivisionData d = p.d();
			NationData n = ClientNations.get(d.nation);
			int color = n != null ? n.color : 0xFFFFFF;
			int x0 = p.x() - W / 2;
			int y0 = p.y() - H / 2;
			boolean isSel = d.id.equals(selected);
			boolean hover = overMap && mouseX >= x0 - 1 && mouseX < x0 + W + 1 && mouseY >= y0 - 1 && mouseY < y0 + H + 4;

			if (hover) {
				hovered = d;
			}

			int border = isSel ? 0xFFFFFFFF : switch (d.state) {
				case FIGHTING -> blink ? 0xFFFF3020 : 0xFF801010;
				case SIEGING -> 0xFFFF9030;
				case RETREATING -> 0xFF808080;
				default -> hover ? 0xFFDDDDDD : 0xFF000000;
			};

			g.fill(x0 - 1, y0 - 1, x0 + W + 1, y0 + H + 4, border);
			g.fill(x0, y0, x0 + W, y0 + H, 0xFF000000 | darken(color));
			g.fill(x0, y0, x0 + 4, y0 + H, 0xFF000000 | color);
			g.text(font, d.kind.symbol, x0 + 6, y0 + 3, d.state == DivisionData.State.RETREATING ? 0xFF999999 : 0xFFFFFFFF, false);

			if (mine != null && mine.id.equals(d.nation)) {
				g.fill(x0 + W - 3, y0 + 1, x0 + W - 1, y0 + 3, 0xFFFFD54F); // a little gold dot: yours
			}

			// strength and morale
			int sw = (int) Math.round(W * Math.max(0, Math.min(1, d.strength / d.kind.maxStrength)));
			int mw = (int) Math.round(W * Math.max(0, Math.min(1, d.morale / 100.0)));
			g.fill(x0, y0 + H, x0 + W, y0 + H + 3, 0xFF1A1A1A);
			g.fill(x0, y0 + H, x0 + sw, y0 + H + 2, ClientWar.strengthColor(d));
			g.fill(x0, y0 + H + 2, x0 + mw, y0 + H + 3, 0xFFE8D060);
		}

		return hovered;
	}

	private static int darken(int rgb) {
		int r = (rgb >> 16 & 0xFF) * 2 / 5;
		int gr = (rgb >> 8 & 0xFF) * 2 / 5;
		int b = (rgb & 0xFF) * 2 / 5;
		return r << 16 | gr << 8 | b;
	}

	private static void dottedLine(GuiGraphicsExtractor g, double x1, double y1, double x2, double y2, int color) {
		double len = Math.hypot(x2 - x1, y2 - y1);

		if (len < 1 || len > 6000) {
			return;
		}

		int steps = (int) Math.min(1500, len / 5);

		for (int i = 0; i <= steps; i++) {
			double t = i / (double) Math.max(1, steps);
			int x = (int) Math.round(x1 + (x2 - x1) * t);
			int y = (int) Math.round(y1 + (y2 - y1) * t);
			g.fill(x - 1, y - 1, x + 1, y + 1, color);
		}
	}

	static void tooltip(GuiGraphicsExtractor g, Font font, DivisionData d, int mouseX, int mouseY, int width, int height, UUID me) {
		RichTooltip tip = new RichTooltip();
		NationData n = ClientNations.get(d.nation);
		int color = n != null ? n.color : 0xFFFFFF;
		tip.text(Component.literal(d.kind.symbol + " " + d.name).withStyle(s -> s.withColor(color).withBold(true)));
		tip.text(Component.literal((n != null ? n.name : "?") + "  ·  " + d.kind.displayName).withColor(0xAAAAAA));
		tip.text(Component.literal("Soldiers " + d.soldiers() + " / " + d.kind.maxStrength + "   Morale " + (int) d.morale + "%").withColor(0x9CE0A0));
		String doing = d.state.displayName;
		ProvinceData target = ClientMarkers.province(d.target);

		if (target != null && d.state != DivisionData.State.IDLE) {
			doing += (d.state == DivisionData.State.SIEGING ? " " : " to ") + target.name;
		}

		tip.text(Component.literal(doing).withColor(d.state == DivisionData.State.FIGHTING ? 0xFF7060 : 0xE8D27A));

		if (ClientWar.canCommand(d, me)) {
			tip.text(Component.literal(d.id.equals(selected) ? "Right-click the map: send it there" : "Left-click: pick this army").withColor(0x7CFF7C));
		}

		tip.draw(g, font, mouseX, mouseY, width, height);
	}
}
