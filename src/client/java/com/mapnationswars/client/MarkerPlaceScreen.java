package com.mapnationswars.client;

import java.util.UUID;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.mapnationswars.nation.MarkerData;
import com.mapnationswars.nation.MarkerType;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.network.MarkerActionPayload;

/** "Place a City here?" - write a name and choose who can see it. */
public class MarkerPlaceScreen extends Screen {
	private static final int PANEL_W = 280;
	private static final int PANEL_H = 188;

	private static int lastVisibility = MarkerType.PUBLIC;

	private final MapScreen parent;
	private final String dimension;
	private final int x;
	private final int z;
	private final MarkerType type;

	private int visibility = lastVisibility;
	private String label = "";

	private int left;
	private int top;
	private final Button[] visibilityButtons = new Button[3];
	private Button placeButton;

	public MarkerPlaceScreen(MapScreen parent, String dimension, int x, int z, MarkerType type) {
		super(Component.literal("Place a marker"));
		this.parent = parent;
		this.dimension = dimension;
		this.x = x;
		this.z = z;
		this.type = type;

		if (type.alwaysPublic) {
			this.visibility = MarkerType.PUBLIC; // important places are always visible to everyone
		}
	}

	// ---------------------------------------------------------------- rules (the server checks them again)

	private UUID me() {
		return this.minecraft != null && this.minecraft.player != null ? this.minecraft.player.getUUID() : new UUID(0, 0);
	}

	private NationData land() {
		return ClientNations.nationAt(this.dimension, Math.floorDiv(this.x, 16), Math.floorDiv(this.z, 16));
	}

	/** Why it can't be placed, or null if it can. */
	private String problem() {
		if (!MapData.isKnown(this.x, this.z)) {
			return "You can only place markers where the map shows land.";
		}

		NationData mine = ClientNations.myNation();
		NationData land = this.land();
		MarkerData replaced = null;

		if (this.type.leaderOnly) {
			if (mine == null || !mine.leader.equals(this.me())) {
				return "Only a nation's leader can place the " + this.type.displayName + ".";
			}

			if (land == null || !land.id.equals(mine.id)) {
				return "The " + this.type.displayName + " must be inside " + mine.name + "'s own land.";
			}

			for (MarkerData m : ClientMarkers.all()) {
				if (m.type == this.type && mine.id.equals(m.nation)) {
					replaced = m;
				}
			}
		}

		if (this.type.settlement) {
			if (land != null && (mine == null || !land.id.equals(mine.id))) {
				return "That is " + land.name + "'s land - you can't found a " + this.type.displayName + " there.";
			}

			for (MarkerData m : ClientMarkers.all()) {
				if (m != replaced && m.type.settlement && m.dimension.equals(this.dimension)
						&& Math.hypot(m.x - this.x, m.z - this.z) < MarkerType.SETTLEMENT_SPACING) {
					return "Too close to " + m.title() + " (settlements need " + MarkerType.SETTLEMENT_SPACING + " blocks).";
				}
			}
		}

		if (this.visibility == MarkerType.NATION && mine == null) {
			return "Join a nation to share markers with it.";
		}

		MarkerType replacing = this.type.playerLimit > 0 ? this.type : null;

		if (ClientMarkers.countMine(this.visibility, replacing) >= MarkerType.VISIBILITY_LIMITS[this.visibility]) {
			return "Limit reached: " + MarkerType.VISIBILITY_LIMITS[this.visibility] + " \"" + MarkerType.VISIBILITY_NAMES[this.visibility] + "\" markers.";
		}

		for (MarkerData m : ClientMarkers.all()) {
			boolean moved = this.type.playerLimit > 0 && m.type == this.type;

			if (!moved && m != replaced && m.owner.equals(this.me()) && m.dimension.equals(this.dimension)
					&& Math.hypot(m.x - this.x, m.z - this.z) < MarkerType.OWN_MARKER_SPACING) {
				return "You already have a marker right here (" + m.title() + ").";
			}
		}

		return null;
	}

	// ---------------------------------------------------------------- widgets

