package com.mapnationswars.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import com.mapnationswars.nation.LetterData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.Ranks;
import com.mapnationswars.nation.Relations;
import com.mapnationswars.network.LetterActionPayload;

/**
 * The Letters tab (Map Nations WARS stage 4): your nation's letters on the left,
 * a letter / writing a new one / your nation's relations on the right.
 */
public class LettersScreen extends PagedScreen {
	private enum View { RELATIONS, LETTER, WRITE }

	// the letter being written survives rebuilding the screen
	private static UUID draftTo = null;
	private static LetterData.Type draftType = LetterData.Type.MESSAGE;
	private static String draftAmount = "10";
	private static String draftText = "";

	private View view = View.RELATIONS;
	private UUID selected;
	private int seenNations;
	private int seenLetters;

	public LettersScreen() {
		super(Tab.LETTERS);
		this.seenNations = ClientNations.version();
		this.seenLetters = ClientDiplomacy.version();
	}

	/** Opens straight on writing to a nation (from its nation page). */
	public LettersScreen(UUID writeTo) {
		this();

		if (writeTo != null) {
			draftTo = writeTo;
			this.view = View.WRITE;
		}
	}

	private UUID me() {
		return this.minecraft != null && this.minecraft.player != null ? this.minecraft.player.getUUID() : new UUID(0, 0);
	}

	private boolean canWrite() {
		NationData mine = ClientNations.myNation();
		return mine != null && (mine.leader.equals(this.me()) || mine.rankOf(this.me()) >= Ranks.MINISTER);
	}

	private List<NationData> others() {
		NationData mine = ClientNations.myNation();
		List<NationData> list = new ArrayList<>();

		for (NationData n : ClientNations.all()) {
			if (mine == null || !n.id.equals(mine.id)) {
				list.add(n);
			}
		}

		list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
		return list;
	}

	private void send(LetterActionPayload p) {
		if (ClientPlayNetworking.canSend(LetterActionPayload.TYPE)) {
			ClientPlayNetworking.send(p);
		}
	}

	private void show(View v, UUID letter) {
		this.view = v;
		this.selected = letter;
		this.scroll = 0;
		this.rebuildWidgets();
	}

	private LetterData selectedLetter() {
		for (LetterData l : ClientDiplomacy.letters()) {
			if (l.id.equals(this.selected)) {
				return l;
			}
		}

		return null;
	}

	// ---------------------------------------------------------------- widgets

	@Override
	protected void init() {
		this.addTabs();
		this.layoutFrame();
		this.contentHeight = 0;

		if (this.canWrite()) {
			this.addRenderableWidget(Button.builder(Component.literal("✎ Write"), b -> this.show(View.WRITE, null))
					.pos(this.listX + this.listW - 56, this.barBottom() + 4).size(56, 16).build());
		}

		switch (this.view) {
			case LETTER -> this.initLetter();
			case WRITE -> this.initWrite();
			default -> this.contentHeight = 14 + ClientNations.all().size() * 12;
		}

		this.clampScroll();
	}

	private void initLetter() {
		LetterData l = this.selectedLetter();
		NationData mine = ClientNations.myNation();
		this.contentHeight = 200;

		if (l != null && mine != null && l.status == LetterData.Status.PENDING && mine.id.equals(l.to) && this.canWrite()) {
			int y = this.bottom() - 30;
			this.addRenderableWidget(Button.builder(Component.literal("✔ Accept").withColor(0x7CFF7C),
					b -> this.send(new LetterActionPayload(LetterActionPayload.ACCEPT, l.id.toString(), "", 0, "")))
					.pos(this.panelX, y).size(90, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("✖ Refuse").withColor(0xFF7C7C),
					b -> this.send(new LetterActionPayload(LetterActionPayload.REFUSE, l.id.toString(), "", 0, "")))
					.pos(this.panelX + 94, y).size(90, 20).build());
		}
	}

	private int writeTop() {
		return this.barBottom() + 10 - this.scroll;
	}

