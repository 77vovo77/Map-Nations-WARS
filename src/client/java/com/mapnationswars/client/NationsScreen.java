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
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import com.mapnationswars.nation.Ideology;
import com.mapnationswars.nation.NationColors;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.network.NationActionPayload;

/**
 * The Nations tab.
 * Left: every nation. Right: create your nation / your nation's panel / info about the nation you clicked.
 */
public class NationsScreen extends PagedScreen {
	private enum View { OVERVIEW, NATION, FORM }

	// The form is kept in static fields so it survives opening the banner picker.
	static String draftName = "";
	static int draftColor = -1;
	static Ideology draftIdeology = null;
	static int draftBannerSlot = -1;
	static ItemStack draftBanner = ItemStack.EMPTY;
	static boolean draftBannerRemoved = false;

	private static final int CELL = 18;
	private static final int PALETTE_COLUMNS = 12;
	private static final int DROPDOWN_ROW = 11;
	private static final int DROPDOWN_W = 250;

	private View view;
	private UUID selected;
	private boolean editing = false;
	private boolean waitingForCreate = false;
	private boolean ideologyOpen = false;
	private int dropdownScroll = 0;
	private int seenVersion;
	private NationData hoveredAlly = null;

	// form positions
	private int bannerSlotX;
	private int bannerSlotY;
	private int paletteX;
	private int paletteY;
	private int dropdownX;
	private int dropdownY;

	public NationsScreen() {
		super(Tab.NATIONS);
		this.seenVersion = ClientNations.version();
		NationData mine = ClientNations.myNation();

		if (mine != null) {
			this.view = View.NATION;
			this.selected = mine.id;
		} else {
			this.view = View.OVERVIEW;
		}
	}

	/** Opens straight on one nation's page (e.g. after clicking it on the map). */
	public NationsScreen(UUID nation) {
		this();

		if (nation != null && ClientNations.get(nation) != null) {
			this.view = View.NATION;
			this.selected = nation;
		}
	}

	// ---------------------------------------------------------------- helpers

	private UUID myId() {
		return this.minecraft.player != null ? this.minecraft.player.getUUID() : new UUID(0, 0);
	}

	private List<NationData> sortedNations() {
		List<NationData> list = new ArrayList<>(ClientNations.all());
		list.sort((a, b) -> {
			int bySize = Integer.compare(b.members.size(), a.members.size());
			return bySize != 0 ? bySize : a.name.compareToIgnoreCase(b.name);
		});
		return list;
	}

	private void send(NationActionPayload payload) {
		if (!ClientPlayNetworking.canSend(NationActionPayload.TYPE)) {
			ClientNations.setStatus("This server doesn't have Map Nations WARS installed, so nations don't work here.", false);
			return;
		}

		ClientPlayNetworking.send(payload);
	}

	private void show(View v, UUID nation) {
		if (v != this.view || nation == null || !nation.equals(this.selected)) {
			this.scroll = 0; // new page: start at the top
		}

		this.view = v;
		this.selected = nation;
		this.ideologyOpen = false;
		this.rebuildWidgets();
	}

	private void startCreate() {
		draftName = "";
		draftColor = -1;
		draftIdeology = null;
		draftBannerSlot = -1;
		draftBanner = ItemStack.EMPTY;
		draftBannerRemoved = false;
		this.editing = false;
		this.show(View.FORM, null);
	}

	private void startEdit(NationData n) {
		draftName = n.name;
		draftColor = n.color;
		draftIdeology = n.ideology;
		draftBannerSlot = -1;
		draftBanner = n.banner;
		draftBannerRemoved = false;
		this.editing = true;
		this.show(View.FORM, n.id);
	}

	/** Called when new data came from the server. */
	private void onDataChanged() {
		NationData mine = ClientNations.myNation();

		if (this.waitingForCreate && mine != null) {
			this.waitingForCreate = false;
			this.view = View.NATION;
			this.selected = mine.id;
		}

		if (this.view == View.FORM && this.editing && (mine == null || !mine.leader.equals(this.myId()))) {
			this.view = View.OVERVIEW;
		}

		if (this.view == View.NATION && ClientNations.get(this.selected) == null) {
			this.view = mine != null ? View.NATION : View.OVERVIEW;
			this.selected = mine != null ? mine.id : null;
		}

		if (this.view == View.OVERVIEW && mine != null) {
			this.view = View.NATION;
			this.selected = mine.id;
		}
	}

	// ---------------------------------------------------------------- widgets

	@Override
	protected void init() {
		this.addTabs();
		this.layoutFrame();

		switch (this.view) {
			case OVERVIEW -> this.contentHeight = 0;
			case NATION -> this.initNationView();
			case FORM -> this.initForm();
		}

		this.clampScroll();
	}

	/** Where everything on a nation's page goes (already moved by the scroll). */
	private record NationLayout(int nameY, int bannerY, int infoY, List<String> stats, int allyY, int allyRows,
			int membersY, int memberRowH, boolean narrowRows, int requestsY, int requestRowH, int proposalsY,
			int noteY, int buttonsY, int bottom) {
	}

	private int alliesPerRow() {
		return Math.max(1, (this.innerW() - this.font.width("Allies: ") - 2) / 20);
	}

