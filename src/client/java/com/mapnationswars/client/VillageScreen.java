package com.mapnationswars.client;

import java.util.UUID;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mapnationswars.nation.NationData;
import com.mapnationswars.nation.ProvinceData;
import com.mapnationswars.network.VillageActionPayload;

/**
 * A village's page: its people, food, emeralds, buildings and happiness.
 * Opened by talking to the mayor (right-click him) or right-clicking the village on the map.
 * Leaders can order buildings; anyone standing in the village can give it emeralds.
 */
public class VillageScreen extends Screen {
	private static final int W = 300;
	private static final int H = 300;
	/** Costs and effects, same as the server (WarsEconomy.Build). */
	private static final String[][] BUILDS = {
		{"HOUSE", "House", "12", "+2 beds"},
		{"FARM", "Farm", "8", "+5 food/day"},
		{"WORKSHOP", "Workshop", "20", "+4 emeralds/day"},
	};

	private final Screen parent;
	private final UUID provinceId;
	private final boolean atMayor;
	private int left;
	private int top;

	public VillageScreen(Screen parent, UUID provinceId, boolean atMayor) {
		super(Component.literal("Village"));
		this.parent = parent;
		this.provinceId = provinceId;
		this.atMayor = atMayor;
	}

	private ProvinceData province() {
		return ClientMarkers.province(this.provinceId);
	}

	private boolean canOrder(ProvinceData p) {
		if (this.minecraft == null || this.minecraft.player == null) {
			return false;
		}

		NationData n = ClientNations.get(p.nation);
		UUID me = this.minecraft.player.getUUID();
		return (n != null && (n.leader.equals(me)
				|| (n.isMember(me) && n.rankOf(me) >= com.mapnationswars.nation.Ranks.MINISTER)));
	}

	/** Am I a member of the nation that owns this village? */
	private NationData myNationHere(ProvinceData p) {
		NationData n = ClientNations.get(p.nation);
		return n != null && this.minecraft != null && this.minecraft.player != null && n.isMember(this.minecraft.player.getUUID()) ? n : null;
	}