	@Override
	protected void init() {
		this.left = this.width / 2 - PANEL_W / 2;
		this.top = Math.max(4, this.height / 2 - PANEL_H / 2);

		EditBox nameBox = new EditBox(this.font, this.left + 12, this.top + 78, PANEL_W - 24, 18, null, Component.literal("Name"));
		nameBox.setMaxLength(MarkerType.MAX_LABEL);
		nameBox.setValue(this.label);
		nameBox.setResponder(text -> this.label = text);
		this.addRenderableWidget(nameBox);

		int bw = (PANEL_W - 24 - 8) / 3;

		for (int i = 0; i < 3; i++) {
			int vis = i;
			this.visibilityButtons[i] = this.addRenderableWidget(Button.builder(Component.literal(MarkerType.VISIBILITY_NAMES[i]), b -> {
				this.visibility = vis;
				this.refreshButtons();
			}).pos(this.left + 12 + i * (bw + 4), this.top + 116).size(bw, 18).build());
		}

		this.placeButton = this.addRenderableWidget(Button.builder(Component.literal(this.type.settlement ? "Choose borders \u2192" : "Place it"), b -> this.place())
				.pos(this.left + 12, this.top + PANEL_H - 28).size(PANEL_W / 2 - 16, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
				.pos(this.left + PANEL_W / 2 + 4, this.top + PANEL_H - 28).size(PANEL_W / 2 - 16, 20).build());

		this.refreshButtons();
	}

	private void refreshButtons() {
		for (int i = 0; i < 3; i++) {
			this.visibilityButtons[i].active = i != this.visibility && !this.type.alwaysPublic;
		}

		this.placeButton.active = this.problem() == null;
	}

	private void place() {
		if (this.problem() != null) {
			return;
		}

		if (!ClientPlayNetworking.canSend(MarkerActionPayload.TYPE)) {
			ClientNations.setStatus("This server doesn't have Map Nations WARS installed, so markers don't work here.", false);
			this.onClose();
			return;
		}

		lastVisibility = this.visibility;

		if (this.type.settlement) {
			// markers with borders: choose the borders on the map first, then it's placed
			this.onClose();
			this.parent.startNewSettlement(this.type, this.label.trim(), this.visibility, this.x, this.z);
			return;
		}

		ClientPlayNetworking.send(new MarkerActionPayload(MarkerActionPayload.PLACE, "", this.x, this.z,
				this.type.name(), this.label.trim(), this.visibility, java.util.List.of()));
		this.onClose();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, 0xA0000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		this.refreshButtons();
		graphics.fill(0, 0, this.width, this.height, 0xA0000000);

		int l = this.left;
		int t = this.top;
		graphics.fill(l - 1, t - 1, l + PANEL_W + 1, t + PANEL_H + 1, 0xFF5A5F66);
		graphics.fill(l, t, l + PANEL_W, t + PANEL_H, 0xF01B1F24);

		// big preview of the marker (with the land's banner)
		NationData land = this.land();
		NationData mine = ClientNations.myNation();
		MarkerIcons.draw(graphics, this.type, land, this.visibility, this.me(), mine != null ? mine.id : null, l + 26, t + 32, 28);
		graphics.nextStratum();

		graphics.text(this.font, Component.literal("Place a " + this.type.displayName + " here?")
				.withStyle(style -> style.withColor(0xFFD54F).withBold(true)), l + 48, t + 14, 0xFFFFFFFF, true);

		Component where = Component.literal("X " + this.x + "  Z " + this.z + "  -  ").withColor(0xAAAAAA)
				.append(land != null
						? Component.literal("in " + land.name + " territory").withColor(land.color)
						: Component.literal("in the Wilderness").withColor(0x9AA0A6));
		graphics.text(this.font, where, l + 48, t + 28, 0xFFFFFFFF, true);

		if (this.type.showsBanner) {
			String owner = land != null ? "It will fly the banner of " + land.name + "." : "Independent - no nation owns this land.";
			graphics.text(this.font, owner, l + 48, t + 40, 0xFF888888);
		}

		graphics.text(this.font, "Name (optional)", l + 12, t + 66, 0xFFFFD060);
		graphics.text(this.font, "Who can see it", l + 12, t + 104, 0xFFFFD060);

		if (this.type.alwaysPublic) {
			graphics.text(this.font, "(always everyone)", l + 12 + this.font.width("Who can see it") + 6, t + 104, 0xFF9CFF9C);
		}

		String quota = "Yours: only me " + ClientMarkers.countMine(MarkerType.PRIVATE, null) + "/" + MarkerType.VISIBILITY_LIMITS[0]
				+ "   nation " + ClientMarkers.countMine(MarkerType.NATION, null) + "/" + MarkerType.VISIBILITY_LIMITS[1]
				+ "   everyone " + ClientMarkers.countMine(MarkerType.PUBLIC, null) + "/" + MarkerType.VISIBILITY_LIMITS[2];
		graphics.text(this.font, quota, l + 12, t + 138, 0xFF888888);

		String problem = this.problem();

		if (problem != null) {
			String shown = problem;

			while (this.font.width(shown) > PANEL_W - 24 && shown.length() > 4) {
				shown = shown.substring(0, shown.length() - 4) + "...";
			}

			graphics.text(this.font, shown, l + 12, t + 148, 0xFFFF7070);
		}

		super.extractRenderState(graphics, mouseX, mouseY, delta);

		if (problem != null && this.font.width(problem) > PANEL_W - 24
				&& mouseY >= t + 146 && mouseY < t + 158 && mouseX >= l && mouseX < l + PANEL_W) {
			new RichTooltip().text(Component.literal(problem).withColor(0xFF7070)).draw(graphics, this.font, mouseX, mouseY, this.width, this.height);
		}
	}
}