	private NationLayout layout(NationData n, boolean leaderView) {
		int y = this.barBottom() + 10 - this.scroll;
		int nameY = y;
		y += 22;
		int bannerY = y;

		if (!n.banner.isEmpty()) {
			y += 36;
		}

		int infoY = y;
		y += 36;
		com.mapnationswars.nation.AllianceData alliance = ClientNations.allianceOf(n.id);
		int provinces = 0;

		for (com.mapnationswars.nation.MarkerData m : ClientMarkers.all()) {
			if (m.province != null && n.id.equals(m.province.nation)) {
				provinces++;
			}
		}

		String money = "   Treasury: " + n.treasury + " emeralds (" + (n.lastBalance >= 0 ? "+" : "") + n.lastBalance + "/day)";
		List<String> stats = this.wrap((n.ai ? n.faction.displayName + " (AI)   Provinces: " + provinces + money + "   " : "")
				+ "Members: " + n.members.size() + "   Territory: " + ClientNations.chunkCount(n.id)
				+ " chunks   Villagers: " + n.population + (alliance != null ? "   Alliance: " + alliance.name : ""), this.innerW());
		stats = new ArrayList<>(stats);
		stats.addAll(this.politicsLines(n));
		y += stats.size() * 11 + 3;

		int allyY = y;
		int allyRows = Math.max(1, (n.allies.size() + this.alliesPerRow() - 1) / this.alliesPerRow());
		y += allyRows * 20 + 6;

		// on narrow screens the leader's buttons go under each name instead of next to it
		boolean narrow = this.innerW() < 330;
		int membersY = y;
		int rowH = leaderView ? (narrow ? 34 : 20) : 12;
		y += 12 + n.members.size() * rowH + 6;

		int requestsY = y;
		int requestRows = leaderView ? n.requests.size() + n.allyRequests.size() : 0;
		int requestRowH = narrow ? 34 : 20;

		if (requestRows > 0) {
			y += 12 + requestRows * requestRowH + 6;
		}

		int proposalsY = y;

		if (leaderView && ClientNations.proposalCount(n.id) > 0) {
			y += 12 + 24;
		}

		int noteY = y;
		NationData mine = ClientNations.myNation();
		boolean isMine = mine != null && mine.id.equals(n.id);

		if (!isMine && (mine != null || n.hasRequest(this.myId()))) {
			y += 12 * this.wrap(this.noteText(n, mine), this.innerW()).size() + 4;
		}

		int buttonsY = y;
		y += this.flowButtons(this.bottomButtons(n), this.panelX, y, false);
		return new NationLayout(nameY, bannerY, infoY, stats, allyY, allyRows, membersY, rowH, narrow, requestsY, requestRowH,
				proposalsY, noteY, buttonsY, y + this.scroll);
	}

	private String noteText(NationData n, NationData mine) {
		if (mine != null) {
			return "To join " + n.name + " you first have to leave " + mine.name + ".";
		}

		return "Waiting for the " + n.ideology.leaderTitle + " to accept you...";
	}

	/** The buttons at the bottom of a nation's page. */
	/** Your rank, merit and salary in your nation, and the next election (Map Nations WARS). */
	private List<String> politicsLines(NationData n) {
		List<String> lines = new ArrayList<>();
		UUID me = this.myId();

		if (n.isMember(me)) {
			int rank = n.rankOf(me);
			String rankName = n.leader.equals(me) ? n.ideology.leaderTitle : com.mapnationswars.nation.Ranks.name(rank);
			int merit = n.merit.getOrDefault(me, 0);
			String next = rank + 1 < com.mapnationswars.nation.Ranks.NAMES.length && n.aiRuled()
					? " (" + com.mapnationswars.nation.Ranks.name(rank + 1) + " at " + com.mapnationswars.nation.Ranks.MERIT[rank + 1] + ")" : "";
			int salary = n.leader.equals(me) ? com.mapnationswars.nation.Ranks.LEADER_SALARY : com.mapnationswars.nation.Ranks.SALARY[Math.min(3, rank)];
			int owed = n.owed.getOrDefault(me, 0);
			lines.addAll(this.wrap("You: " + rankName + "   Merit " + merit + next + "   Salary " + salary + "/day"
					+ (owed > 0 ? "   Waiting: " + owed + " emeralds (collect at any mayor)" : ""), this.innerW()));
		}

		if (n.ai && com.mapnationswars.nation.Ranks.hasElections(n.ideology)) {
			long day = this.minecraft != null && this.minecraft.level != null ? this.minecraft.level.getGameTime() / 24000 : 0;
			long days = Math.max(0, n.nextElection - day);
			StringBuilder names = new StringBuilder();

			for (UUID c : n.candidates) {
				NationData.Member m = n.member(c);

				if (m != null) {
					names.append(names.length() == 0 ? "" : ", ").append(m.name());
				}
			}

			lines.addAll(this.wrap("Elections: next in " + (n.nextElection == 0 ? "?" : days + (days == 1 ? " day" : " days"))
					+ (names.length() > 0 ? "   Candidates: " + names : "   No candidates yet"), this.innerW()));

			if (!n.lastElection.isEmpty()) {
				lines.addAll(this.wrap("Last election: " + n.lastElection, this.innerW()));
			}
		}

		return lines;
	}

