package com.mapnationswars.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import com.mapnationswars.nation.DivisionData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.nation.Ranks;
import com.mapnationswars.network.ArmyActionPayload;
import com.mapnationswars.network.DiplomacySyncPayload;
import com.mapnationswars.network.WarSyncPayload;

/**
 * The War tab (Map Nations WARS stage 5): your nation's armies on the left,
 * your wars, sieges and battles - or one army and its orders - on the right.
 */
public class WarScreen extends PagedScreen {
	private UUID selected;
	private int seen = -1;

	public WarScreen() {
		super(Tab.WAR);
	}

	private UUID me() {
		return this.minecraft != null && this.minecraft.player != null ? this.minecraft.player.getUUID() : new UUID(0, 0);
	}

	private List<DivisionData> mine() {
		NationData n = ClientNations.myNation();
		return n == null ? List.of() : ClientWar.of(n.id);
	}

	private void send(int action, DivisionData d, String argument, int x, int z) {
		if (ClientPlayNetworking.canSend(ArmyActionPayload.TYPE)) {
			ClientPlayNetworking.send(new ArmyActionPayload(action, d.id.toString(), argument, x, z));
		}
	}

	private int stamp() {
		return ClientWar.version() * 31 + ClientNations.version() * 7 + ClientDiplomacy.version();
	}

	// ---------------------------------------------------------------- widgets

	@Override
	protected void init() {
		this.addTabs();
		this.layoutFrame();
		this.seen = this.stamp();
		DivisionData d = this.selected != null ? ClientWar.get(this.selected) : null;

		if (d == null) {
			this.selected = null;
			this.contentHeight = 400;
			this.clampScroll();
			return;
		}

		int x = this.panelX;
		int y = this.bottom() - 30;
		boolean command = ClientWar.canCommand(d, this.me());
		NationData n = ClientNations.get(d.nation);
		boolean raise = n != null && (n.leader.equals(this.me()) || n.rankOf(this.me()) >= Ranks.MINISTER);
		List<ButtonSpec> buttons = new ArrayList<>();
		buttons.add(new ButtonSpec("Show on map", 84, b -> {
			MapScreen.focus(d.x, d.z);
			WarMap.selected = command ? d.id : null;
			this.minecraft.gui.setScreen(new MapScreen(false));
		}));

		if (command) {
			buttons.add(new ButtonSpec("Hold", 44, b -> this.send(ArmyActionPayload.HALT, d, "", 0, 0)));
			buttons.add(new ButtonSpec("Go home", 60, b -> {
				ProvinceData home = this.nearestOwn(d);

				if (home != null) {
					this.send(ArmyActionPayload.MOVE, d, home.id.toString(), home.x, home.z);
				}
			}));
		}

		if (raise) {
			buttons.add(new ButtonSpec("Disband", 56, b -> {
				this.send(ArmyActionPayload.DISBAND, d, "", 0, 0);
				this.selected = null;
				this.rebuildWidgets();
			}));
		}

		buttons.add(new ButtonSpec("Back", 44, b -> {
			this.selected = null;
			this.rebuildWidgets();
		}));

		int rows = this.flowButtons(buttons, x, 0, false);
		this.flowButtons(buttons, x, y - rows + 24, true);
		this.contentHeight = 0;
	}