	private void initWrite() {
		List<NationData> others = this.others();

		if (draftTo == null && !others.isEmpty()) {
			draftTo = others.get(0).id;
		}

		int x = this.panelX;
		int y = this.writeTop() + 14;

		// recipient: arrows to go through the nations
		this.addScrolled(Button.builder(Component.literal("◀"), b -> this.cycleTo(-1)).pos(x, y).size(20, 20).build());
		this.addScrolled(Button.builder(Component.literal("▶"), b -> this.cycleTo(1)).pos(x + this.innerW() - 20, y).size(20, 20).build());
		y += 34;

		// kind of letter
		List<ButtonSpec> types = new ArrayList<>();

		for (LetterData.Type t : LetterData.Type.values()) {
			String label = (t == draftType ? "● " : "") + t.displayName;
			types.add(new ButtonSpec(label, this.font.width(label) + 14, b -> {
				draftType = t;
				this.rebuildWidgets();
			}));
		}

		y += this.flowButtons(types, x, y, true) + 16;

		if (draftType == LetterData.Type.GIFT || draftType == LetterData.Type.TRIBUTE) {
			EditBox amount = new EditBox(this.font, x + 70, y - 4, 60, 18, null, Component.literal("Emeralds"));
			amount.setMaxLength(6);
			amount.setValue(draftAmount);
			amount.setResponder(text -> draftAmount = text.replaceAll("[^0-9]", ""));
			amount.visible = amount.getY() >= this.viewTop() && amount.getY() + 18 <= this.viewBottom();
			this.addRenderableWidget(amount);
			y += 22;
		}

		y += 12;
		EditBox text = new EditBox(this.font, x, y, this.innerW(), 18, null, Component.literal("Letter"));
		text.setMaxLength(240);
		text.setValue(draftText);
		text.setResponder(t -> draftText = t);
		text.visible = text.getY() >= this.viewTop() && text.getY() + 18 <= this.viewBottom();
		this.addRenderableWidget(text);
		y += 30;

		this.addScrolled(Button.builder(Component.literal(draftType == LetterData.Type.WAR ? "⚔ Declare war" : "✉ Send letter"), b -> {
			int amount = 0;

			try {
				amount = draftAmount.isEmpty() ? 0 : Integer.parseInt(draftAmount);
			} catch (NumberFormatException ignored) {
			}

			if (draftTo != null) {
				this.send(new LetterActionPayload(LetterActionPayload.SEND, draftTo.toString(), draftType.name(), amount, draftText));
				draftText = "";
				this.show(View.RELATIONS, null);
			}
		}).pos(x, y).size(120, 20).build());
		this.addScrolled(Button.builder(Component.literal("Cancel"), b -> this.show(View.RELATIONS, null)).pos(x + 124, y).size(70, 20).build());
		this.contentHeight = y + 28 - this.writeTop();
	}