	private List<ButtonSpec> bottomButtons(NationData n) {
		List<ButtonSpec> list = new ArrayList<>();
		UUID me = this.myId();
		NationData mine = ClientNations.myNation();
		boolean isMine = mine != null && mine.id.equals(n.id);
		String nationId = n.id.toString();

		if (isMine) {
			if (n.leader.equals(me)) {
				list.add(new ButtonSpec("Edit nation", 100, b -> this.startEdit(n)));
			}

			list.add(new ButtonSpec("Leave nation", 100, b -> this.send(NationActionPayload.simple(NationActionPayload.LEAVE, ""))));

			// elections: Officers and higher can run
			if (n.ai && com.mapnationswars.nation.Ranks.hasElections(n.ideology)
					&& (n.leader.equals(me) || n.rankOf(me) >= com.mapnationswars.nation.Ranks.OFFICER)) {
				if (n.candidates.contains(me)) {
					list.add(new ButtonSpec("Withdraw candidacy", 120, b -> this.send(NationActionPayload.simple(NationActionPayload.WITHDRAW_CANDIDACY, ""))));
				} else {
					list.add(new ButtonSpec("Run for " + n.ideology.leaderTitle, Math.max(110, this.font.width("Run for " + n.ideology.leaderTitle) + 16),
							b -> this.send(NationActionPayload.simple(NationActionPayload.RUN_FOR_OFFICE, ""))));
				}
			}
		} else if (mine == null) {
			if (n.hasRequest(me)) {
				list.add(new ButtonSpec("Cancel request", 120,
						b -> this.send(NationActionPayload.simple(NationActionPayload.CANCEL_REQUEST, nationId))));
			} else {
				list.add(new ButtonSpec("Ask to join", 120,
						b -> this.send(NationActionPayload.simple(NationActionPayload.REQUEST_JOIN, nationId))));
			}

		} else if (mine.leader.equals(me)) {
			// leaders handle alliances with other nations
			String label;
			int action;

			if (mine.allies.contains(n.id)) {
				label = "Break alliance";
				action = NationActionPayload.BREAK_ALLY;
			} else if (mine.allyRequests.contains(n.id)) {
				label = "Accept alliance";
				action = NationActionPayload.ACCEPT_ALLY;
			} else if (n.allyRequests.contains(mine.id)) {
				label = "Take back alliance offer";
				action = NationActionPayload.BREAK_ALLY;
			} else {
				label = "Propose alliance";
				action = NationActionPayload.PROPOSE_ALLY;
			}

			list.add(new ButtonSpec(label, 150, b -> this.send(NationActionPayload.simple(action, nationId))));
		}

		return list;
	}

	private void initNationView() {
		NationData n = ClientNations.get(this.selected);

		if (n == null) {
			this.contentHeight = 0;
			return;
		}

		UUID me = this.myId();
		NationData mine = ClientNations.myNation();
		boolean isMine = mine != null && mine.id.equals(n.id);
		boolean amLeader = isMine && n.leader.equals(me);
		NationLayout lay = this.layout(n, amLeader);
		this.contentHeight = lay.bottom() - (this.barBottom() + 10);
		int right = this.panelX + this.innerW();

		// leader controls on each member
		if (amLeader) {
			for (int i = 0; i < n.members.size(); i++) {
				NationData.Member m = n.members.get(i);

				if (m.id().equals(me)) {
					continue;
				}

				int rowY = lay.membersY() + 12 + i * lay.memberRowH();
				int by = lay.narrowRows() ? rowY + 13 : rowY;
				int bx = lay.narrowRows() ? this.panelX + 16 : right - 206;
				String target = m.id().toString();
				int rank = n.rankOf(m.id());
				Button up = this.addScrolled(Button.builder(Component.literal("\u25B2"),
								b -> this.send(new NationActionPayload(NationActionPayload.SET_RANK, target, "", rank + 1, "", -1)))
						.pos(bx, by).size(28, 18).build());
				Button down = this.addScrolled(Button.builder(Component.literal("\u25BC"),
								b -> this.send(new NationActionPayload(NationActionPayload.SET_RANK, target, "", rank - 1, "", -1)))
						.pos(bx + 30, by).size(28, 18).build());
				up.active = rank < com.mapnationswars.nation.Ranks.MINISTER;
				down.active = rank > com.mapnationswars.nation.Ranks.CITIZEN;
				this.addScrolled(Button.builder(Component.literal("Make leader"),
								b -> this.send(NationActionPayload.simple(NationActionPayload.PROMOTE, target)))
						.pos(bx + 62, by).size(94, 18).build());
				this.addScrolled(Button.builder(Component.literal("Kick"),
								b -> this.send(NationActionPayload.simple(NationActionPayload.KICK, target)))
						.pos(bx + 160, by).size(40, 18).build());
			}

			int requestRows = n.requests.size() + n.allyRequests.size();

			for (int i = 0; i < requestRows; i++) {
				int rowY = lay.requestsY() + 12 + i * lay.requestRowH();
				int by = lay.narrowRows() ? rowY + 15 : rowY;
				int bx = lay.narrowRows() ? this.panelX + 16 : right - 106;
				boolean joinRequest = i < n.requests.size();
				String target = joinRequest ? n.requests.get(i).id().toString() : n.allyRequests.get(i - n.requests.size()).toString();
				int accept = joinRequest ? NationActionPayload.ACCEPT : NationActionPayload.ACCEPT_ALLY;
				int deny = joinRequest ? NationActionPayload.DENY : NationActionPayload.DECLINE_ALLY;
				this.addScrolled(Button.builder(Component.literal("Accept"),
								b -> this.send(NationActionPayload.simple(accept, target)))
						.pos(bx, by).size(56, 18).build());
				this.addScrolled(Button.builder(Component.literal("Deny"),
								b -> this.send(NationActionPayload.simple(deny, target)))
						.pos(bx + 60, by).size(44, 18).build());
			}

			if (ClientNations.proposalCount(n.id) > 0) {
				int py = lay.proposalsY() + 12;
				this.addScrolled(Button.builder(Component.literal("Annex all"),
								b -> this.send(NationActionPayload.simple(NationActionPayload.ACCEPT_PROPOSALS, "")))
						.pos(this.panelX, py).size(64, 18).build());
				this.addScrolled(Button.builder(Component.literal("Deny all"),
								b -> this.send(NationActionPayload.simple(NationActionPayload.DENY_PROPOSALS, "")))
						.pos(this.panelX + 68, py).size(58, 18).build());
			}
		}

		this.flowButtons(this.bottomButtons(n), this.panelX, lay.buttonsY(), true);
	}