	private ProvinceData nearestOwn(DivisionData d) {
		ProvinceData best = null;
		double bestD = Double.MAX_VALUE;

		for (com.mapnationswars.nation.MarkerData m : ClientMarkers.all()) {
			ProvinceData p = m.province;

			if (p != null && d.nation.equals(p.nation) && p.dimension.equals(d.dimension)) {
				double dist = Math.hypot(p.x - d.x, p.z - d.z);

				if (dist < bestD) {
					bestD = dist;
					best = p;
				}
			}
		}

		return best;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (this.stamp() != this.seen) {
			this.rebuildWidgets();
		}

		this.drawFrame(graphics, "Your armies");
		this.drawList(graphics, mouseX, mouseY);
		graphics.enableScissor(this.panelX - 4, this.viewTop(), this.right(), this.viewBottom());
		DivisionData d = this.selected != null ? ClientWar.get(this.selected) : null;

		if (d != null) {
			this.drawDivision(graphics, d);
		} else {
			this.contentHeight = this.drawOverview(graphics);
		}

		graphics.disableScissor();
		this.drawScrollBar(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.nextStratum();
		this.drawStatus(graphics, this.bottom() - 22);
	}

	private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		NationData n = ClientNations.myNation();
		List<DivisionData> list = this.mine();

		if (n == null || list.isEmpty()) {
			int y = this.listY + 8;
			String text = n == null ? "Join a nation to fight for it." : "No armies yet. Raise one at a province of " + n.name + ".";

			for (String line : this.wrap(text, this.listW - 12)) {
				graphics.text(this.font, line, this.listX + 6, y, 0xFFAAAAAA);
				y += 11;
			}

			return;
		}

		int visible = (this.listBottom - this.listY) / ROW_H;
		this.listScroll = Math.max(0, Math.min(this.listScroll, list.size() - visible));
		graphics.enableScissor(this.listX, this.listY, this.listX + this.listW, this.listBottom);

		for (int i = 0; i < list.size() - this.listScroll && i <= visible; i++) {
			DivisionData d = list.get(i + this.listScroll);
			int y = this.listY + i * ROW_H;
			boolean hover = mouseX >= this.listX && mouseX < this.listX + this.listW && mouseY >= y && mouseY < y + ROW_H && mouseY < this.listBottom;
			int stateColor = switch (d.state) {
				case FIGHTING -> 0xFF4030;
				case SIEGING -> 0xFF9030;
				case RETREATING -> 0x888888;
				default -> n.color;
			};
			this.drawListRow(graphics, y, stateColor, d.id.equals(this.selected), hover);
			graphics.text(this.font, this.fit(d.kind.symbol + " " + d.name, this.listW - 14), this.listX + 8, y + 3, 0xFFFFFFFF);
			String sub = d.soldiers() + "/" + d.kind.maxStrength + " · " + d.state.displayName;
			graphics.text(this.font, this.fit(sub, this.listW - 14), this.listX + 8, y + 13, 0xFF000000 | stateColor);
		}

		graphics.disableScissor();
		this.drawListScrollBar(graphics, list.size(), visible);
	}

