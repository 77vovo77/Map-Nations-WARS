package com.mapnationswars.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.joml.Matrix3x2fStack;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import com.mapnationswars.nation.AllianceData;
import com.mapnationswars.nation.NationColors;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.network.AllianceActionPayload;

/**
 * The Alliances tab. An alliance is a group of whole nations: only nation leaders found, join or leave one,
 * and their whole nation comes with them. (Different from "allies", which are just two friendly nations.)
 */
public class AlliancesScreen extends PagedScreen {
	private enum View { OVERVIEW, ALLIANCE, FORM }

	private static final int CELL = 18;

	// the form survives opening the banner picker
	private static String draftName = "";
	private static int draftColor = -1;
	private static int draftBannerSlot = -1;
	private static ItemStack draftBanner = ItemStack.EMPTY;
	private static boolean draftBannerRemoved = false;

	private View view;
	private UUID selected;
	private boolean editing = false;
	private int seenVersion;

	private int bannerSlotX;
	private int bannerSlotY;
	private int paletteX;
	private int paletteY;
	private int paletteColumns = 12;

	public AlliancesScreen() {
		super(Tab.ALLIANCES);
		this.seenVersion = ClientNations.version();
		AllianceData mine = this.myAlliance();

		if (mine != null) {
			this.view = View.ALLIANCE;
			this.selected = mine.id;
		} else {
			this.view = View.OVERVIEW;
		}
	}

	/** Opens straight on one alliance's page. */
	public AlliancesScreen(UUID alliance) {
		this();

		if (alliance != null && ClientNations.alliance(alliance) != null) {
			this.view = View.ALLIANCE;
			this.selected = alliance;
		}
	}

	// ---------------------------------------------------------------- helpers

	private UUID me() {
		return this.minecraft != null && this.minecraft.player != null ? this.minecraft.player.getUUID() : new UUID(0, 0);
	}

	private NationData myNation() {
		return ClientNations.myNation();
	}

	private boolean amNationLeader() {
		NationData n = this.myNation();
		return n != null && this.minecraft != null && this.minecraft.player != null && n.leader.equals(this.minecraft.player.getUUID());
	}

	private AllianceData myAlliance() {
		NationData n = ClientNations.myNation();
		return n != null ? ClientNations.allianceOf(n.id) : null;
	}

	private boolean amHead(AllianceData a) {
		NationData n = this.myNation();
		return a != null && n != null && n.id.equals(a.head()) && this.amNationLeader();
	}

	private List<AllianceData> sorted() {
		List<AllianceData> list = new ArrayList<>(ClientNations.alliances());
		list.sort((a, b) -> {
			int bySize = Integer.compare(b.nations.size(), a.nations.size());
			return bySize != 0 ? bySize : a.name.compareToIgnoreCase(b.name);
		});
		return list;
	}

	private void send(AllianceActionPayload payload) {
		if (!ClientPlayNetworking.canSend(AllianceActionPayload.TYPE)) {
			ClientNations.setStatus("This server doesn't have Map Nations WARS installed, so alliances don't work here.", false);
			return;
		}

		ClientPlayNetworking.send(payload);
	}

	private void show(View v, UUID alliance) {
		if (v != this.view || alliance == null || !alliance.equals(this.selected)) {
			this.scroll = 0;
		}

		this.view = v;
		this.selected = alliance;
		this.rebuildWidgets();
	}

	private void startCreate() {
		draftName = "";
		draftColor = -1;
		draftBannerSlot = -1;
		draftBanner = ItemStack.EMPTY;
		draftBannerRemoved = false;
		this.editing = false;
		this.show(View.FORM, null);
	}

	private void startEdit(AllianceData a) {
		draftName = a.name;
		draftColor = a.color;
		draftBannerSlot = -1;
		draftBanner = a.banner;
		draftBannerRemoved = false;
		this.editing = true;
		this.show(View.FORM, a.id);
	}

	private void onDataChanged() {
		AllianceData mine = this.myAlliance();

		if (this.view == View.FORM && !this.editing && mine != null) {
			this.view = View.ALLIANCE; // just founded it
			this.selected = mine.id;
			this.scroll = 0;
		}

		if (this.view == View.FORM && this.editing && (mine == null || !this.amHead(mine))) {
			this.view = View.OVERVIEW;
		}

		if (this.view == View.ALLIANCE && ClientNations.alliance(this.selected) == null) {
			this.view = mine != null ? View.ALLIANCE : View.OVERVIEW;
			this.selected = mine != null ? mine.id : null;
		}
	}

