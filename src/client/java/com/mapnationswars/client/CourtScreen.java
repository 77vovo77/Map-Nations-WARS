package com.mapnationswars.client;

import java.util.UUID;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.mapnationswars.nation.LetterData;
import com.mapnationswars.nation.MarkerData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.Ranks;
import com.mapnationswars.network.LetterActionPayload;
import com.mapnationswars.network.NationActionPayload;
import com.mapnationswars.network.PersonalActionPayload;

/** The royal court (2.0): opened by talking to a nation's king. Everything you can ask the crown, in one place. */
public class CourtScreen extends Screen {
	private static final int W = 300;
	private static final int H = 236;
	private final UUID nationId;
	private final String greeting;
	private int left;
	private int top;

	public CourtScreen(UUID nation, String greeting) {
		super(Component.literal("Royal Court"));
		this.nationId = nation;
		this.greeting = greeting;
	}

	private NationData nation() {
		return ClientNations.get(this.nationId);
	}

	private UUID me() {
		return this.minecraft != null && this.minecraft.player != null ? this.minecraft.player.getUUID() : new UUID(0, 0);
	}

	private void letter(LetterData.Type type, int amount) {
		if (ClientPlayNetworking.canSend(LetterActionPayload.TYPE)) {
			ClientPlayNetworking.send(new LetterActionPayload(LetterActionPayload.SEND, this.nationId.toString(), type.name(), amount, "", true));
		}
	}