	/** Where everything in the create / edit form goes (already moved by the scroll). */
	private record FormLayout(int titleY, int nameY, int bannerLabelY, int colourLabelY, List<String> colourLabel,
			int paletteColumns, int ideologyLabelY, int ideologyInfoY, boolean ideologyInfoBelow, int buttonsY, int bottom) {
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
		List<String> colourLabel = this.wrap("Colour (each colour can only belong to one nation)", this.innerW() - 10);
		y += colourLabel.size() * 11 + 2;
		int columns = Math.max(4, Math.min(PALETTE_COLUMNS, (this.innerW() - 10) / CELL));
		this.paletteX = this.panelX + 10;
		this.paletteY = y;
		y += ((NationColors.PALETTE.length + columns - 1) / columns) * CELL + 8;
		int ideologyLabelY = y;
		y += 12;
		this.dropdownX = this.panelX + 10;
		this.dropdownY = y;
		y += 20;
		int dropdownW = Math.min(DROPDOWN_W, this.innerW() - 10);
		boolean below = this.innerW() - 10 - dropdownW < 150;
		int ideologyInfoY = below ? y + 4 : this.dropdownY + 6;

		if (below && draftIdeology != null) {
			y += 14;
		}

		y += 8;
		int buttonsY = y;
		y += 24;
		return new FormLayout(titleY, nameY, bannerLabelY, colourLabelY, colourLabel, columns, ideologyLabelY, ideologyInfoY, below,
				buttonsY, y + this.scroll);
	}

	private int paletteColumns = PALETTE_COLUMNS;