	// ---------------------------------------------------------------- widgets

	@Override
	protected void init() {
		this.addTabs();
		this.layoutFrame();

		switch (this.view) {
			case OVERVIEW -> this.initOverview();
			case ALLIANCE -> this.initAlliance();
			case FORM -> this.initForm();
		}

		this.clampScroll();
	}

	private void initOverview() {
		this.contentHeight = 0;

		if (this.amNationLeader() && this.myAlliance() == null) {
			int mid = this.viewTop() + (this.viewBottom() - this.viewTop()) / 2;
			this.addRenderableWidget(Button.builder(Component.literal("+ Found an Alliance"), b -> this.startCreate())
					.pos(this.panelX + this.panelW / 2 - 80, mid + 16).size(160, 22).build());
		}
	}

	private record Layout(int nameY, int bannerY, int infoY, List<String> info, int nationsY, int rowH, boolean narrow,
			int requestsY, int noteY, List<String> note, int buttonsY, int bottom) {
	}

	private String noteFor(AllianceData a) {
		NationData mine = this.myNation();
		AllianceData myAlliance = this.myAlliance();

		if (mine == null) {
			return "Only nations can join alliances. Create or join a nation first (Nations tab).";
		}

		if (a.nations.contains(mine.id)) {
			return this.amNationLeader() ? "" : "Your nation is in this alliance. Only your " + mine.ideology.leaderTitle + " can leave it.";
		}

		if (!this.amNationLeader()) {
			return "Only your nation's " + mine.ideology.leaderTitle + " can ask to join an alliance.";
		}

		if (myAlliance != null) {
			return "To join the " + a.name + ", your nation first has to leave the " + myAlliance.name + ".";
		}

		if (a.requests.contains(mine.id)) {
			return "Waiting for the head of the alliance to accept " + mine.name + "...";
		}

		return "";
	}

	private Layout layout(AllianceData a) {
		boolean head = this.amHead(a);
		int y = this.barBottom() + 10 - this.scroll;
		int nameY = y;
		y += 22;
		int bannerY = y;

		if (!a.banner.isEmpty()) {
			y += 36;
		}

		int infoY = y;
		int players = 0;

		for (UUID id : a.nations) {
			NationData n = ClientNations.get(id);
			players += n != null ? n.members.size() : 0;
		}

		List<String> info = this.wrap("Nations: " + a.nations.size() + "   Territory: " + ClientNations.allianceChunks(a)
				+ " chunks   People: " + players, this.innerW());
		y += 12 + info.size() * 11 + 8;

		boolean narrow = this.innerW() < 300;
		int nationsY = y;
		int rowH = head ? (narrow ? 36 : 22) : 20;
		y += 12 + a.nations.size() * rowH + 6;

		int requestsY = y;

		if (head && !a.requests.isEmpty()) {
			y += 12 + a.requests.size() * (narrow ? 36 : 22) + 6;
		}

		int noteY = y;
		List<String> note = this.noteFor(a).isEmpty() ? List.of() : this.wrap(this.noteFor(a), this.innerW());
		y += note.size() * 11 + (note.isEmpty() ? 0 : 6);

		int buttonsY = y;
		y += this.flowButtons(this.bottomButtons(a), this.panelX, y, false);
		return new Layout(nameY, bannerY, infoY, info, nationsY, rowH, narrow, requestsY, noteY, note, buttonsY, y + this.scroll);
	}

	private List<ButtonSpec> bottomButtons(AllianceData a) {
		List<ButtonSpec> list = new ArrayList<>();
		NationData mine = this.myNation();

		if (mine == null || !this.amNationLeader()) {
			return list;
		}

		String id = a.id.toString();

		if (a.nations.contains(mine.id)) {
			if (this.amHead(a)) {
				list.add(new ButtonSpec("Edit alliance", 100, b -> this.startEdit(a)));
			}

			list.add(new ButtonSpec(a.nations.size() == 1 ? "Dissolve alliance" : "Leave alliance", 110,
					b -> this.send(AllianceActionPayload.simple(AllianceActionPayload.LEAVE, ""))));
		} else if (this.myAlliance() == null) {
			if (a.requests.contains(mine.id)) {
				list.add(new ButtonSpec("Cancel request", 110, b -> this.send(AllianceActionPayload.simple(AllianceActionPayload.CANCEL_REQUEST, id))));
			} else {
				list.add(new ButtonSpec("Ask to join", 110, b -> this.send(AllianceActionPayload.simple(AllianceActionPayload.REQUEST_JOIN, id))));
			}

			list.add(new ButtonSpec("+ Found an Alliance", 130, b -> this.startCreate()));
		}

		return list;
	}

