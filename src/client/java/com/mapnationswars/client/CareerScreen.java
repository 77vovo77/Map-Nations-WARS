package com.mapnationswars.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mapnationswars.nation.Charter;
import com.mapnationswars.nation.DutyData;
import com.mapnationswars.nation.LetterData;
import com.mapnationswars.nation.MarkerData;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.nation.Ranks;
import com.mapnationswars.nation.Relations;
import com.mapnationswars.network.NationActionPayload;
import com.mapnationswars.network.PersonalActionPayload;
import com.mapnationswars.network.PersonalSyncPayload;

/**
 * The "You" tab (Map Nations WARS 1.9): everything one player can do, explained step by step,
 * with your progress and a button for every action - joining, duties, ranks, elections, coups, letters,
 * revolts, founding your own nation, personal wars and land.
 */
public class CareerScreen extends PagedScreen {
	private enum Section {
		START("Start here"),
		DUTIES("Duties"),
		RANK("Rank & merit"),
		POWER("Elections & coups"),
		LETTERS("Letters"),
		REVOLT("Revolts"),
		FOUND("Your own nation"),
		WARS("Personal wars"),
		TREASURY("Treasury"),
		LAND("Land"),
		ARMY("Armies");

		final String label;

		Section(String label) {
			this.label = label;
		}
	}

	private static Section section = Section.START;
	private boolean coupArmed = false;
	private int seen = -1;

	// the page is laid out twice the same way: once to place the buttons (init), once to draw (render)
	private boolean drawing;
	private GuiGraphicsExtractor g;
	private int y;
	private int top;

	public CareerScreen() {
		super(Tab.YOU);
	}

	private UUID me() {
		return this.minecraft != null && this.minecraft.player != null ? this.minecraft.player.getUUID() : new UUID(0, 0);
	}

	private int stamp() {
		return ClientNations.version() * 31 + ClientPersonal.version() * 17 + ClientDiplomacy.version() * 7 + ClientWar.version();
	}

	private int carried() {
		int count = 0;

		if (this.minecraft != null && this.minecraft.player != null) {
			for (ItemStack stack : this.minecraft.player.getInventory().getNonEquipmentItems()) {
				if (stack.is(Items.EMERALD)) {
					count += stack.getCount();
				}
			}
		}

		return count;
	}

	private void nationAction(int action, String target) {
		if (ClientPlayNetworking.canSend(NationActionPayload.TYPE)) {
			ClientPlayNetworking.send(NationActionPayload.simple(action, target));
		}
	}

	private void personalAction(int action, String target) {
		if (ClientPlayNetworking.canSend(PersonalActionPayload.TYPE)) {
			ClientPlayNetworking.send(new PersonalActionPayload(action, target));
		}
	}

	private void open(net.minecraft.client.gui.screens.Screen s) {
		this.minecraft.gui.setScreen(s);
	}

	// ---------------------------------------------------------------- the page builder

	private void text(String s, int color) {
		for (String line : this.wrap(s, this.innerW())) {
			if (this.drawing) {
				this.g.text(this.font, line, this.panelX, this.y, color);
			}

			this.y += 11;
		}
	}

	private void heading(String s) {
		this.gap(4);

		if (this.drawing) {
			this.drawHeading(this.g, s, this.panelX, this.y, 0xFFFFD060);
		}

		this.y += 14;
	}

	private void title(String s, int color) {
		if (this.drawing) {
			this.g.text(this.font, Component.literal(s).withStyle(st -> st.withColor(color).withBold(true)), this.panelX, this.y, 0xFFFFFFFF, true);
		}

		this.y += 14;
	}

	/** A checklist line: ✔ done / ✖ not yet. */
	private void check(boolean done, String s) {
		this.text((done ? "✔ " : "✖ ") + s, done ? 0xFF7CFF7C : 0xFFFF8A80);
	}

	private void bar(String label, String value, double fraction, int color) {
		if (this.drawing) {
			int x = this.panelX;
			this.g.text(this.font, label, x, this.y, 0xFFDDDDDD);
			int bx = x + Math.min(90, this.font.width(label) + 8);
			int bw = Math.max(40, this.innerW() - (bx - x) - this.font.width(value) - 8);
			this.g.fill(bx, this.y + 1, bx + bw, this.y + 8, 0xFF2A2F36);
			this.g.fill(bx, this.y + 1, bx + (int) (bw * Math.max(0, Math.min(1, fraction))), this.y + 8, color);
			this.g.text(this.font, value, bx + bw + 6, this.y, 0xFFDDDDDD);
		}

		this.y += 13;
	}