	private void cycleTo(int dir) {
		List<NationData> others = this.others();

		if (others.isEmpty()) {
			return;
		}

		int i = 0;

		for (int k = 0; k < others.size(); k++) {
			if (others.get(k).id.equals(draftTo)) {
				i = k;
			}
		}

		draftTo = others.get(Math.floorMod(i + dir, others.size())).id;
		this.rebuildWidgets();
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (ClientNations.version() != this.seenNations || ClientDiplomacy.version() != this.seenLetters) {
			this.seenNations = ClientNations.version();
			this.seenLetters = ClientDiplomacy.version();
			this.rebuildWidgets();
		}

		this.drawFrame(graphics, "Letters");
		this.drawLetterList(graphics, mouseX, mouseY);
		graphics.enableScissor(this.panelX - 4, this.viewTop(), this.right(), this.viewBottom());

		switch (this.view) {
			case LETTER -> this.drawLetter(graphics);
			case WRITE -> this.drawWrite(graphics);
			default -> this.drawRelations(graphics);
		}

		graphics.disableScissor();
		this.drawScrollBar(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.nextStratum();
		this.drawStatus(graphics, this.bottom() - 22);
	}

	private static int typeColor(LetterData.Type t) {
		return switch (t) {
			case WAR, TRIBUTE -> 0xFF5050;
			case PEACE, ALLIANCE -> 0x7CFF7C;
			case TRADE, GIFT -> 0xFFD54F;
			default -> 0xBBBBBB;
		};
	}

	private void drawLetterList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		NationData mine = ClientNations.myNation();
		List<LetterData> letters = ClientDiplomacy.letters();

		if (mine == null) {
			int y = this.listY + 8;

			for (String line : this.wrap("Join a nation to see its letters.", this.listW - 12)) {
				graphics.text(this.font, line, this.listX + 6, y, 0xFFAAAAAA);
				y += 11;
			}

			return;
		}

		if (letters.isEmpty()) {
			graphics.text(this.font, "No letters yet.", this.listX + 6, this.listY + 8, 0xFFAAAAAA);
			return;
		}

		int visible = (this.listBottom - this.listY) / ROW_H;
		this.listScroll = Math.max(0, Math.min(this.listScroll, letters.size() - visible));
		graphics.enableScissor(this.listX, this.listY, this.listX + this.listW, this.listBottom);

		for (int i = 0; i < letters.size() - this.listScroll && i <= visible; i++) {
			LetterData l = letters.get(i + this.listScroll);
			int y = this.listY + i * ROW_H;
			boolean incoming = mine.id.equals(l.to);
			NationData other = ClientNations.get(incoming ? l.from : l.to);
			boolean hover = mouseX >= this.listX && mouseX < this.listX + this.listW && mouseY >= y && mouseY < y + ROW_H && mouseY < this.listBottom;
			this.drawListRow(graphics, y, typeColor(l.type), l.id.equals(this.selected) && this.view == View.LETTER, hover);
			String who = (incoming ? "From " : "To ") + (other != null ? other.name : "?");
			graphics.text(this.font, this.fit(who, this.listW - 14), this.listX + 8, y + 3, other != null ? 0xFF000000 | other.color : 0xFFFFFFFF);
			String status = switch (l.status) {
				case PENDING -> incoming ? "needs answer" : "waiting";
				case ACCEPTED -> "accepted";
				case REFUSED -> "refused";
				case DONE -> "";
			};
			int sc = l.status == LetterData.Status.PENDING && incoming ? 0xFFFFD54F : 0xFF888888;
			graphics.text(this.font, this.fit(l.type.displayName + (status.isEmpty() ? "" : " · " + status), this.listW - 14), this.listX + 8, y + 13, sc);
		}

		graphics.disableScissor();
		this.drawListScrollBar(graphics, letters.size(), visible);
	}

	private void drawRelations(GuiGraphicsExtractor graphics) {
		NationData mine = ClientNations.myNation();
		int x = this.panelX;
		int y = this.barBottom() + 10 - this.scroll;

		if (mine == null) {
			for (String line : this.wrap("You are not in a nation. Nations write letters to each other: gifts, trade, alliances, peace, demands and wars. Only leaders and Ministers can write.", this.innerW())) {
				graphics.text(this.font, line, x, y, 0xFFBBBBBB);
				y += 11;
			}

			return;
		}

		this.drawHeading(graphics, "How the world sees " + mine.name, x, y, 0xFFFFD060);
		y += 14;
		List<NationData> others = this.others();
		others.sort((a, b) -> Integer.compare(ClientDiplomacy.opinion(b, mine), ClientDiplomacy.opinion(a, mine)));

		for (NationData n : others) {
			int o = ClientDiplomacy.opinion(n, mine);
			StringBuilder tags = new StringBuilder();

			if (ClientDiplomacy.atWar(n.id, mine.id)) {
				tags.append(" ⚔ WAR");
			}

			if (mine.allies.contains(n.id)) {
				tags.append(" ✦ ally");
			}

			if (ClientDiplomacy.trading(n.id, mine.id)) {
				tags.append(" ◆ trade");
			}

			String left = this.fit(n.name, this.innerW() / 2);
			graphics.text(this.font, left, x, y, 0xFF000000 | n.color);
			String right = (o > 0 ? "+" : "") + o + " " + Relations.label(o) + tags;
			graphics.text(this.font, right, x + this.innerW() - this.font.width(right), y, 0xFF000000 | (tags.indexOf("WAR") >= 0 ? 0xFF6060 : Relations.color(o)));
			y += 12;
		}
	}