	private void initAlliance() {
		AllianceData a = ClientNations.alliance(this.selected);

		if (a == null) {
			this.contentHeight = 0;
			return;
		}

		Layout lay = this.layout(a);
		this.contentHeight = lay.bottom() - (this.barBottom() + 10);
		int right = this.panelX + this.innerW();

		if (this.amHead(a)) {
			for (int i = 1; i < a.nations.size(); i++) { // the head (first) can't be removed
				String target = a.nations.get(i).toString();
				int rowY = lay.nationsY() + 12 + i * lay.rowH();
				int by = lay.narrow() ? rowY + 16 : rowY + 1;
				int bx = lay.narrow() ? this.panelX + 20 : right - 142;
				this.addScrolled(Button.builder(Component.literal("Make head"),
								b -> this.send(AllianceActionPayload.simple(AllianceActionPayload.MAKE_HEAD, target)))
						.pos(bx, by).size(78, 18).build());
				this.addScrolled(Button.builder(Component.literal("Remove"),
								b -> this.send(AllianceActionPayload.simple(AllianceActionPayload.KICK, target)))
						.pos(bx + 82, by).size(58, 18).build());
			}

			int reqH = lay.narrow() ? 36 : 22;

			for (int i = 0; i < a.requests.size(); i++) {
				String target = a.requests.get(i).toString();
				int rowY = lay.requestsY() + 12 + i * reqH;
				int by = lay.narrow() ? rowY + 16 : rowY + 1;
				int bx = lay.narrow() ? this.panelX + 20 : right - 108;
				this.addScrolled(Button.builder(Component.literal("Accept"),
								b -> this.send(AllianceActionPayload.simple(AllianceActionPayload.ACCEPT, target)))
						.pos(bx, by).size(56, 18).build());
				this.addScrolled(Button.builder(Component.literal("Deny"),
								b -> this.send(AllianceActionPayload.simple(AllianceActionPayload.DENY, target)))
						.pos(bx + 60, by).size(46, 18).build());
			}
		}

		this.flowButtons(this.bottomButtons(a), this.panelX, lay.buttonsY(), true);
	}

	private record FormLayout(int titleY, int nameY, int bannerLabelY, int colourLabelY, int buttonsY, int bottom) {
	}

	private FormLayout formLayout() {
		int y = this.barBottom() + 10 - this.scroll;
		int titleY = y;
		y += 25;
		int nameY = y;
		y += 12 + 20 + 8;
		int bannerLabelY = y;
		this.bannerSlotX = this.panelX + 10;
		this.bannerSlotY = y + 12;
		y += 12 + 26 + 10;
		int colourLabelY = y;
		y += 12;
		this.paletteColumns = Math.max(4, Math.min(12, (this.innerW() - 10) / CELL));
		this.paletteX = this.panelX + 10;
		this.paletteY = y;
		y += ((NationColors.PALETTE.length + this.paletteColumns - 1) / this.paletteColumns) * CELL + 10;
		int buttonsY = y;
		y += 24;
		return new FormLayout(titleY, nameY, bannerLabelY, colourLabelY, buttonsY, y + this.scroll);
	}

	private void initForm() {
		FormLayout lay = this.formLayout();
		this.contentHeight = lay.bottom() - (this.barBottom() + 10);
		int x = this.panelX + 10;

		EditBox nameBox = new EditBox(this.font, x, lay.nameY() + 12, Math.min(220, this.innerW() - 10), 20, null, Component.literal("Alliance name"));
		nameBox.setMaxLength(24);
		nameBox.setValue(draftName);
		nameBox.setResponder(text -> draftName = text);
		nameBox.visible = nameBox.getY() >= this.viewTop() && nameBox.getY() + 20 <= this.viewBottom();
		this.addRenderableWidget(nameBox);

		if (!draftBanner.isEmpty()) {
			this.addScrolled(Button.builder(Component.literal("Remove"), b -> {
				draftBanner = ItemStack.EMPTY;
				draftBannerSlot = -1;
				draftBannerRemoved = true;
				this.rebuildWidgets();
			}).pos(x + 32, this.bannerSlotY + 4).size(56, 18).build());
		}

		this.addScrolled(Button.builder(Component.literal(this.editing ? "Save changes" : "Found alliance"), b -> this.submit())
				.pos(x, lay.buttonsY()).size(120, 20).build());
		this.addScrolled(Button.builder(Component.literal("Cancel"), b -> {
			AllianceData mine = this.myAlliance();
			this.show(mine != null ? View.ALLIANCE : View.OVERVIEW, mine != null ? mine.id : null);
		}).pos(x + 124, lay.buttonsY()).size(80, 20).build());
	}