	private boolean canUseTreasury(ProvinceData p) {
		NationData n = this.myNationHere(p);
		UUID me = this.minecraft.player.getUUID();
		return n != null && p.capital && (n.leader.equals(me) || n.rankOf(me) >= com.mapnationswars.nation.Ranks.MINISTER);
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

	private void send(int action, String argument, int amount) {
		if (ClientPlayNetworking.canSend(VillageActionPayload.TYPE)) {
			ClientPlayNetworking.send(new VillageActionPayload(this.provinceId.toString(), action, argument, amount));
		}
	}

	@Override
	protected void init() {
		int w = Math.min(W, this.width - 12);
		int h = Math.min(H, this.height - 10);
		this.left = (this.width - w) / 2;
		this.top = (this.height - h) / 2;
		ProvinceData p = this.province();

		if (p == null) {
			return;
		}

		boolean village = p.type == ProvinceData.Type.VILLAGE && !p.abandoned;
		int y = this.top + h - 80;

		if (village && this.canOrder(p)) {
			// orders: only the village's own leaders
			int bw = (w - 24 - 8) / 3;

			for (int i = 0; i < BUILDS.length; i++) {
				String[] b = BUILDS[i];
				Button button = this.addRenderableWidget(Button.builder(Component.literal(b[1] + " (" + b[2] + ")"),
						btn -> this.send(VillageActionPayload.ORDER, b[0], 0)).pos(this.left + 12 + i * (bw + 4), y).size(bw, 20).build());
				button.active = this.canOrder(p) && p.building.isEmpty();
			}
		}

		// raising armies (stage 5): from the map too, like building orders
		if (p.nation != null && !p.abandoned && this.canOrder(p)) {
			int ry = this.top + h - 106;
			int rw = (w - 24 - 12) / 4;
			com.mapnationswars.nation.DivisionData.Kind[] kinds = com.mapnationswars.nation.DivisionData.Kind.values();

			for (int i = 0; i < kinds.length; i++) {
				com.mapnationswars.nation.DivisionData.Kind k = kinds[i];
				Button raise = this.addRenderableWidget(Button.builder(Component.literal(k.symbol + " " + k.displayName + " " + k.cost), b -> {
					if (ClientPlayNetworking.canSend(com.mapnationswars.network.ArmyActionPayload.TYPE)) {
						ClientPlayNetworking.send(new com.mapnationswars.network.ArmyActionPayload(com.mapnationswars.network.ArmyActionPayload.RAISE,
								this.provinceId.toString(), k.name(), 0, 0));
					}
				}).pos(this.left + 12 + i * (rw + 4), ry).size(rw, 20).build());
				raise.active = ClientWar.siegeOf(p.id) == null;
			}
		}

		// salary and treasury (members of the nation, in person)
		NationData mine = this.myNationHere(p);
		int my = this.top + h - 54;
		int mw = (w - 24 - 8) / 3;

		if (mine != null) {
			int owed = mine.owed.getOrDefault(this.minecraft.player.getUUID(), 0);
			Button collect = this.addRenderableWidget(Button.builder(Component.literal("Salary (" + owed + ")"),
					b -> this.send(VillageActionPayload.COLLECT_SALARY, "", 0)).pos(this.left + 12, my).size(mw, 20).build());
			collect.active = this.atMayor && owed > 0;

			if (this.canUseTreasury(p)) {
				Button dep = this.addRenderableWidget(Button.builder(Component.literal("Deposit 10"),
						b -> this.send(VillageActionPayload.DEPOSIT, "", 10)).pos(this.left + 12 + mw + 4, my).size(mw, 20).build());
				Button wd = this.addRenderableWidget(Button.builder(Component.literal("Withdraw 10"),
						b -> this.send(VillageActionPayload.WITHDRAW, "", 10)).pos(this.left + 12 + (mw + 4) * 2, my).size(mw, 20).build());
				dep.active = this.atMayor;
				wd.active = this.atMayor;
			}
		}

		// outsiders at the mayor: found a nation here (no nation), stir up the people against their rulers
		if (village && mine == null && this.atMayor) {
			boolean noNation = ClientNations.myNation() == null;
			int half = (w - 24 - 4) / 2;
			int sx = this.left + 12;

			if (noNation) {
				Button found = this.addRenderableWidget(Button.builder(Component.literal("\u2691 Found a nation (" + com.mapnationswars.nation.Charter.COST + ")"),
						b -> this.minecraft.gui.setScreen(new NationsScreen(true))).pos(sx, my).size(half, 20).build());
				found.active = this.charterProblem(p) == null;
				sx += half + 4;
			}

			if (p.nation != null && !p.capital) {
				// ready to rise: lead it; otherwise stir it up
				boolean ready = noNation && p.unrest >= 70 && ClientRevolts.support(p.id) >= 60;
				int action = ready ? com.mapnationswars.network.PersonalActionPayload.LEAD_REVOLT : com.mapnationswars.network.PersonalActionPayload.STIR_UNREST;
				Button stir = this.addRenderableWidget(Button.builder(Component.literal(ready ? "\u2691 LEAD THE REVOLT" : "\uD83D\uDD25 Stir unrest (10)")
						.withColor(ready ? 0xFF7C7C : 0xFFFFFF), b -> {
					if (ClientPlayNetworking.canSend(com.mapnationswars.network.PersonalActionPayload.TYPE)) {
						ClientPlayNetworking.send(new com.mapnationswars.network.PersonalActionPayload(action, this.provinceId.toString()));
					}
				}).pos(sx, my).size(noNation ? half : w - 24, 20).build());
				stir.active = ClientRevolts.support(p.id) >= 20;
			}
		}

		// gifts and closing
		int by = this.top + h - 28;
		int gw = 54;

		if (village) {
			Button give1 = this.addRenderableWidget(Button.builder(Component.literal("Give 1"), b -> this.send(VillageActionPayload.DONATE, "", 1))
					.pos(this.left + 12, by).size(gw, 20).build());
			Button give10 = this.addRenderableWidget(Button.builder(Component.literal("Give 10"), b -> this.send(VillageActionPayload.DONATE, "", 10))
					.pos(this.left + 12 + gw + 4, by).size(gw, 20).build());
			give1.active = this.atMayor;
			give10.active = this.atMayor;
		}

		if (p.nation != null) {
			this.addRenderableWidget(Button.builder(Component.literal("Nation"), b -> this.minecraft.gui.setScreen(new NationsScreen(p.nation)))
					.pos(this.left + w - 12 - 50 - 4 - 56, by).size(56, 20).build());
		}

		this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
				.pos(this.left + w - 12 - 50, by).size(50, 20).build());
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
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
		ProvinceData p = this.province();
		int w = Math.min(W, this.width - 12);
		int h = Math.min(H, this.height - 10);
		int l = this.left;
		int t = this.top;

		graphics.fill(0, 0, this.width, this.height, 0x90000000);
		graphics.fill(l + 3, t + 3, l + w + 3, t + h + 3, 0x60000000);
		graphics.fill(l - 1, t - 1, l + w + 1, t + h + 1, MapNationsBaseScreen.C_EDGE);
		graphics.fill(l, t, l + w, t + h, MapNationsBaseScreen.C_WINDOW);

		if (p == null) {
			graphics.centeredText(this.font, "This village is gone.", l + w / 2, t + 20, 0xFFFFFFFF);
			super.extractRenderState(graphics, mouseX, mouseY, delta);
			return;
		}

		NationData n = ClientNations.get(p.nation);
		int accent = n != null ? n.color : 0xFFFFFF;
		graphics.fill(l, t, l + w, t + 3, 0xFF000000 | accent);

		// title
		graphics.text(this.font, Component.literal(p.title()).withStyle(s -> s.withColor(accent).withBold(true)), l + 12, t + 10, 0xFFFFFFFF, true);
		String sub = (p.capital ? "Capital  ·  " : "") + p.type.displayName + (n != null ? "  of  " : "");
		graphics.text(this.font, Component.literal(sub).withColor(0xAAAAAA)
				.append(n != null ? Component.literal(n.name).withColor(n.color) : Component.literal("")), l + 12, t + 22, 0xFFFFFFFF, true);

		// under siege?
		com.mapnationswars.network.WarSyncPayload.Siege siege = ClientWar.siegeOf(p.id);

		if (siege != null) {
			NationData attacker = ClientNations.get(siege.attacker());
			String text = "\u26A0 Siege " + (int) siege.progress() + "%";
			graphics.text(this.font, text, l + w - 12 - this.font.width(text), t + 10, 0xFFFF6050);

			if (attacker != null) {
				String by = this.fitWidth("by " + attacker.name, w / 2 - 12);
				graphics.text(this.font, by, l + w - 12 - this.font.width(by), t + 22, 0xFF000000 | attacker.color);
			}
		}

		int y = t + 38;
		boolean village = p.type == ProvinceData.Type.VILLAGE && !p.abandoned;

		if (p.abandoned) {
			graphics.text(this.font, "Only zombies live here now.", l + 12, y, 0xFF88AA66);
		} else {
			graphics.text(this.font, Component.literal(village ? "Mayor " : "Commander ").withColor(0xAAAAAA)
					.append(Component.literal(p.mayorName).withColor(0xFFE0A0)), l + 12, y, 0xFFFFFFFF, true);
		}

		y += 16;
		int col2 = l + w / 2 + 4;

		// people and happiness
		graphics.text(this.font, (village ? "Villagers: " : "Garrison: ") + p.population + (village ? " / " + p.beds() + " beds" : ""), l + 12, y, 0xFFDDDDDD);
		graphics.text(this.font, "Happiness", col2, y, 0xFFDDDDDD);
		int barX = col2 + this.font.width("Happiness") + 6;
		int barW = l + w - 12 - barX;
		int hcol = p.happiness >= 60 ? 0xFF6CCB5F : (p.happiness >= 35 ? 0xFFE0B040 : 0xFFE05050);
		graphics.fill(barX, y + 1, barX + barW, y + 8, 0xFF2A2F36);
		graphics.fill(barX, y + 1, barX + barW * Math.max(0, Math.min(100, p.happiness)) / 100, y + 8, hcol);
		y += 14;

		// food
		String foodTrend = (p.lastFood >= 0 ? "+" : "") + p.lastFood + "/day";
		graphics.text(this.font, "Food: " + p.food, l + 12, y, 0xFFE8D27A);
		graphics.text(this.font, foodTrend, l + 12 + this.font.width("Food: " + p.food) + 6, y, p.lastFood >= 0 ? 0xFF9CFF9C : 0xFFFF8080);
		graphics.text(this.font, "Village emeralds: " + p.funds, col2, y, 0xFF6CE07A);
		y += 14;

		// money
		graphics.text(this.font, "Income " + p.lastIncome + "/day   tax to nation " + p.lastTax + "   upkeep " + p.lastUpkeep, l + 12, y, 0xFFAAAAAA);
		y += 14;

		// unrest, and how much the people back you (stage 7)
		if (!p.abandoned) {
			int ucol = p.unrest >= 85 ? 0xFFFF4040 : p.unrest >= 60 ? 0xFFFF8040 : p.unrest >= 30 ? 0xFFE0C040 : 0xFF7CC87C;
			graphics.text(this.font, "Unrest " + p.unrest + "% - " + p.unrestLabel(), l + 12, y, ucol);

			if (village && this.myNationHere(p) == null) {
				if (p.nation != null) {
					int st = ClientPersonal.standing(p.nation);
					String tag = ClientPersonal.outlawIn(p.nation) ? "  \u26A0 their guards hunt you" : "";
					graphics.text(this.font, "Their nation thinks of you: " + (st > 0 ? "+" : "") + st + tag, l + 12, y + 11,
							tag.isEmpty() ? 0xFF9AA0A6 : 0xFFFF6050);
				}

				int support = ClientRevolts.support(p.id);
				graphics.text(this.font, "They back you: " + support + "/" + com.mapnationswars.nation.Charter.SUPPORT_NEEDED, col2, y,
						support >= com.mapnationswars.nation.Charter.SUPPORT_NEEDED ? 0xFF7CFF7C : 0xFFB0B0B0);
			}
		}

		y += !p.abandoned && village && this.myNationHere(p) == null && p.nation != null ? 28 : 18;

		// buildings
		if (village) {
			graphics.text(this.font, "Buildings", l + 12, y, 0xFFFFD060);
			graphics.fill(l + 12 + this.font.width("Buildings") + 6, y + 4, l + w - 12, y + 5, 0x30FFFFFF);
			y += 12;
			graphics.text(this.font, "Houses " + p.houses + "    Farms " + p.farms + "    Workshops " + p.workshops, l + 12, y, 0xFFDDDDDD);
			y += 12;

			if (!p.building.isEmpty()) {
				String what = p.building.charAt(0) + p.building.substring(1).toLowerCase();
				graphics.text(this.font, "Building a " + what.toLowerCase() + " - ready tomorrow", l + 12, y, 0xFF9CC8FF);
			} else if (this.canOrder(p)) {
				graphics.text(this.font, "Order a building (paid by the village, then the nation):", l + 12, y, 0xFF9CC8FF);
			} else {
				NationData owner = ClientNations.get(p.nation);
				graphics.text(this.font, "Only " + (owner != null ? owner.name + "'s" : "its") + " leader and Ministers give orders here.", l + 12, y, 0xFF888888);
			}

			// your money here
			NationData mine = this.myNationHere(p);
			String info = this.atMayor ? "You carry " + this.carried() + " emeralds." : "Talk to the mayor to give or collect emeralds.";

			if (mine != null && p.capital) {
				info += "  Treasury: " + mine.treasury;
			}

			graphics.text(this.font, info, l + 12, t + h - 134, 0xFF888888);
		} else {
			graphics.text(this.font, p.type == ProvinceData.Type.BASTION ? "A piglin stronghold. Lives from gold and raids."
					: "An illager stronghold. Lives from raids.", l + 12, y, 0xFF888888);
		}

		super.extractRenderState(graphics, mouseX, mouseY, delta);

		// what the outsider buttons do
		if (village && this.atMayor && this.myNationHere(p) == null) {
			int fy = t + h - 54;
			boolean noNation = ClientNations.myNation() == null;
			int half = (w - 24 - 4) / 2;
			boolean overStir = mouseY >= fy && mouseY < fy + 20 && (noNation ? mouseX >= l + 12 + half + 4 && mouseX < l + w - 12 : mouseX >= l + 12 && mouseX < l + w - 12);

			if (overStir && p.nation != null && !p.capital) {
				int support = ClientRevolts.support(p.id);
				graphics.setTooltipForNextFrame(this.font, Component.literal(support < 20
						? "Nobody listens to you yet (support " + support + "/20). Do duties for " + p.name + ", give emeralds, kill monsters here."
						: noNation && p.unrest >= 70 && support >= 60 ? "The village is ready! Raise the banner: it rises at once and YOU rule it, with a rebel army."
						: "Costs 10 emeralds: +12 unrest. " + (noNation ? "With 60 support and 70% unrest you can lead the revolt yourself (now: "
								+ support + " support, " + p.unrest + "% unrest)." : "At 100% the village rises up.") + " Their guards may catch you!"), mouseX, mouseY);
			} else if (noNation && mouseX >= l + 12 && mouseX < l + 12 + half && mouseY >= fy && mouseY < fy + 20) {
				String problem = this.charterProblem(p);
				graphics.setTooltipForNextFrame(this.font, Component.literal(problem != null ? problem
						: "The people of " + p.name + " are ready to follow you. Name your nation - but the old rulers will fight to keep it."), mouseX, mouseY);
			}
		}

		// army kinds on hover
		if (p.nation != null && !p.abandoned && this.canOrder(p)) {
			int rw = (w - 24 - 12) / 4;
			int ry = t + h - 106;
			com.mapnationswars.nation.DivisionData.Kind[] kinds = com.mapnationswars.nation.DivisionData.Kind.values();
			graphics.text(this.font, "Raise an army here (paid from the treasury):", l + 12, ry - 11, 0xFFFF9C7A);

			for (int i = 0; i < kinds.length; i++) {
				int bx = l + 12 + i * (rw + 4);

				if (mouseX >= bx && mouseX < bx + rw && mouseY >= ry && mouseY < ry + 20) {
					com.mapnationswars.nation.DivisionData.Kind k = kinds[i];
					NationData owner = ClientNations.get(p.nation);
					String unit = owner != null ? k.unitName(owner.faction) : k.displayName;
					graphics.setTooltipForNextFrame(this.font, Component.literal(unit + ": " + k.maxStrength + " soldiers, attack " + k.attack
							+ ", defence " + k.defence + ", speed " + k.speed + ", siege " + k.siege + ". Costs " + k.cost + ", upkeep " + k.upkeep() + "/day."), mouseX, mouseY);
				}
			}
		}

		// costs on hover
		if (village) {
			int bw = (w - 24 - 8) / 3;
			int by = t + h - 80;

			for (int i = 0; i < BUILDS.length; i++) {
				int bx = l + 12 + i * (bw + 4);

				if (mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 20) {
					graphics.setTooltipForNextFrame(this.font, Component.literal(BUILDS[i][1] + ": " + BUILDS[i][3] + ", costs " + BUILDS[i][2]
							+ " emeralds, ready the next day"), mouseX, mouseY);
				}
			}
		}
	}

	@Override
	public void tick() {
		// buttons follow the data (e.g. a building got finished)
		ProvinceData p = this.province();

		if (p != null && this.lastBuilding != null && !this.lastBuilding.equals(p.building)) {
			this.rebuildWidgets();
		}

		this.lastBuilding = p != null ? p.building : null;
	}

	private String lastBuilding = null;

	private String charterProblem(ProvinceData p) {
		return com.mapnationswars.nation.Charter.problem(p, ClientRevolts.support(p.id), this.carried());
	}

	private String fitWidth(String text, int width) {
		String t = text;

		while (t.length() > 1 && this.font.width(t) > width) {
			t = t.substring(0, t.length() - 1);
		}

		return t;
	}
}