	@Override
	protected void init() {
		int w = Math.min(W, this.width - 12);
		int h = Math.min(H, this.height - 10);
		this.left = (this.width - w) / 2;
		this.top = (this.height - h) / 2;
		NationData n = this.nation();

		if (n == null) {
			return;
		}

		boolean member = n.isMember(this.me());
		NationData mine = ClientNations.myNation();
		int bw = (w - 24 - 4) / 2;
		int x1 = this.left + 12;
		int x2 = x1 + bw + 4;
		int y = this.top + h - 106;

		if (member) {
			int rank = n.rankOf(this.me());
			Button promo = this.addRenderableWidget(Button.builder(Component.literal("⬆ Ask for promotion"), b -> this.letter(LetterData.Type.PROMOTION, 0))
					.pos(x1, y).size(bw, 20).build());
			promo.active = rank < Ranks.MINISTER;
			this.addRenderableWidget(Button.builder(Component.literal("⚑ Ask for a duty"), b -> {
				if (ClientPlayNetworking.canSend(PersonalActionPayload.TYPE)) {
					ClientPlayNetworking.send(new PersonalActionPayload(PersonalActionPayload.NEW_DUTY, ""));
				}
			}).pos(x2, y).size(bw, 20).build());
		} else {
			Button join = this.addRenderableWidget(Button.builder(Component.literal("⚑ Swear allegiance (join)"), b -> {
				if (ClientPlayNetworking.canSend(NationActionPayload.TYPE)) {
					ClientPlayNetworking.send(NationActionPayload.simple(NationActionPayload.REQUEST_JOIN, this.nationId.toString()));
				}
			}).pos(x1, y).size(bw, 20).build());
			join.active = mine == null;
			this.addRenderableWidget(Button.builder(Component.literal("✉ Write to the crown"), b -> this.minecraft.gui.setScreen(new LettersScreen(this.nationId)))
					.pos(x2, y).size(bw, 20).build());
		}

		y += 24;
		this.addRenderableWidget(Button.builder(Component.literal("♦ Gift 10 emeralds"), b -> this.letter(LetterData.Type.GIFT, 10)).pos(x1, y).size(bw, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("♦ Gift 50 emeralds"), b -> this.letter(LetterData.Type.GIFT, 50)).pos(x2, y).size(bw, 20).build());
		y += 24;

		if (ClientPersonal.atWar(this.nationId)) {
			this.addRenderableWidget(Button.builder(Component.literal("☘ Beg for peace (30)"), b -> this.letter(LetterData.Type.PEACE, 30)).pos(x1, y).size(bw, 20).build());
		} else if (!member) {
			this.addRenderableWidget(Button.builder(Component.literal("⚔ Declare your war").withColor(0xFF8080),
					b -> this.minecraft.gui.setScreen(new LettersScreen(this.nationId, LetterData.Type.WAR))).pos(x1, y).size(bw, 20).build());
		}

		this.addRenderableWidget(Button.builder(Component.literal("Nation page"), b -> this.minecraft.gui.setScreen(new NationsScreen(this.nationId)))
				.pos(x2, y).size(bw, 20).build());
		y += 24;
		this.addRenderableWidget(Button.builder(Component.literal("Leave the court"), b -> this.onClose()).pos(x1, y).size(w - 24, 20).build());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, 0x90000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		int w = Math.min(W, this.width - 12);
		int h = Math.min(H, this.height - 10);
		int l = this.left;
		int t = this.top;
		NationData n = this.nation();
		graphics.fill(0, 0, this.width, this.height, 0x90000000);
		graphics.fill(l - 2, t - 2, l + w + 2, t + h + 2, 0xFFB08A2E); // gold frame
		graphics.fill(l - 1, t - 1, l + w + 1, t + h + 1, 0xFF3A2A0E);
		graphics.fill(l, t, l + w, t + h, 0xF01A1420);

		if (n == null) {
			graphics.centeredText(this.font, "This nation is no more.", l + w / 2, t + 20, 0xFFFFFFFF);
			super.extractRenderState(graphics, mouseX, mouseY, delta);
			return;
		}

		graphics.fill(l, t, l + w, t + 3, 0xFF000000 | n.color);
		graphics.centeredText(this.font, Component.literal("♛ The Court of " + n.name).withStyle(s -> s.withColor(0xFFD54F).withBold(true)), l + w / 2, t + 10, 0xFFFFFFFF);
		graphics.centeredText(this.font, Component.literal(n.faction.displayName + "  ·  " + n.ideology.displayName).withColor(n.faction.color), l + w / 2, t + 22, 0xFFFFFFFF);
		int y = t + 38;

		for (String line : this.wrap(this.greeting, w - 24)) {
			graphics.text(this.font, line, l + 12, y, 0xFFF0E0C0);
			y += 10;
		}

		y += 6;
		int provinces = 0;
		int unrest = 0;

		for (MarkerData m : ClientMarkers.all()) {
			if (m.province != null && n.id.equals(m.province.nation)) {
				provinces++;
				unrest += m.province.unrest;
			}
		}

		graphics.text(this.font, "Treasury " + n.treasury + " (" + (n.lastBalance >= 0 ? "+" : "") + n.lastBalance + "/day)   Taxes " + NationData.TAX_NAMES[n.taxLevel],
				l + 12, y, 0xFF9CFF9C);
		y += 11;
		graphics.text(this.font, "Provinces " + provinces + "   Villagers " + n.population + "   Armies " + ClientWar.of(n.id).size()
				+ "   Unrest " + (provinces == 0 ? 0 : unrest / provinces) + "%", l + 12, y, 0xFFDDDDDD);
		y += 11;
		int st = ClientPersonal.standing(n.id);
		boolean member = n.isMember(this.me());
		String you = member ? "You serve as " + Ranks.name(n.rankOf(this.me())) + ", merit " + n.merit.getOrDefault(this.me(), 0)
				: "The crown thinks of you: " + (st > 0 ? "+" : "") + st + (ClientPersonal.outlawIn(n.id) ? " - OUTLAW" : "");
		graphics.text(this.font, you, l + 12, y, member ? 0xFFFFE0A0 : (ClientPersonal.outlawIn(n.id) ? 0xFFFF6050 : 0xFFBBBBBB));
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private java.util.List<String> wrap(String text, int width) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();

		for (String word : text.split(" ")) {
			String test = line.length() == 0 ? word : line + " " + word;

			if (this.font.width(test) > width && line.length() > 0) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(test);
			}
		}

		if (line.length() > 0) {
			lines.add(line.toString());
		}

		return lines;
	}
}