	private void submit() {
		String name = draftName.trim();

		if (name.length() < 3) {
			ClientNations.setStatus("The name needs at least 3 letters.", false);
			return;
		}

		if (draftColor < 0) {
			ClientNations.setStatus("Pick a colour for the alliance.", false);
			return;
		}

		int slot = this.editing ? (draftBannerRemoved ? -2 : (draftBannerSlot >= 0 ? draftBannerSlot : -1)) : draftBannerSlot;
		this.send(new AllianceActionPayload(this.editing ? AllianceActionPayload.EDIT : AllianceActionPayload.CREATE, "", name, draftColor, slot));

		if (this.editing) {
			AllianceData mine = this.myAlliance();
			this.show(View.ALLIANCE, mine != null ? mine.id : null);
		}
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (ClientNations.version() != this.seenVersion) {
			this.seenVersion = ClientNations.version();
			this.onDataChanged();
			this.rebuildWidgets();
		}

		this.drawFrame(graphics, "Alliances (" + ClientNations.alliances().size() + ")");
		this.drawList(graphics, mouseX, mouseY);

		graphics.enableScissor(this.panelX - 4, this.viewTop(), this.right(), this.viewBottom());

		switch (this.view) {
			case OVERVIEW -> this.drawOverview(graphics);
			case ALLIANCE -> this.drawAlliance(graphics);
			case FORM -> this.drawForm(graphics, mouseX, mouseY);
		}

		graphics.disableScissor();
		this.drawScrollBar(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.nextStratum();
		this.drawStatus(graphics, this.bottom() - 22);
	}

	private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<AllianceData> list = this.sorted();

		if (list.isEmpty()) {
			graphics.text(this.font, "No alliances yet.", this.listX + 6, this.listY + 8, 0xFFAAAAAA);
			return;
		}

		int visible = (this.listBottom - this.listY) / ROW_H;
		this.listScroll = Math.max(0, Math.min(this.listScroll, list.size() - visible));
		AllianceData mine = this.myAlliance();
		graphics.enableScissor(this.listX, this.listY, this.listX + this.listW, this.listBottom);

		for (int i = 0; i < list.size() - this.listScroll && i <= visible; i++) {
			AllianceData a = list.get(i + this.listScroll);
			int y = this.listY + i * ROW_H;
			boolean hover = mouseX >= this.listX && mouseX < this.listX + this.listW && mouseY >= y && mouseY < y + ROW_H && mouseY < this.listBottom;
			this.drawListRow(graphics, y, a.color, a.id.equals(this.selected) && this.view == View.ALLIANCE, hover);
			int textX = this.listX + 8;
			this.drawIcon(graphics, a.banner, a.color, textX, y + 4);
			textX += 20;
			String name = (mine != null && mine.id.equals(a.id) ? "★ " : "") + a.name;
			graphics.text(this.font, this.fit(name, this.listX + this.listW - textX - 4), textX, y + 4, 0xFF000000 | a.color);
			graphics.text(this.font, a.nations.size() + (a.nations.size() == 1 ? " nation" : " nations"), textX, y + 14, 0xFFAAAAAA);
		}

		graphics.disableScissor();
		this.drawListScrollBar(graphics, list.size(), visible);
	}

	/** A banner, or a little flag in the colour if there is no banner. */
	private void drawIcon(GuiGraphicsExtractor graphics, ItemStack banner, int color, int x, int y) {
		if (!banner.isEmpty()) {
			graphics.item(banner, x, y);
		} else {
			graphics.fill(x + 2, y + 1, x + 14, y + 15, 0xFF1B1B1B);
			graphics.fill(x + 3, y + 2, x + 13, y + 14, 0xFF000000 | color);
		}
	}