	private void buttons(List<ButtonSpec> specs) {
		if (specs.isEmpty()) {
			return;
		}

		this.gap(2);
		this.y += this.flowButtons(specs, this.panelX, this.y, !this.drawing);
	}

	private void gap(int px) {
		this.y += px;
	}

	private ButtonSpec button(String label, Runnable action) {
		return new ButtonSpec(label, this.font.width(label) + 16, b -> action.run());
	}

	// ---------------------------------------------------------------- widgets and drawing

	@Override
	protected void init() {
		this.addTabs();
		this.layoutFrame();
		this.seen = this.stamp();
		this.drawing = false;
		this.build();
		this.clampScroll();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (this.stamp() != this.seen) {
			this.rebuildWidgets();
		}

		this.drawFrame(graphics, "What you can do");
		this.drawSections(graphics, mouseX, mouseY);
		graphics.enableScissor(this.panelX - 4, this.viewTop(), this.right(), this.viewBottom());
		this.drawing = true;
		this.g = graphics;
		this.build();
		this.g = null;
		graphics.disableScissor();
		this.drawScrollBar(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.nextStratum();
		this.drawStatus(graphics, this.bottom() - 22);
	}

	private void build() {
		this.top = this.barBottom() + 10 - this.scroll;
		this.y = this.top;

		switch (section) {
			case START -> this.start();
			case DUTIES -> this.duties();
			case RANK -> this.rank();
			case POWER -> this.power();
			case LETTERS -> this.letters();
			case REVOLT -> this.revolt();
			case FOUND -> this.found();
			case WARS -> this.wars();
			case TREASURY -> this.treasury();
			case LAND -> this.land();
			case ARMY -> this.army();
		}

		this.contentHeight = this.y - this.top + 10;
	}

	private void drawSections(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Section[] all = Section.values();
		int visible = (this.listBottom - this.listY) / ROW_H;
		this.listScroll = Math.max(0, Math.min(this.listScroll, all.length - visible));
		graphics.enableScissor(this.listX, this.listY, this.listX + this.listW, this.listBottom);

		for (int i = 0; i < all.length - this.listScroll && i <= visible; i++) {
			Section s = all[i + this.listScroll];
			int ry = this.listY + i * ROW_H;
			boolean hover = mouseX >= this.listX && mouseX < this.listX + this.listW && mouseY >= ry && mouseY < ry + ROW_H && mouseY < this.listBottom;
			this.drawListRow(graphics, ry, s == section ? 0xFFD54F : 0x5A6270, s == section, hover);
			graphics.text(this.font, this.fit(s.label, this.listW - 14), this.listX + 8, ry + 3, 0xFFFFFFFF);
			String hint = this.hint(s);
			graphics.text(this.font, this.fit(hint, this.listW - 14), this.listX + 8, ry + 13, 0xFF9AA0A6);
		}

		graphics.disableScissor();
		this.drawListScrollBar(graphics, all.length, visible);
	}

	/** A short state under each section's name. */
	private String hint(Section s) {
		NationData mine = ClientNations.myNation();
		UUID me = this.me();

		return switch (s) {
			case START -> mine == null ? "no nation yet" : mine.name;
			case DUTIES -> ClientPersonal.duties().size() + " active";
			case RANK -> mine == null ? "-" : (mine.leader.equals(me) ? mine.ideology.leaderTitle : Ranks.name(mine.rankOf(me))) + ", merit " + mine.merit.getOrDefault(me, 0);
			case POWER -> mine != null && Ranks.hasElections(mine.ideology) ? "elections" : "coups";
			case LETTERS -> ClientDiplomacy.letters().size() + " letters";
			case REVOLT -> ClientRevolts.all().size() + " villages know you";
			case FOUND -> "the charter";
			case WARS -> {
				int wars = 0;

				for (PersonalSyncPayload.Standing st : ClientPersonal.standings()) {
					wars += st.war() ? 1 : 0;
				}

				yield wars == 0 ? "at peace" : wars + " personal war" + (wars == 1 ? "" : "s");
			}
			case TREASURY -> mine == null ? "-" : mine.treasury + " emeralds";
			case LAND -> "claiming land";
			case ARMY -> ClientWar.of(mine != null ? mine.id : new UUID(0, 0)).size() + " armies";
		};
	}

	// ---------------------------------------------------------------- sections

	private void start() {
		NationData mine = ClientNations.myNation();
		UUID me = this.me();

		if (mine == null) {
			this.title("You belong to no nation", 0xFFFFE0A0);
			this.text("Everyone starts alone. Choose your road:", 0xFFBBBBBB);
			this.heading("Road 1: serve a nation and rise");
			this.check(false, "1. Join a nation (below). Villager kingdoms take almost anyone; illagers and piglins want gifts first.");
			this.text("2. Do duties (Duties) to earn merit.   3. Merit makes you Soldier, Officer, Minister.   4. Win an election or stage a coup to rule.", 0xFFBBBBBB);
			this.heading("Road 2: your own nation");
			this.text("Do duties for a village (it then backs you), stir up its people against their rulers (Revolts) - when it rises up, you lead it. "
					+ "Or pay a charter and found your nation there (Your own nation).", 0xFFBBBBBB);
			this.heading("Nations near you");
			List<ButtonSpec> join = new ArrayList<>();

			for (NationData n : this.nearestNations(5)) {
				int o = ClientPersonal.standing(n.id);
				String need = switch (n.faction) {
					case UNDEAD -> "never takes the living";
					case ILLAGER -> "wants opinion 20";
					case PIGLIN -> "wants opinion 30";
					default -> n.aiRuled() ? "takes you in" : "its leader decides";
				};
				this.text(n.name + "  -  " + n.faction.displayName + ", thinks of you " + (o > 0 ? "+" : "") + o + " (" + need + ")", 0xFF000000 | n.color);

				if (n.faction != com.mapnationswars.nation.Faction.UNDEAD) {
					join.add(this.button("Join " + this.fit(n.name, 90), () -> this.nationAction(NationActionPayload.REQUEST_JOIN, n.id.toString())));
				}
			}

			this.buttons(join);
			this.buttons(List.of(this.button("✉ Send a gift / letter", () -> this.open(new LettersScreen(null, LetterData.Type.GIFT))),
					this.button("Nations tab", () -> this.open(new NationsScreen()))));
		} else {
			boolean leader = mine.leader.equals(me);
			int rank = mine.rankOf(me);
			this.title((leader ? mine.ideology.leaderTitle : Ranks.name(rank)) + " of " + mine.name, 0xFF000000 | mine.color);
			this.text("Merit " + mine.merit.getOrDefault(me, 0) + "   Salary waiting: " + mine.owed.getOrDefault(me, 0)
					+ " emeralds (collect at any mayor of your nation)", 0xFFBBBBBB);
			this.heading("Your road");
			this.check(true, "Joined " + mine.name);
			this.check(!ClientPersonal.duties().isEmpty() || rank > 0, "Do duties for merit (Duties)");
			this.check(rank >= Ranks.SOLDIER || leader, "Soldier (" + Ranks.MERIT[1] + " merit)");
			this.check(rank >= Ranks.OFFICER || leader, "Officer (" + Ranks.MERIT[2] + " merit): command armies, run in elections, coups");
			this.check(rank >= Ranks.MINISTER || leader, "Minister (" + Ranks.MERIT[3] + " merit): order buildings, raise armies, write for the nation, claim land");
			this.check(leader, "Lead the nation: elections or a coup (Elections & coups)");
			this.buttons(List.of(this.button("My duties", () -> this.show(Section.DUTIES)),
					this.button("My nation", () -> this.open(new NationsScreen(mine.id))),
					this.button("✉ Write a letter", () -> this.open(new LettersScreen(null)))));
		}
	}

	private void duties() {
		NationData mine = ClientNations.myNation();
		this.title("Duties", 0xFF9CE0FF);
		this.text(mine != null ? "Your nation gives you small tasks. Doing them earns merit (and sometimes pay). New ones come every few minutes."
				: "Villages near you ask strangers for help. Doing it makes their people back you (support) and their nation like you.", 0xFFBBBBBB);
		this.gap(4);
		List<DutyData> list = ClientPersonal.duties();

		if (list.isEmpty()) {
			this.text("No duties right now.", 0xFF888888);
		}

		for (DutyData d : list) {
			this.text(d.type.displayName + ": " + d.text, 0xFFFFFFFF);
			this.bar("Progress", d.progress + "/" + d.needed, d.progress / (double) Math.max(1, d.needed), 0xFF5FD35F);
			this.text("Reward: " + d.reward(), 0xFF9CFF9C);
			this.buttons(List.of(this.button("Abandon", () -> this.personalAction(PersonalActionPayload.ABANDON_DUTY, d.id.toString()))));
			this.gap(4);
		}

		this.buttons(List.of(this.button("⚑ Ask for a new duty", () -> this.personalAction(PersonalActionPayload.NEW_DUTY, ""))));
		this.text("Up to 3 at a time. Visits count when you are near the place; patrols while you walk your nation's land; "
				+ "emeralds are given at the mayor (Give buttons); monster kills count where the duty says.", 0xFF888888);
	}

	private void rank() {
		NationData mine = ClientNations.myNation();
		UUID me = this.me();
		this.title("Rank & merit", 0xFFFFE0A0);

		if (mine == null) {
			this.text("Join a nation first (Start here). Ranks: Citizen, Soldier, Officer, Minister - and the leader.", 0xFFBBBBBB);
			return;
		}

		int rank = mine.rankOf(me);
		int merit = mine.merit.getOrDefault(me, 0);
		boolean leader = mine.leader.equals(me);
		this.text("You are " + (leader ? "the " + mine.ideology.leaderTitle : "a " + Ranks.name(rank)) + " of " + mine.name + ".", 0xFFFFFFFF);

		if (!leader && rank + 1 < Ranks.NAMES.length) {
			int need = Ranks.MERIT[rank + 1];
			this.bar("Merit", merit + "/" + need + " for " + Ranks.name(rank + 1), merit / (double) need, 0xFFE8D060);
			this.text(mine.aiRuled() ? "Your nation promotes you by itself when you have the merit. You can also ask:"
					: "A player leads your nation: they promote you by hand. Ask them:", 0xFFBBBBBB);
			this.buttons(List.of(this.button("✉ Ask for promotion", () -> this.open(new LettersScreen(mine.id, LetterData.Type.PROMOTION)))));
		} else {
			this.text("Merit " + merit + ". You are at the top.", 0xFFBBBBBB);
		}

		int salary = leader ? Ranks.LEADER_SALARY : Ranks.SALARY[Math.min(3, rank)];
		this.text("Salary: " + salary + " emeralds a day, waiting: " + mine.owed.getOrDefault(me, 0) + " (collect at any mayor of your nation).", 0xFF9CFF9C);
		this.heading("How to earn merit");
		this.text("• Duties: +5 to +20 each (Duties)", 0xFFDDDDDD);
		this.text("• Being in your nation's land: +1 every 5 minutes", 0xFFDDDDDD);
		this.text("• Killing monsters in your land: +2", 0xFFDDDDDD);
		this.text("• Giving emeralds to your villages (their mayor): +1 per emerald", 0xFFDDDDDD);
		this.text("• Killing enemy soldiers and guards: +3, taking a province: +20", 0xFFDDDDDD);
		this.heading("What each rank can do");
		this.text("Soldier: paid 2 a day.  Officer: command armies on the map, run in elections, attempt a coup.  "
				+ "Minister: order buildings, raise armies, use the treasury, write letters for the nation, claim land.", 0xFFBBBBBB);
	}

	private void power() {
		NationData mine = ClientNations.myNation();
		UUID me = this.me();
		this.title("Elections & coups", 0xFFFFE0A0);

		if (mine == null) {
			this.text("Join a nation first. To rule without one, see Revolts and Your own nation.", 0xFFBBBBBB);
			return;
		}

		boolean leader = mine.leader.equals(me);
		int rank = mine.rankOf(me);
		this.text("Ruler: " + mine.leaderName() + " (" + mine.ideology.leaderTitle + ")" + (leader ? " - that's you!" : ""), 0xFFFFFFFF);
		this.heading("Elections");

		if (Ranks.hasElections(mine.ideology)) {
			long day = this.minecraft.level != null ? this.minecraft.level.getGameTime() / 24000 : 0;
			this.text("Every " + Ranks.ELECTION_DAYS + " days the villages vote. Next: " + (mine.nextElection == 0 ? "soon" : "in " + Math.max(0, mine.nextElection - day) + " days")
					+ ". Villages vote for merit, the ruler for happy villages.", 0xFFBBBBBB);

			if (!mine.lastElection.isEmpty()) {
				this.text("Last time: " + mine.lastElection, 0xFF9AA0A6);
			}

			if (leader || rank >= Ranks.OFFICER) {
				this.buttons(List.of(mine.candidates.contains(me)
						? this.button("Withdraw candidacy", () -> this.nationAction(NationActionPayload.WITHDRAW_CANDIDACY, ""))
						: this.button("★ Run for " + mine.ideology.leaderTitle, () -> this.nationAction(NationActionPayload.RUN_FOR_OFFICE, ""))));
			} else {
				this.check(false, "Officers and up can run (you need " + Ranks.MERIT[Ranks.OFFICER] + " merit)");
			}
		} else {
			this.text(mine.ideology.displayName + " has no elections. Power is taken - by a coup.", 0xFFBBBBBB);
		}

		this.heading("Coup");

		if (leader) {
			this.text("You rule. Keep your villages happy - if they stay miserable for 3 days, the people overthrow you.", 0xFFBBBBBB);
			return;
		}

		int merit = mine.merit.getOrDefault(me, 0);
		int carried = this.carried();
		double avg = this.averageHappiness(mine);
		int pct = (int) Math.round(Charter.coupChance(mine, me, avg, !mine.aiRuled()) * 100);
		this.check(rank >= Ranks.OFFICER, "Be an Officer or Minister");
		this.check(merit >= Charter.COUP_MERIT, "Merit " + merit + "/" + Charter.COUP_MERIT);
		this.check(carried >= Charter.COUP_COST, "Carry " + Charter.COUP_COST + " emeralds for bribes (you carry " + carried + ")");
		this.text("Chance now: ~" + pct + "% (higher when the villages are unhappy: now " + (int) avg + "%). Failing means exile.", 0xFFFFC070);

		if (rank >= Ranks.OFFICER && merit >= Charter.COUP_MERIT && carried >= Charter.COUP_COST) {
			this.buttons(List.of(this.button(this.coupArmed ? "♛ Really? Click again!" : "♛ Attempt a coup", () -> {
				if (this.coupArmed) {
					this.coupArmed = false;
					this.nationAction(NationActionPayload.COUP, "");
				} else {
					this.coupArmed = true;
				}

				this.rebuildWidgets();
			})));
		}
	}

	private void letters() {
		NationData mine = ClientNations.myNation();
		boolean forNation = mine != null && (mine.leader.equals(this.me()) || mine.rankOf(this.me()) >= Ranks.MINISTER);
		this.title("Letters", 0xFFFFE0A0);
		this.text("Anyone can write to any nation, as themselves:", 0xFFBBBBBB);
		this.text("• Message - a few kind words.   • Gift - emeralds you carry; they like you more.", 0xFFDDDDDD);
		this.text("• Ask to join.   • Ask for promotion (to your own nation).", 0xFFDDDDDD);
		this.text("• Declaration of war - your OWN war: their village guards hunt you.   • Peace - offer emeralds to end it.", 0xFFDDDDDD);

		if (forNation) {
			this.text("As a " + (mine.leader.equals(this.me()) ? "leader" : "Minister") + " you can also write for " + mine.name
					+ ": trade, alliances, tribute, wars and peace between nations.", 0xFF9CE0FF);
		}

		this.text("Nations ruled by the game answer at once; players answer in their Letters tab.", 0xFF9AA0A6);
		this.buttons(List.of(this.button("✎ Write a letter", () -> this.open(new LettersScreen(null))),
				this.button("✉ Give a gift", () -> this.open(new LettersScreen(null, LetterData.Type.GIFT))),
				this.button("Letters tab", () -> this.open(new LettersScreen()))));
	}

	private void revolt() {
		this.title("Revolts", 0xFFFF8A65);
		this.text("Every village has unrest. Hunger, misery, foreign rulers, long wars and nations that are too big raise it; "
				+ "at 100% the village rises up and declares itself free.", 0xFFBBBBBB);
		this.heading("How to start one");
		this.text("1. Pick a village of another nation (not a capital).", 0xFFDDDDDD);
		this.text("2. Make its people back you: do duties for it, give its mayor emeralds, kill monsters there. You need 20 support.", 0xFFDDDDDD);
		this.text("3. Talk to its mayor and press 🔥 Stir unrest (10 emeralds, +12 unrest). Their guards may catch you!", 0xFFDDDDDD);
		this.text("4. At 100% it rises up. If you have no nation and 60+ support, YOU lead the new nation.", 0xFFDDDDDD);
		this.heading("Villages that know you");
		Map<UUID, Integer> support = ClientRevolts.all();

		if (support.isEmpty()) {
			this.text("None yet. Do duties for a village (Duties) when you have no nation.", 0xFF888888);
		}

		for (Map.Entry<UUID, Integer> e : support.entrySet()) {
			ProvinceData p = ClientMarkers.province(e.getKey());

			if (p != null) {
				this.bar(this.fit(p.name, 90), "support " + e.getValue() + ", unrest " + p.unrest + "%", p.unrest / 100.0, 0xFFFF7040);
			}
		}

		this.heading("Most restless villages");
		List<ProvinceData> restless = new ArrayList<>();

		for (MarkerData m : ClientMarkers.all()) {
			if (m.province != null && !m.province.abandoned && m.province.unrest > 0) {
				restless.add(m.province);
			}
		}

		restless.sort((a, b) -> Integer.compare(b.unrest, a.unrest));
		List<ButtonSpec> show = new ArrayList<>();

		for (int i = 0; i < Math.min(5, restless.size()); i++) {
			ProvinceData p = restless.get(i);
			NationData n = ClientNations.get(p.nation);
			this.bar(this.fit(p.name, 90), p.unrest + "% (" + (n != null ? this.fit(n.name, 80) : "?") + ")", p.unrest / 100.0, 0xFFFF7040);
			show.add(this.button("Map: " + this.fit(p.name, 70), () -> {
				MapScreen.focus(p.x, p.z);
				this.open(new MapScreen(false));
			}));
		}

		if (restless.isEmpty()) {
			this.text("All quiet.", 0xFF888888);
		}

		this.buttons(show);
	}

	private void found() {
		this.title("Your own nation", 0xFFFFE0A0);
		this.text("Founding a nation is hard. You need a village whose people back you - and its old rulers will fight you.", 0xFFBBBBBB);
		ProvinceData here = this.villageHere();
		ProvinceData best = here != null ? here : this.bestSupported();
		this.heading(here != null ? "Here: " + here.name : best != null ? "Best chance: " + best.name : "No village knows you yet");
		NationData mine = ClientNations.myNation();
		int carried = this.carried();
		this.check(mine == null, "Belong to no nation" + (mine != null ? " (leave " + mine.name + " first)" : ""));

		if (best != null) {
			int s = ClientRevolts.support(best.id);
			this.check(s >= Charter.SUPPORT_NEEDED, "Its people back you: " + s + "/" + Charter.SUPPORT_NEEDED + " (duties, gifts, killing monsters there)");
			this.check(best.unrest >= 40 || best.happiness < 45 || s >= Charter.SUPPORT_BELOVED,
					"It wants change: unrest " + best.unrest + "% (40+) or happiness " + best.happiness + "% (under 45), or " + Charter.SUPPORT_BELOVED + " support");
			this.check(!best.capital, "Not a capital");
		} else {
			this.check(false, "A village that backs you (do duties for a village)");
		}

		this.check(carried >= Charter.COST, "Carry " + Charter.COST + " emeralds for the charter (you carry " + carried + ")");
		this.check(here != null, "Stand in the village");
		boolean ready = here != null && mine == null && Charter.problem(here, ClientRevolts.support(here.id), carried) == null;
		this.buttons(List.of(ready ? this.button("⚑ Found a nation here", () -> this.open(new NationsScreen(true)))
				: this.button("Duties", () -> this.show(Section.DUTIES))));
		this.text("Then: you get the village, a free militia and a small treasury. Defend it, win allies, or make peace.", 0xFF9AA0A6);
	}

	private void wars() {
		this.title("Personal wars", 0xFFFF8A65);
		this.text("You can declare your OWN war on a nation (a letter). Then - and when your nation is at war with them, "
				+ "or when they think you are an outlaw (opinion -60) - the guards of their villages come out to kill you when you get close: "
				+ "iron golems, vindicators, brutes, the dead. Killing guards makes them hate you more.", 0xFFBBBBBB);
		this.heading("What the nations think of you");
		boolean any = false;

		for (PersonalSyncPayload.Standing st : ClientPersonal.standings()) {
			NationData n = ClientNations.get(st.nation());

			if (n == null) {
				continue;
			}

			any = true;
			String tag = st.war() ? "  ⚔ WAR" : st.opinion() <= -60 ? "  ⚠ outlaw" : "";
			this.text(n.name + ": " + (st.opinion() > 0 ? "+" : "") + st.opinion() + " " + Relations.label(st.opinion()) + tag,
					tag.isEmpty() ? 0xFF000000 | Relations.color(st.opinion()) : 0xFFFF6050);
		}

		if (!any) {
			this.text("Nobody has an opinion of you yet (0 everywhere).", 0xFF888888);
		}

		this.buttons(List.of(this.button("⚔ Declare personal war", () -> this.open(new LettersScreen(null, LetterData.Type.WAR))),
				this.button("☘ Ask for peace", () -> this.open(new LettersScreen(null, LetterData.Type.PEACE))),
				this.button("✉ Gift (win them over)", () -> this.open(new LettersScreen(null, LetterData.Type.GIFT)))));
		this.text("Peace costs at least 30 emeralds (sent with the letter).", 0xFF9AA0A6);
	}

	/** Opens the You tab on the treasury page. */
	static CareerScreen treasuryPage() {
		section = Section.TREASURY;
		return new CareerScreen();
	}

	private void treasury() {
		NationData mine = ClientNations.myNation();
		this.title("Treasury", 0xFF9CFF9C);

		if (mine == null) {
			this.text("Join a nation to see its treasury.", 0xFF888888);
			return;
		}

		boolean may = mine.leader.equals(this.me()) || mine.rankOf(this.me()) >= Ranks.MINISTER;
		this.text(mine.name + " has " + mine.treasury + " emeralds (" + (mine.lastBalance >= 0 ? "+" : "") + mine.lastBalance + " yesterday).", 0xFFFFFFFF);
		this.heading("Yesterday's accounts");

		if (mine.ledger.isEmpty()) {
			this.text("Nothing yet - the accounts are made at the end of each day (every 20 minutes).", 0xFF888888);
		}

		for (Map.Entry<String, Integer> e : mine.ledger.entrySet()) {
			int v = e.getValue();
			this.text((v >= 0 ? "+" : "") + v + "   " + e.getKey(), v >= 0 ? 0xFF9CFF9C : 0xFFFF8A80);
		}

		this.heading("Taxes: " + NationData.TAX_NAMES[Math.max(0, Math.min(3, mine.taxLevel))]);
		this.text("The villages give the nation part of what they earn. Higher taxes fill the treasury but make the villages unhappy "
				+ "(and unhappy villages work less, grow slower and may revolt).", 0xFFBBBBBB);

		for (int i = 0; i < 4; i++) {
			this.text((i == mine.taxLevel ? "\u25CF " : "   ") + NationData.TAX_NAMES[i] + ": " + (int) (NationData.TAX_RATES[i] * 100)
					+ "% of income, mood " + (NationData.TAX_MOOD[i] >= 0 ? "+" : "") + NationData.TAX_MOOD[i], i == mine.taxLevel ? 0xFFFFE0A0 : 0xFF9AA0A6);
		}

		if (may) {
			List<ButtonSpec> taxes = new ArrayList<>();

			for (int i = 0; i < 4; i++) {
				int level = i;
				taxes.add(this.button(NationData.TAX_NAMES[i], () -> {
					if (ClientPlayNetworking.canSend(NationActionPayload.TYPE)) {
						ClientPlayNetworking.send(new NationActionPayload(NationActionPayload.SET_TAX, "", "", level, "", 0));
					}
				}));
			}

			this.buttons(taxes);
		}

		this.heading("Spend on the people");
		int provinces = 0;

		for (MarkerData m : ClientMarkers.all()) {
			if (m.province != null && mine.id.equals(m.province.nation)) {
				provinces++;
			}
		}

		this.text("Festival: +10 happiness and -12 unrest in every village (" + Math.max(5, provinces * 6) + " emeralds, once a day).", 0xFFDDDDDD);
		this.text("Grain imports: +20 food in every village (" + Math.max(5, provinces * 3) + " emeralds, once a day).", 0xFFDDDDDD);

		if (may) {
			this.buttons(List.of(this.button("\u2728 Hold a festival", () -> this.treasuryAction("FESTIVAL")),
					this.button("\u2698 Buy grain", () -> this.treasuryAction("GRAIN"))));
		} else {
			this.text("The leader and Ministers decide how the treasury is spent.", 0xFF888888);
		}

		this.heading("Where the money comes from and goes");
		this.text("In: taxes from every village (its income comes from its villagers and workshops), trade agreements, tribute. "
				+ "Out: upkeep of the villages' buildings, salaries of the members, armies (upkeep, hiring soldiers), new land, festivals.", 0xFF9AA0A6);
	}

	private void treasuryAction(String action) {
		if (ClientPlayNetworking.canSend(NationActionPayload.TYPE)) {
			ClientPlayNetworking.send(new NationActionPayload(NationActionPayload.TREASURY, "", "", 0, action, 0));
		}
	}

	private void land() {
		NationData mine = ClientNations.myNation();
		this.title("Land", 0xFFFFE0A0);
		this.text("Every province's land grows into the wild around it. Nations ruled by the game expand by themselves every day "
				+ "(happy villages faster) until they meet their neighbours. Land of other nations is only taken in war (sieges).", 0xFFBBBBBB);
		boolean may = mine != null && (mine.leader.equals(this.me()) || mine.rankOf(this.me()) >= Ranks.MINISTER);

		if (may) {
			this.text("You can claim land for " + mine.name + ": 2 emeralds a chunk from the treasury (" + mine.treasury + " now). "
					+ "It must touch your land and be within 12 chunks of one of your provinces. Left-click / drag to claim, right-click to give up.", 0xFF9CE0FF);
			this.buttons(List.of(this.button("⚐ Claim land on the map", () -> this.open(new MapScreen(true)))));
		} else {
			this.text("The leader and Ministers of a nation claim land on the map.", 0xFF888888);
		}
	}

	private void army() {
		NationData mine = ClientNations.myNation();
		this.title("Armies", 0xFFFF8A65);
		this.text("Ministers raise armies at a province (right-click it on the map, or talk to its mayor). Officers command them: on the map, "
				+ "left-click an army, then right-click where it should go - an enemy province to besiege it. "
				+ "Go to a battle yourself: enemy soldiers appear, and every one you kill weakens their army.", 0xFFBBBBBB);

		if (mine != null) {
			this.text(mine.name + " has " + ClientWar.of(mine.id).size() + " armies.", 0xFFFFFFFF);
		}

		this.buttons(List.of(this.button("War tab", () -> this.open(new WarScreen())), this.button("Map", () -> this.open(new MapScreen(false)))));
	}

	// ---------------------------------------------------------------- helpers

	private void show(Section s) {
		section = s;
		this.scroll = 0;
		this.coupArmed = false;
		this.rebuildWidgets();
	}

	private double averageHappiness(NationData n) {
		int total = 0;
		int count = 0;

		for (MarkerData m : ClientMarkers.all()) {
			if (m.province != null && n.id.equals(m.province.nation) && !m.province.abandoned) {
				total += m.province.happiness;
				count++;
			}
		}

		return count == 0 ? 50 : total / (double) count;
	}

	/** The village whose centre you stand near (within 90 blocks). */
	private ProvinceData villageHere() {
		if (this.minecraft.player == null || this.minecraft.level == null) {
			return null;
		}

		String dim = this.minecraft.level.dimension().identifier().toString();
		ProvinceData best = null;
		double bestD = 90;

		for (MarkerData m : ClientMarkers.all()) {
			ProvinceData p = m.province;

			if (p != null && p.type == ProvinceData.Type.VILLAGE && !p.abandoned && p.dimension.equals(dim)) {
				double d = Math.hypot(p.x - this.minecraft.player.getX(), p.z - this.minecraft.player.getZ());

				if (d < bestD) {
					bestD = d;
					best = p;
				}
			}
		}

		return best;
	}

	private ProvinceData bestSupported() {
		ProvinceData best = null;
		int most = 0;

		for (Map.Entry<UUID, Integer> e : ClientRevolts.all().entrySet()) {
			ProvinceData p = ClientMarkers.province(e.getKey());

			if (p != null && e.getValue() > most) {
				most = e.getValue();
				best = p;
			}
		}

		return best;
	}

	/** Nations whose capitals are closest to you. */
	private List<NationData> nearestNations(int count) {
		List<NationData> list = new ArrayList<>();

		for (NationData n : ClientNations.all()) {
			list.add(n);
		}

		double px = this.minecraft.player != null ? this.minecraft.player.getX() : 0;
		double pz = this.minecraft.player != null ? this.minecraft.player.getZ() : 0;
		list.sort((a, b) -> Double.compare(this.distance(a, px, pz), this.distance(b, px, pz)));
		return list.subList(0, Math.min(count, list.size()));
	}

	private double distance(NationData n, double px, double pz) {
		double best = Double.MAX_VALUE;

		for (MarkerData m : ClientMarkers.all()) {
			if (m.province != null && n.id.equals(m.province.nation)) {
				best = Math.min(best, Math.hypot(m.province.x - px, m.province.z - pz));
			}
		}

		return best;
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

			if (index >= 0 && index < Section.values().length) {
				this.show(Section.values()[index]);
			}

			return true;
		}

		return false;
	}

	@Override
	protected int listCount() {
		return Section.values().length;
	}
}