	private void drawLetter(GuiGraphicsExtractor graphics) {
		LetterData l = this.selectedLetter();

		if (l == null) {
			return;
		}

		NationData from = ClientNations.get(l.from);
		NationData to = ClientNations.get(l.to);
		int x = this.panelX;
		int y = this.barBottom() + 10 - this.scroll;
		int w = this.innerW();

		// parchment
		graphics.fill(x, y, x + w, y + 170, 0xFFE9DDBB);
		graphics.fill(x, y, x + w, y + 2, 0xFFB89B5E);
		int ty = y + 8;
		graphics.text(this.font, Component.literal(l.type.displayName).withStyle(s -> s.withBold(true).withColor(typeColor(l.type) == 0xBBBBBB ? 0x3A2E1A : darker(typeColor(l.type)))), x + 8, ty, 0xFFFFFFFF, false);
		ty += 14;
		graphics.text(this.font, this.fit("From: " + (from != null ? from.name : "?") + " (" + l.sender + ")", w - 16), x + 8, ty, 0xFF3A2E1A);
		ty += 11;
		graphics.text(this.font, this.fit("To: " + (to != null ? to.name : "?") + "   Day " + l.day, w - 16), x + 8, ty, 0xFF3A2E1A);
		ty += 15;

		if (l.amount > 0 && (l.type == LetterData.Type.GIFT || l.type == LetterData.Type.TRIBUTE)) {
			graphics.text(this.font, (l.type == LetterData.Type.GIFT ? "Gift: " : "Demanded: ") + l.amount + " emeralds", x + 8, ty, 0xFF2E6B2E);
			ty += 13;
		}

		for (String line : this.wrap(l.text.isBlank() ? "(no words)" : l.text, w - 16)) {
			graphics.text(this.font, line, x + 8, ty, 0xFF2A2116);
			ty += 10;
		}

		ty += 6;
		String status = switch (l.status) {
			case PENDING -> "Waiting for an answer...";
			case ACCEPTED -> "Accepted.";
			case REFUSED -> "Refused.";
			case DONE -> "";
		};

		graphics.text(this.font, status, x + 8, ty, l.status == LetterData.Status.REFUSED ? 0xFF8A2020 : 0xFF2E6B2E);
		ty += 12;

		for (String line : this.wrap(l.reply, w - 16)) {
			graphics.text(this.font, line, x + 8, ty, 0xFF5A4630);
			ty += 10;
		}
	}

	private static int darker(int rgb) {
		return ((rgb >> 1) & 0x7F7F7F);
	}

	private void drawWrite(GuiGraphicsExtractor graphics) {
		NationData mine = ClientNations.myNation();
		NationData to = ClientNations.get(draftTo);
		int x = this.panelX;
		int y = this.writeTop();
		graphics.text(this.font, "To:", x, y, 0xFFFFD060);

		if (to != null) {
			int cx = x + this.innerW() / 2;
			graphics.centeredText(this.font, Component.literal(this.fit(to.name, this.innerW() - 50)).withColor(to.color), cx, y + 20, 0xFFFFFFFF);

			if (mine != null) {
				int o = ClientDiplomacy.opinion(to, mine);
				String feel = "They feel: " + (o > 0 ? "+" : "") + o + " " + Relations.label(o) + (to.aiRuled() ? "   (answers at once)" : "   (a player decides)");
				graphics.text(this.font, this.fit(feel, this.innerW()), x, y + 36, 0xFF000000 | Relations.color(o));
			}
		}

		// the kind's explanation below its buttons
		int typesBottom = y + 48 + this.typeRowsHeight();
		graphics.text(this.font, this.fit(draftType.help, this.innerW()), x, typesBottom - 12, 0xFF9AA0A6);

		if (draftType == LetterData.Type.GIFT || draftType == LetterData.Type.TRIBUTE) {
			graphics.text(this.font, "Emeralds:", x, typesBottom + 4, 0xFFFFD060);
			typesBottom += 22;
		}

		graphics.text(this.font, "Your words (optional):", x, typesBottom + 4, 0xFFFFD060);
	}

	private int typeRowsHeight() {
		List<ButtonSpec> types = new ArrayList<>();

		for (LetterData.Type t : LetterData.Type.values()) {
			String label = (t == draftType ? "● " : "") + t.displayName;
			types.add(new ButtonSpec(label, this.font.width(label) + 14, b -> { }));
		}

		return this.flowButtons(types, this.panelX, 0, false) + 16;
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
			List<LetterData> letters = ClientDiplomacy.letters();

			if (index >= 0 && index < letters.size()) {
				this.show(View.LETTER, letters.get(index).id);
			}

			return true;
		}

		return false;
	}

	@Override
	protected int listCount() {
		return ClientDiplomacy.letters().size();
	}
}