	private int drawOverview(GuiGraphicsExtractor graphics) {
		NationData mine = ClientNations.myNation();
		int x = this.panelX;
		int top = this.barBottom() + 10 - this.scroll;
		int y = top;
		int w = this.innerW();

		if (mine != null) {
			this.drawHeading(graphics, "Wars of " + mine.name, x, y, 0xFFFF8A65);
			y += 14;
			boolean any = false;

			for (NationData other : ClientNations.all()) {
				if (other.id.equals(mine.id) || !ClientDiplomacy.atWar(mine.id, other.id)) {
					continue;
				}

				any = true;
				int ours = ClientWar.of(mine.id).size();
				int theirs = ClientWar.of(other.id).size();
				graphics.text(this.font, this.fit("⚔ " + other.name, w / 2), x, y, 0xFF000000 | other.color);
				String right = "armies " + ours + " vs " + theirs + "   provinces " + this.provinces(mine.id) + " vs " + this.provinces(other.id);
				graphics.text(this.font, this.fit(right, w / 2), x + w - Math.min(w / 2, this.font.width(right)), y, 0xFFBBBBBB);
				y += 12;
			}

			if (!any) {
				graphics.text(this.font, "At peace. Wars are declared in the Letters tab.", x, y, 0xFF9CFF9C);
				y += 12;
			}

			y += 6;
			this.drawHeading(graphics, "Sieges", x, y, 0xFFFF8A65);
			y += 14;
			boolean sieges = false;

			for (WarSyncPayload.Siege s : ClientWar.sieges()) {
				ProvinceData p = ClientMarkers.province(s.province());

				if (p == null || !(mine.id.equals(p.nation) || mine.id.equals(s.attacker()))) {
					continue;
				}

				sieges = true;
				NationData a = ClientNations.get(s.attacker());
				boolean ours = mine.id.equals(p.nation);
				String text = (ours ? "⚠ " + p.name + " is besieged by " + (a != null ? a.name : "?") : "⚔ We besiege " + p.name)
						+ " - " + (int) s.progress() + "%";
				graphics.text(this.font, this.fit(text, w), x, y, ours ? 0xFFFF6A50 : 0xFFFFC070);
				y += 12;
			}

			if (!sieges) {
				graphics.text(this.font, "None.", x, y, 0xFF888888);
				y += 12;
			}

			y += 6;
		}

		this.drawHeading(graphics, "Battles going on", x, y, 0xFFFF8A65);
		y += 14;

		if (ClientWar.battles().isEmpty()) {
			graphics.text(this.font, "None.", x, y, 0xFF888888);
			y += 12;
		}

		for (WarSyncPayload.Battle b : ClientWar.battles()) {
			NationData a = ClientNations.get(b.a());
			NationData c = ClientNations.get(b.b());
			String text = "⚔ " + (a != null ? a.name : "?") + " vs " + (c != null ? c.name : "?") + "  at " + (int) b.x() + ", " + (int) b.z();
			graphics.text(this.font, this.fit(text, w), x, y, 0xFFFFD0A0);
			y += 12;
		}

		y += 6;
		this.drawHeading(graphics, "Chronicle", x, y, 0xFFFFD060);
		y += 14;
		List<String> news = ClientDiplomacy.news();

		if (news.isEmpty()) {
			graphics.text(this.font, "Nothing has happened yet.", x, y, 0xFF888888);
			y += 12;
		}

		for (int i = news.size() - 1; i >= Math.max(0, news.size() - 14); i--) {
			y += this.drawWrapped(graphics, news.get(i), x, y, w, i == news.size() - 1 ? 0xFFFFE0A0 : 0xFFC8C0B0);
			y += 2;
		}

		y += 6;
		this.drawHeading(graphics, "Wars in the world", x, y, 0xFFFF8A65);
		y += 14;
		List<DiplomacySyncPayload.Entry> wars = ClientDiplomacy.wars();

		if (wars.isEmpty()) {
			graphics.text(this.font, "The world is at peace.", x, y, 0xFF888888);
			y += 12;
		}

		for (DiplomacySyncPayload.Entry e : wars) {
			NationData a = ClientNations.get(e.a());
			NationData b = ClientNations.get(e.b());

			if (a == null || b == null) {
				continue;
			}

			graphics.text(this.font, Component.literal(this.fit(a.name, w / 2 - 12)).withColor(a.color)
					.append(Component.literal(" ⚔ ").withColor(0xFF5050))
					.append(Component.literal(this.fit(b.name, w / 2 - 12)).withColor(b.color)), x, y, 0xFFFFFFFF, false);
			y += 12;
		}

		y += 6;
		this.drawHeading(graphics, "How war works", x, y, 0xFFFFD060);
		y += 14;
		String help = "Raise armies at your provinces: right-click a village on the map (or talk to its mayor). "
				+ "On the map, left-click one of your armies to pick it, then right-click where it should march. "
				+ "Send it to an enemy province to besiege it - when the siege reaches 100% the province is yours. "
				+ "Armies of nations at war fight when they meet. Go there yourself: the enemy's soldiers appear, "
				+ "every one you kill weakens their army, and your side fights harder while you are near. "
				+ "Armies cost upkeep every day and refill when they rest in your land.";
		y += this.drawWrapped(graphics, help, x, y, w, 0xFFBBBBBB);
		return y - top + 10;
	}