	private void drawOverview(GuiGraphicsExtractor graphics) {
		int cx = this.panelX + this.panelW / 2;
		int mid = this.viewTop() + (this.viewBottom() - this.viewTop()) / 2;
		graphics.centeredText(this.font, Component.literal("Alliances").withStyle(s -> s.withColor(0xFFD54F).withBold(true)), cx, mid - 56, 0xFFFFFFFF);
		String text;
		NationData mine = this.myNation();

		if (mine == null) {
			text = "An alliance is a group of whole nations. Join or create a nation first, then its leader can found or join an alliance.";
		} else if (!this.amNationLeader()) {
			text = "An alliance is a group of whole nations. Only your " + mine.ideology.leaderTitle + " can found or join one for " + mine.name + ".";
		} else {
			text = "An alliance is a group of whole nations. Found your own, or click an alliance on the left and ask to join with " + mine.name + ".";
		}

		int y = mid - 40;

		for (String line : this.wrap(text, this.panelW - 20)) {
			graphics.centeredText(this.font, line, cx, y, 0xFFBBBBBB);
			y += 11;
		}
	}

	private void drawBigText(GuiGraphicsExtractor graphics, Component text, int centerX, int y, float scale) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(centerX, y);
		pose.scale(scale);
		graphics.centeredText(this.font, text, 0, 0, 0xFFFFFFFF);
		pose.popMatrix();
	}

	private void drawAlliance(GuiGraphicsExtractor graphics) {
		AllianceData a = ClientNations.alliance(this.selected);

		if (a == null) {
			return;
		}

		Layout lay = this.layout(a);
		int x = this.panelX;
		int w = this.innerW();
		int cx = x + w / 2;
		float scale = Math.max(1.0f, Math.min(2.0f, (w - 8) / (float) Math.max(1, this.font.width(a.name))));
		this.drawBigText(graphics, Component.literal(a.name).withColor(a.color), cx, lay.nameY() + 2, scale);

		if (!a.banner.isEmpty()) {
			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(cx - 16, lay.bannerY());
			pose.scale(2.0f);
			graphics.item(a.banner, 0, 0);
			pose.popMatrix();
		}

		NationData head = ClientNations.get(a.head());
		int y = lay.infoY();

		if (head != null) {
			graphics.text(this.font, Component.literal("Led by ").withColor(0xAAAAAA)
					.append(Component.literal(this.fit(head.name, w / 2)).withColor(head.color))
					.append(Component.literal("  (" + head.ideology.symbol + " " + head.leaderName() + ")").withColor(0xFFD54F)), x, y, 0xFFFFFFFF, true);
		}

		for (int i = 0; i < lay.info().size(); i++) {
			graphics.text(this.font, lay.info().get(i), x, y + 12 + i * 11, 0xFFDDDDDD);
		}

		this.drawHeading(graphics, "Member nations (" + a.nations.size() + ")", x, lay.nationsY(), 0xFFFFD060);
		boolean head_ = this.amHead(a);
		int nameRoom = head_ && !lay.narrow() ? w - 20 - 146 : w - 20;

		for (int i = 0; i < a.nations.size(); i++) {
			NationData n = ClientNations.get(a.nations.get(i));
			int rowY = lay.nationsY() + 12 + i * lay.rowH();

			if (n == null || rowY > this.viewBottom() || rowY + lay.rowH() < this.viewTop()) {
				continue;
			}

			this.drawIcon(graphics, n.banner, n.color, x + 2, rowY + 1);
			String label = this.fit(n.name, Math.max(30, nameRoom - (i == 0 ? 40 : 0)));
			Component text = Component.literal(label).withColor(n.color);

			if (i == 0) {
				text = Component.literal(label).withColor(n.color).append(Component.literal("  head").withColor(0xFFD54F));
			}

			graphics.text(this.font, text, x + 22, rowY + 5, 0xFFFFFFFF, true);
		}

		if (head_ && !a.requests.isEmpty()) {
			this.drawHeading(graphics, "Want to join (" + a.requests.size() + ")", x, lay.requestsY(), 0xFF9CFF9C);
			int reqH = lay.narrow() ? 36 : 22;

			for (int i = 0; i < a.requests.size(); i++) {
				NationData n = ClientNations.get(a.requests.get(i));
				int rowY = lay.requestsY() + 12 + i * reqH;

				if (n != null) {
					this.drawIcon(graphics, n.banner, n.color, x + 2, rowY + 1);
					graphics.text(this.font, Component.literal(this.fit(n.name, lay.narrow() ? w - 24 : w - 24 - 112)).withColor(n.color),
							x + 22, rowY + 5, 0xFFFFFFFF, true);
				}
			}
		}

		for (int i = 0; i < lay.note().size(); i++) {
			graphics.text(this.font, lay.note().get(i), x, lay.noteY() + i * 11, 0xFFAAAAAA);
		}
	}

	private void drawForm(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		FormLayout lay = this.formLayout();
		int x = this.panelX + 10;
		int cx = this.panelX + this.innerW() / 2;
		this.drawBigText(graphics, Component.literal(this.editing ? "Edit Alliance" : "Found an Alliance"), cx, lay.titleY(), 1.5f);
		graphics.text(this.font, "Name", x, lay.nameY(), 0xFFFFD060);
		graphics.text(this.font, "Banner (optional)", x, lay.bannerLabelY(), 0xFFFFD060);

		int sx = this.bannerSlotX;
		int sy = this.bannerSlotY;
		boolean slotHover = mouseX >= sx && mouseX < sx + 26 && mouseY >= sy && mouseY < sy + 26;
		graphics.fill(sx, sy, sx + 26, sy + 26, slotHover ? 0xFFA0A0A0 : 0xFF8B8B8B);
		graphics.fill(sx + 1, sy + 1, sx + 25, sy + 25, 0xFF373737);

		if (!draftBanner.isEmpty()) {
			graphics.item(draftBanner, sx + 5, sy + 5);
		} else {
			graphics.text(this.font, "+", sx + 11, sy + 9, 0xFF777777);
			List<String> hint = this.wrap("Click to pick a banner from your inventory", this.innerW() - 42);

			for (int i = 0; i < hint.size(); i++) {
				graphics.text(this.font, hint.get(i), sx + 32, sy + 13 - hint.size() * 11 / 2 + i * 11, 0xFFAAAAAA);
			}
		}

		graphics.text(this.font, "Colour", x, lay.colourLabelY(), 0xFFFFD060);
		Set<Integer> used = ClientNations.usedAllianceColors(this.editing ? this.selected : null);

		for (int i = 0; i < NationColors.PALETTE.length; i++) {
			int color = NationColors.PALETTE[i];
			int px = this.paletteX + (i % this.paletteColumns) * CELL;
			int py = this.paletteY + (i / this.paletteColumns) * CELL;
			graphics.fill(px, py, px + CELL - 2, py + CELL - 2, color == draftColor ? 0xFFFFFFFF : 0xFF000000);
			graphics.fill(px + 1, py + 1, px + CELL - 3, py + CELL - 3, 0xFF000000 | color);

			if (used.contains(color)) {
				graphics.fill(px + 1, py + 1, px + CELL - 3, py + CELL - 3, 0xB0000000);
				graphics.text(this.font, "x", px + 5, py + 3, 0xFFFF5555);
			}
		}
	}

	// ---------------------------------------------------------------- input

	private int paletteIndexAt(double mx, double my) {
		if (mx < this.paletteX || my < this.paletteY || my < this.viewTop() || my > this.viewBottom()) {
			return -1;
		}

		int col = (int) ((mx - this.paletteX) / CELL);
		int row = (int) ((my - this.paletteY) / CELL);

		if (col >= this.paletteColumns) {
			return -1;
		}

		int index = row * this.paletteColumns + col;
		return index < NationColors.PALETTE.length ? index : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
		if (super.mouseClicked(click, doubleClick)) {
			return true;
		}

		double mx = click.x();
		double my = click.y();

		if (mx >= this.listX && mx < this.listX + this.listW && my >= this.listY && my < this.listBottom) {
			int index = (int) ((my - this.listY) / ROW_H) + this.listScroll;
			List<AllianceData> list = this.sorted();

			if (index >= 0 && index < list.size()) {
				this.show(View.ALLIANCE, list.get(index).id);
			}

			return true;
		}

		if (this.view == View.FORM) {
			if (mx >= this.bannerSlotX && mx < this.bannerSlotX + 26 && my >= this.bannerSlotY && my < this.bannerSlotY + 26
					&& my >= this.viewTop() && my <= this.viewBottom()) {
				this.minecraft.gui.setScreen(new BannerPickerScreen(this, draftBannerSlot, draftBanner, (slot, banner) -> {
					draftBannerSlot = slot;
					draftBanner = banner;
					draftBannerRemoved = banner.isEmpty();
				}));
				return true;
			}

			int index = this.paletteIndexAt(mx, my);

			if (index >= 0) {
				int color = NationColors.PALETTE[index];

				if (ClientNations.usedAllianceColors(this.editing ? this.selected : null).contains(color)) {
					ClientNations.setStatus("That colour already belongs to another alliance.", false);
				} else {
					draftColor = color;
				}

				return true;
			}
		}

		return false;
	}

	@Override
	protected int listCount() {
		return ClientNations.alliances().size();
	}
}