	private void initForm() {
		FormLayout lay = this.formLayout();
		this.paletteColumns = lay.paletteColumns();
		this.contentHeight = lay.bottom() - (this.barBottom() + 10);
		int x = this.panelX + 10;

		EditBox nameBox = new EditBox(this.font, x, lay.nameY() + 12, Math.min(220, this.innerW() - 10), 20, null, Component.literal("Nation name"));
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

		Component ideologyLabel = draftIdeology == null
				? Component.literal("Choose your ideology  ▼")
				: Component.literal(draftIdeology.symbol + "  " + draftIdeology.displayName + "  ▼");
		this.addScrolled(Button.builder(ideologyLabel, b -> {
			this.ideologyOpen = !this.ideologyOpen;
			this.dropdownScroll = 0;
		}).pos(this.dropdownX, this.dropdownY).size(Math.min(DROPDOWN_W, this.innerW() - 10), 20).build());

		this.addScrolled(Button.builder(Component.literal(this.editing ? "Save changes" : "Create nation"), b -> this.submitForm())
				.pos(x, lay.buttonsY()).size(120, 20).build());
		this.addScrolled(Button.builder(Component.literal("Cancel"), b -> {
			NationData mine = ClientNations.myNation();
			this.show(mine != null ? View.NATION : View.OVERVIEW, mine != null ? mine.id : null);
		}).pos(x + 124, lay.buttonsY()).size(80, 20).build());
	}
	private void submitForm() {
		String name = draftName.trim();

		if (name.length() < 3) {
			ClientNations.setStatus("The name needs at least 3 letters.", false);
			return;
		}

		if (draftColor < 0) {
			ClientNations.setStatus("Pick a colour for your nation.", false);
			return;
		}

		if (draftIdeology == null) {
			ClientNations.setStatus("Choose your ideology.", false);
			return;
		}

		int bannerSlot;

		if (this.editing) {
			bannerSlot = draftBannerRemoved ? -2 : (draftBannerSlot >= 0 ? draftBannerSlot : -1);
		} else {
			bannerSlot = draftBannerSlot;
		}

		int action = this.editing ? NationActionPayload.EDIT : NationActionPayload.CREATE;
		this.send(new NationActionPayload(action, "", name, draftColor, draftIdeology.name(), bannerSlot));

		if (this.editing) {
			NationData mine = ClientNations.myNation();
			this.show(View.NATION, mine != null ? mine.id : null);
		} else {
			this.waitingForCreate = true;
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

		this.drawFrame(graphics, "Nations (" + ClientNations.all().size() + ")");
		this.drawList(graphics, mouseX, mouseY);

		// the right side scrolls: draw it clipped to its area
		graphics.enableScissor(this.panelX - 4, this.viewTop(), this.right(), this.viewBottom());

		switch (this.view) {
			case OVERVIEW -> this.drawOverview(graphics);
			case NATION -> this.drawNation(graphics, mouseX, mouseY);
			case FORM -> this.drawForm(graphics, mouseX, mouseY);
		}

		graphics.disableScissor();
		this.drawScrollBar(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.nextStratum();
		this.drawStatus(graphics, this.bottom() - 22);

		if (this.view == View.FORM && this.ideologyOpen) {
			this.drawIdeologyDropdown(graphics, mouseX, mouseY);
		}

		if (this.view == View.NATION && this.hoveredAlly != null) {
			NationData ally = this.hoveredAlly;
			new RichTooltip()
					.text(Component.literal(ally.name).withStyle(style -> style.withColor(ally.color).withBold(true)))
					.text(Component.literal(ally.ideology.displayName).withColor(ally.ideology.category.color))
					.player(ally.ideology.leaderTitle + ": ", ally.leader, ally.leaderName(), "")
					.text(Component.literal("Click for details").withColor(0x777777))
					.draw(graphics, this.font, mouseX, mouseY, this.width, this.height);
		}
	}

	private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<NationData> nations = this.sortedNations();

		if (nations.isEmpty()) {
			graphics.text(this.font, "No nations yet.", this.listX + 6, this.listY + 8, 0xFFAAAAAA);
			graphics.text(this.font, "Be the first!", this.listX + 6, this.listY + 20, 0xFFAAAAAA);
			return;
		}

		int visible = (this.listBottom - this.listY) / ROW_H;
		this.listScroll = Math.max(0, Math.min(this.listScroll, nations.size() - visible));
		NationData mine = ClientNations.myNation();

		graphics.enableScissor(this.listX, this.listY, this.listX + this.listW, this.listBottom);

		for (int i = 0; i < nations.size() - this.listScroll && i <= visible; i++) {
			NationData n = nations.get(i + this.listScroll);
			int y = this.listY + i * ROW_H;
			boolean hover = mouseX >= this.listX && mouseX < this.listX + this.listW && mouseY >= y && mouseY < y + ROW_H && mouseY < this.listBottom;
			boolean isSelected = n.id.equals(this.selected) && this.view == View.NATION;

			this.drawListRow(graphics, y, n.color, isSelected, hover);
			int textX = this.listX + 8;

			if (!n.banner.isEmpty()) {
				graphics.item(n.banner, textX, y + 4);
			} else {
				graphics.fill(textX + 3, y + 5, textX + 13, y + 19, 0xFF000000 | n.color);
			}

			textX += 20;
			String name = n.name;

			if (mine != null && mine.id.equals(n.id)) {
				name = "★ " + name;
			}

			graphics.text(this.font, this.fit(name, this.listX + this.listW - textX - 4), textX, y + 4, 0xFF000000 | n.color);

			if (mine != null && mine.allies.contains(n.id)) {
				graphics.text(this.font, "ally", this.listX + this.listW - this.font.width("ally") - 4, y + 14, 0xFF7CFF7C);
			}
			graphics.text(this.font, n.ai ? n.ideology.symbol + " " + n.faction.displayName
					: n.ideology.symbol + " " + n.members.size() + (n.members.size() == 1 ? " member" : " members"),
					textX, y + 14, n.ai ? 0xFF000000 | n.faction.color : 0xFFAAAAAA);
		}

		graphics.disableScissor();

		this.drawListScrollBar(graphics, nations.size(), visible);
	}

	private void drawOverview(GuiGraphicsExtractor graphics) {
		int cx = this.panelX + this.panelW / 2;
		int mid = this.viewTop() + (this.viewBottom() - this.viewTop()) / 2;
		graphics.centeredText(this.font, "You are not part of any nation yet.", cx, mid - 36, 0xFFFFFFFF);
		int y = mid - 22;

		for (String line : this.wrap("Every village of the world belongs to a nation. Click one on the left to see it. Joining nations and founding your own come in later updates.", this.panelW - 16)) {
			graphics.centeredText(this.font, line, cx, y, 0xFFAAAAAA);
			y += 10;
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

	private void drawNation(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		NationData n = ClientNations.get(this.selected);

		if (n == null) {
			return;
		}

		UUID me = this.myId();
		NationData mine = ClientNations.myNation();
		boolean isMine = mine != null && mine.id.equals(n.id);
		boolean amLeader = isMine && n.leader.equals(me);
		NationLayout lay = this.layout(n, amLeader);
		int cx = this.panelX + this.innerW() / 2;
		int x = this.panelX;
		int w = this.innerW();

		// name (smaller if it doesn't fit), then the banner under it
		float nameScale = Math.max(1.0f, Math.min(2.0f, (w - 8) / (float) Math.max(1, this.font.width(n.name))));
		this.drawBigText(graphics, Component.literal(n.name).withColor(n.color), cx, lay.nameY() + 2, nameScale);

		if (!n.banner.isEmpty()) {
			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(cx - 16, lay.bannerY());
			pose.scale(2.0f);
			graphics.item(n.banner, 0, 0);
			pose.popMatrix();
		}

		int y = lay.infoY();
		graphics.text(this.font, Component.literal("Ideology: ").withColor(0xAAAAAA)
				.append(Component.literal(n.ideology.displayName).withColor(n.ideology.category.color)), x, y, 0xFFFFFFFF, true);
		graphics.text(this.font, Component.literal(n.ideology.category.displayName).withColor(0x777777), x + 8, y + 11, 0xFFFFFFFF, true);
		String titleText = n.ideology.symbol + " " + n.ideology.leaderTitle + ": ";
		graphics.text(this.font, Component.literal(titleText).withColor(0xFFD54F), x, y + 24, 0xFFFFFFFF, true);
		int headX = x + this.font.width(titleText);
		Faces.draw(graphics, n.leader, headX, y + 24, 8);
		graphics.text(this.font, Component.literal(this.fit(n.leaderName(), w - (headX + 11 - x))).withColor(n.color), headX + 11, y + 24, 0xFFFFFFFF, true);

		for (int i = 0; i < lay.stats().size(); i++) {
			graphics.text(this.font, lay.stats().get(i), x, y + 36 + i * 11, 0xFFDDDDDD);
		}

		this.drawAllianceBar(graphics, n, x, lay.allyY(), mouseX, mouseY);

		// members, in the order they joined
		this.drawHeading(graphics, "Members (" + n.members.size() + ")", x, lay.membersY(), 0xFFFFD060);
		int nameRoom = amLeader && !lay.narrowRows() ? w - 16 - 210 : w - 16;

		for (int i = 0; i < n.members.size(); i++) {
			NationData.Member m = n.members.get(i);
			int rowY = lay.membersY() + 12 + i * lay.memberRowH();

			if (rowY > this.viewBottom() || rowY + lay.memberRowH() < this.viewTop()) {
				continue;
			}

			boolean isLeader = m.id().equals(n.leader);
			int textY = rowY + (amLeader && !lay.narrowRows() ? 5 : 1);
			Faces.draw(graphics, m.id(), x + 4, textY, 8);
			String name = this.fit(m.name(), Math.max(30, nameRoom - 70));
			Component label;

			if (isLeader) {
				label = Component.literal(n.ideology.symbol + " ").withColor(0xFFD54F)
						.append(Component.literal(name).withColor(n.color))
						.append(Component.literal("  (" + n.ideology.leaderTitle + ")").withColor(0xFFD54F));
			} else if (n.isOfficer(m.id())) {
				label = Component.literal("\u2726 ").withColor(0xC0C8FF)
						.append(Component.literal(name).withColor(n.color))
						.append(Component.literal("  (" + com.mapnationswars.nation.Ranks.name(n.rankOf(m.id())) + ")").withColor(0xC0C8FF));
			} else {
				label = Component.literal(name).withColor(n.color)
						.append(Component.literal("  (" + com.mapnationswars.nation.Ranks.name(n.rankOf(m.id())) + ")").withColor(0x999999));
			}

			graphics.text(this.font, label, x + 16, textY, 0xFFFFFFFF, true);
		}

		// join requests and alliance offers (leader only)
		int requestCount = n.requests.size() + n.allyRequests.size();

		if (amLeader && requestCount > 0) {
			this.drawHeading(graphics, "Requests (" + requestCount + ")", x, lay.requestsY(), 0xFF9CFF9C);
			int textRoom = lay.narrowRows() ? w - 20 : w - 20 - 110;

			for (int i = 0; i < requestCount; i++) {
				int ry = lay.requestsY() + 12 + i * lay.requestRowH() + (lay.narrowRows() ? 2 : 5);

				if (i < n.requests.size()) {
					NationData.Member r = n.requests.get(i);
					Faces.draw(graphics, r.id(), x + 4, ry, 8);
					graphics.text(this.font, Component.literal(this.fit(r.name(), Math.max(30, textRoom - 80))).withColor(0xFFFFFF)
							.append(Component.literal(" wants to join").withColor(0x999999)), x + 16, ry, 0xFFFFFFFF, true);
				} else {
					NationData other = ClientNations.get(n.allyRequests.get(i - n.requests.size()));

					if (other != null) {
						this.drawNationIcon(graphics, other, x + 2, ry - 4);
						graphics.text(this.font, Component.literal(this.fit(other.name, Math.max(30, textRoom - 110))).withColor(other.color)
								.append(Component.literal(" offers an alliance").withColor(0x999999)), x + 20, ry, 0xFFFFFFFF, true);
					}
				}
			}
		}

		int proposals = ClientNations.proposalCount(n.id);

		if (amLeader && proposals > 0) {
			graphics.text(this.font, Component.literal(this.fit(proposals + " chunks of land proposed by officers", w)).withColor(0x9CFF9C),
					x, lay.proposalsY(), 0xFFFFFFFF, true);
		}

		if (!isMine && (mine != null || n.hasRequest(me))) {
			this.drawWrapped(graphics, this.noteText(n, mine), x, lay.noteY(), w, mine != null ? 0xFFFF9C9C : 0xFFAAAAAA);
		}
	}

	/** A nation's banner (or a flag in its colour if it has none), 16x16. */
	private void drawNationIcon(GuiGraphicsExtractor graphics, NationData n, int x, int y) {
		if (!n.banner.isEmpty()) {
			graphics.item(n.banner, x, y);
		} else {
			graphics.fill(x + 2, y + 1, x + 14, y + 15, 0xFF1B1B1B);
			graphics.fill(x + 3, y + 2, x + 13, y + 14, 0xFF000000 | n.color);
		}
	}

	private int allianceBarX() {
		return this.panelX + this.font.width("Allies: ") + 2;
	}

	/** The ally under the mouse in the alliance bar, or null. */
	private UUID allyAt(NationData n, int barY, double mouseX, double mouseY) {
		int bx = this.allianceBarX();

		for (int i = 0; i < n.allies.size(); i++) {
			int ix = bx + (i % this.alliesPerRow()) * 20;
			int iy = barY + (i / this.alliesPerRow()) * 20;

			if (mouseX >= ix && mouseX < ix + 18 && mouseY >= iy && mouseY < iy + 18) {
				return n.allies.get(i);
			}
		}

		return null;
	}

	/** "Allies:" followed by the banners of allied nations. Hover shows the name, click opens them. */
	private void drawAllianceBar(GuiGraphicsExtractor graphics, NationData n, int x, int barY, int mouseX, int mouseY) {
		graphics.text(this.font, "Allies:", x, barY + 5, 0xFFFFD060);
		this.hoveredAlly = null;

		if (n.allies.isEmpty()) {
			graphics.text(this.font, "none yet", this.allianceBarX(), barY + 5, 0xFF777777);
			return;
		}

		int bx = this.allianceBarX();
		UUID hovered = mouseY >= this.viewTop() && mouseY <= this.viewBottom() ? this.allyAt(n, barY, mouseX, mouseY) : null;

		for (int i = 0; i < n.allies.size(); i++) {
			NationData ally = ClientNations.get(n.allies.get(i));

			if (ally == null) {
				continue;
			}

			int ix = bx + (i % this.alliesPerRow()) * 20;
			int iy = barY + (i / this.alliesPerRow()) * 20;

			if (ally.id.equals(hovered)) {
				graphics.fill(ix - 1, iy - 1, ix + 19, iy + 19, 0x60FFFFFF);
				this.hoveredAlly = ally;
			}

			this.drawNationIcon(graphics, ally, ix + 1, iy + 1);
		}
	}

	private void drawForm(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		FormLayout lay = this.formLayout();
		int x = this.panelX + 10;
		int w = this.innerW() - 10;
		int cx = this.panelX + this.innerW() / 2;

		this.drawBigText(graphics, Component.literal(this.editing ? "Edit Your Nation" : "Create Your Nation"), cx, lay.titleY(), 1.5f);
		graphics.text(this.font, "Name", x, lay.nameY(), 0xFFFFD060);

		// banner slot
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
			List<String> hint = this.wrap("Click the slot to put a banner from your inventory", w - 32);
			int hy = sy + 13 - hint.size() * 11 / 2;

			for (int i = 0; i < hint.size(); i++) {
				graphics.text(this.font, hint.get(i), sx + 32, hy + i * 11, 0xFFAAAAAA);
			}
		}

		// colours
		for (int i = 0; i < lay.colourLabel().size(); i++) {
			graphics.text(this.font, lay.colourLabel().get(i), x, lay.colourLabelY() + i * 11, 0xFFFFD060);
		}

		Set<Integer> used = ClientNations.usedColors(this.editing ? this.selected : null);

		for (int i = 0; i < NationColors.PALETTE.length; i++) {
			int color = NationColors.PALETTE[i];
			int px = this.paletteX + (i % this.paletteColumns) * CELL;
			int py = this.paletteY + (i / this.paletteColumns) * CELL;
			boolean taken = used.contains(color);
			boolean chosen = color == draftColor;

			graphics.fill(px, py, px + CELL - 2, py + CELL - 2, chosen ? 0xFFFFFFFF : 0xFF000000);
			graphics.fill(px + 1, py + 1, px + CELL - 3, py + CELL - 3, 0xFF000000 | color);

			if (taken) {
				graphics.fill(px + 1, py + 1, px + CELL - 3, py + CELL - 3, 0xB0000000);
				graphics.text(this.font, "x", px + 5, py + 3, 0xFFFF5555);
			}
		}

		graphics.text(this.font, "Ideology", x, lay.ideologyLabelY(), 0xFFFFD060);

		if (draftIdeology != null) {
			int ix = lay.ideologyInfoBelow() ? x : x + Math.min(DROPDOWN_W, w) + 8;
			graphics.text(this.font, Component.literal(this.fit(draftIdeology.category.displayName + " - " + draftIdeology.leaderTitle + " leads",
					this.panelX + this.innerW() - ix)).withColor(draftIdeology.category.color), ix, lay.ideologyInfoY(), 0xFFFFFFFF, true);
		}

		// tooltips for colour squares
		int hovered = this.paletteIndexAt(mouseX, mouseY);

		if (hovered >= 0 && used.contains(NationColors.PALETTE[hovered]) && !this.ideologyOpen) {
			graphics.setTooltipForNextFrame(this.font, Component.literal("Already taken by another nation"), mouseX, mouseY);
		}
	}

	// ---------------------------------------------------------------- ideology dropdown

	private record DropdownRow(Ideology.Category header, Ideology ideology) {
	}

	private List<DropdownRow> dropdownRows() {
		List<DropdownRow> rows = new ArrayList<>();

		for (Ideology.Category cat : Ideology.Category.values()) {
			rows.add(new DropdownRow(cat, null));

			for (Ideology i : Ideology.values()) {
				if (i.category == cat) {
					rows.add(new DropdownRow(null, i));
				}
			}
		}

		return rows;
	}

	/** How many rows of the ideology list fit on the screen. */
	private int dropdownVisibleRows(int rowCount) {
		return Math.max(3, Math.min(rowCount, (this.bottom() - 8 - (this.barBottom() + 2)) / DROPDOWN_ROW));
	}

	private int dropdownTop(int rowCount) {
		int h = this.dropdownVisibleRows(rowCount) * DROPDOWN_ROW + 4;
		int top = this.dropdownY + 20;

		if (top + h > this.bottom() - 4) {
			top = Math.max(this.barBottom() + 2, this.bottom() - 4 - h);
		}

		return top;
	}

	private int dropdownW() {
		return Math.min(DROPDOWN_W, Math.max(160, this.right() - this.dropdownX - 4));
	}

	private void drawIdeologyDropdown(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<DropdownRow> rows = this.dropdownRows();
		int visible = this.dropdownVisibleRows(rows.size());
		this.dropdownScroll = Math.max(0, Math.min(this.dropdownScroll, rows.size() - visible));
		int top = this.dropdownTop(rows.size());
		int x = this.dropdownX;
		int w = this.dropdownW();

		graphics.nextStratum();
		graphics.fill(x - 1, top - 1, x + w + 1, top + visible * DROPDOWN_ROW + 5, 0xFFFFFFFF);
		graphics.fill(x, top, x + w, top + visible * DROPDOWN_ROW + 4, 0xF0101010);

		Ideology hovered = null;

		for (int v = 0; v < visible; v++) {
			DropdownRow row = rows.get(v + this.dropdownScroll);
			int y = top + 2 + v * DROPDOWN_ROW;

			if (row.header() != null) {
				graphics.text(this.font, Component.literal(row.header().displayName).withStyle(style -> style.withColor(row.header().color).withBold(true)),
						x + 4, y + 1, 0xFFFFFFFF, true);
			} else {
				boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + DROPDOWN_ROW;

				if (hover) {
					graphics.fill(x + 1, y, x + w - 1, y + DROPDOWN_ROW, 0x50FFFFFF);
					hovered = row.ideology();
				}

				boolean chosen = row.ideology() == draftIdeology;
				graphics.text(this.font, "  " + row.ideology().symbol + " " + row.ideology().displayName, x + 6, y + 1,
						chosen ? 0xFFFFD54F : 0xFFFFFFFF);
			}
		}

		// scroll bar when the list is longer than the screen
		if (rows.size() > visible) {
			int trackH = visible * DROPDOWN_ROW + 4;
			int barH = Math.max(10, trackH * visible / rows.size());
			int barY = top + (trackH - barH) * this.dropdownScroll / Math.max(1, rows.size() - visible);
			graphics.fill(x + w - 3, top, x + w, top + trackH, 0x40FFFFFF);
			graphics.fill(x + w - 3, barY, x + w, barY + barH, 0xC0FFFFFF);
		}

		if (hovered != null) {
			List<Component> tip = new ArrayList<>();
			tip.add(Component.literal(hovered.displayName).withColor(hovered.category.color));
			tip.add(Component.literal(hovered.description).withColor(0xDDDDDD));
			tip.add(Component.literal("Leader title: " + hovered.symbol + " " + hovered.leaderTitle).withColor(0xFFD54F));
			graphics.setComponentTooltipForNextFrame(this.font, tip, mouseX, mouseY, (Identifier) null);
		}
	}

	// ---------------------------------------------------------------- input

	private int paletteIndexAt(double mouseX, double mouseY) {
		if (mouseX < this.paletteX || mouseY < this.paletteY) {
			return -1;
		}

		int col = (int) ((mouseX - this.paletteX) / CELL);
		int row = (int) ((mouseY - this.paletteY) / CELL);

		if (col >= this.paletteColumns || row < 0 || mouseY < this.viewTop() || mouseY > this.viewBottom()) {
			return -1;
		}

		int index = row * this.paletteColumns + col;
		return index < NationColors.PALETTE.length ? index : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
		double mx = click.x();
		double my = click.y();

		// the open dropdown gets the click first
		if (this.view == View.FORM && this.ideologyOpen) {
			List<DropdownRow> rows = this.dropdownRows();
			int top = this.dropdownTop(rows.size());

			int visible = this.dropdownVisibleRows(rows.size());

			if (mx >= this.dropdownX && mx < this.dropdownX + this.dropdownW() && my >= top + 2 && my < top + 2 + visible * DROPDOWN_ROW) {
				int index = (int) ((my - top - 2) / DROPDOWN_ROW) + this.dropdownScroll;

				if (index >= 0 && index < rows.size() && rows.get(index).ideology() != null) {
					draftIdeology = rows.get(index).ideology();
					this.ideologyOpen = false;
					this.rebuildWidgets();
					return true;
				}
			}

			this.ideologyOpen = false;
			return true;
		}

		if (super.mouseClicked(click, doubleClick)) {
			return true;
		}

		// alliance bar -> open that nation
		if (this.view == View.NATION) {
			NationData shown = ClientNations.get(this.selected);

			if (shown != null) {
				NationData mine = ClientNations.myNation();
				boolean leaderView = mine != null && mine.id.equals(shown.id) && shown.leader.equals(this.myId());
				UUID ally = my >= this.viewTop() && my <= this.viewBottom()
						? this.allyAt(shown, this.layout(shown, leaderView).allyY(), mx, my) : null;

				if (ally != null && ClientNations.get(ally) != null) {
					this.show(View.NATION, ally);
					return true;
				}
			}
		}

		// nation list
		if (mx >= this.listX && mx < this.listX + this.listW && my >= this.listY && my < this.listBottom) {
			int index = (int) ((my - this.listY) / ROW_H) + this.listScroll;
			List<NationData> nations = this.sortedNations();

			if (index >= 0 && index < nations.size()) {
				this.show(View.NATION, nations.get(index).id);
			}

			return true;
		}

		if (this.view == View.FORM) {
			// banner slot -> open the inventory picker
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

				if (ClientNations.usedColors(this.editing ? this.selected : null).contains(color)) {
					ClientNations.setStatus("That colour already belongs to another nation.", false);
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
		return ClientNations.all().size();
	}

	@Override
	protected boolean scrollOther(double verticalAmount) {
		if (this.view == View.FORM && this.ideologyOpen) {
			this.dropdownScroll = Math.max(0, this.dropdownScroll - (int) Math.signum(verticalAmount) * 2);
			return true;
		}

		return false;
	}
}