	private int provinces(UUID nation) {
		int count = 0;

		for (com.mapnationswars.nation.MarkerData m : ClientMarkers.all()) {
			if (m.province != null && nation.equals(m.province.nation)) {
				count++;
			}
		}

		return count;
	}

	private void drawDivision(GuiGraphicsExtractor graphics, DivisionData d) {
		NationData n = ClientNations.get(d.nation);
		int x = this.panelX;
		int y = this.barBottom() + 10;
		int w = this.innerW();
		int color = n != null ? n.color : 0xFFFFFF;
		graphics.text(this.font, Component.literal(d.kind.symbol + " " + d.name).withStyle(s -> s.withColor(color).withBold(true)), x, y, 0xFFFFFFFF, true);
		y += 13;
		graphics.text(this.font, this.fit((n != null ? n.name : "?") + "  ·  " + d.kind.displayName, w), x, y, 0xFFAAAAAA);
		y += 18;

		y = this.bar(graphics, "Soldiers", d.soldiers() + " / " + d.kind.maxStrength, d.strength / d.kind.maxStrength, ClientWar.strengthColor(d), x, y, w);
		y = this.bar(graphics, "Morale", (int) d.morale + "%", d.morale / 100.0, 0xFFE8D060, x, y, w);
		y += 4;

		String doing = d.state.displayName;
		ProvinceData target = ClientMarkers.province(d.target);

		if (target != null && d.state != DivisionData.State.IDLE) {
			doing += (d.state == DivisionData.State.SIEGING ? " " : " to ") + target.name;
		}

		graphics.text(this.font, doing, x, y, d.state == DivisionData.State.FIGHTING ? 0xFFFF7060 : 0xFFE8D27A);
		y += 12;
		graphics.text(this.font, "At " + (int) d.x + ", " + (int) d.z + (d.state == DivisionData.State.MARCHING
				? "   ~" + (int) (Math.hypot(d.goalX - d.x, d.goalZ - d.z) / d.kind.speed) + " s to go" : ""), x, y, 0xFF9AA0A6);
		y += 16;
		String stats = "Attack " + d.kind.attack + "   Defence " + d.kind.defence + "   Speed " + d.kind.speed + "   Siege " + d.kind.siege
				+ "   Upkeep " + d.kind.upkeep() + "/day";
		y += this.drawWrapped(graphics, stats, x, y, w, 0xFF9AA0A6);

		if (!ClientWar.canCommand(d, this.me())) {
			this.drawWrapped(graphics, "Officers, Ministers and the leader give orders to armies.", x, y + 6, w, 0xFF888888);
		}
	}

	private int bar(GuiGraphicsExtractor graphics, String label, String value, double fraction, int color, int x, int y, int w) {
		graphics.text(this.font, label, x, y, 0xFFDDDDDD);
		int bx = x + 56;
		int bw = Math.max(40, w - 56 - this.font.width(value) - 8);
		graphics.fill(bx, y + 1, bx + bw, y + 8, 0xFF2A2F36);
		graphics.fill(bx, y + 1, bx + (int) (bw * Math.max(0, Math.min(1, fraction))), y + 8, color);
		graphics.text(this.font, value, bx + bw + 6, y, 0xFFDDDDDD);
		return y + 13;
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
		if (super.mouseClicked(click, doubleClick)) {
			return true;
		}

		double mx = click.x();
		double my = click.y();

		if (mx >= this.listX && mx < this.listX + this.listW && my >= this.listY && my < this.listBottom) {
			int index = (int) ((my - this.listY) / ROW_H) + this.listScroll;
			List<DivisionData> list = this.mine();

			if (index >= 0 && index < list.size()) {
				this.selected = list.get(index).id;
				this.scroll = 0;
				this.rebuildWidgets();
			}

			return true;
		}

		return false;
	}

	@Override
	protected int listCount() {
		return this.mine().size();
	}
}
