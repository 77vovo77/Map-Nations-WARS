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

/**
 * Right-click on a marker opens this.
 * Your own marker: rename it, move it or remove it.
 * Someone else's marker: vote to destroy it (it's gone after enough votes).
 */
public class MarkerManageScreen extends Screen {
	private static final int PANEL_W = 260;
	private static final int PANEL_H = 172;

	private final MapScreen parent;
	private final MarkerData marker;
	private String label;

	public MarkerManageScreen(MapScreen parent, MarkerData marker) {
		super(Component.literal("Marker"));
		this.parent = parent;
		this.marker = marker;
		this.label = marker.label;
	}

	private UUID me() {
		return this.minecraft.player != null ? this.minecraft.player.getUUID() : new UUID(0, 0);
	}

	private boolean mine() {
		return this.marker.owner.equals(this.me());
	}

	private boolean send(MarkerActionPayload payload) {
		if (!ClientPlayNetworking.canSend(MarkerActionPayload.TYPE)) {
			ClientNations.setStatus("This server doesn't have Map Nations WARS installed.", false);
			return false;
		}

		ClientPlayNetworking.send(payload);
		return true;
	}

	@Override
	protected void init() {
		int l = this.width / 2 - PANEL_W / 2;
		int t = this.height / 2 - PANEL_H / 2;
		String id = this.marker.id.toString();
		int bottom = t + PANEL_H - 28;

		if (this.mine()) {
			EditBox nameBox = new EditBox(this.font, l + 12, t + 58, PANEL_W - 24 - 70, 18, null, Component.literal("Name"));
			nameBox.setMaxLength(MarkerType.MAX_LABEL);
			nameBox.setValue(this.label);
			nameBox.setResponder(text -> this.label = text);
			this.addRenderableWidget(nameBox);

			this.addRenderableWidget(Button.builder(Component.literal("Rename"), b -> {
				this.send(MarkerActionPayload.rename(id, this.label.trim()));
				this.onClose();
			}).pos(l + PANEL_W - 12 - 66, t + 57).size(66, 20).build());

			this.addRenderableWidget(Button.builder(Component.literal("Move"), b -> {
				this.minecraft.gui.setScreen(this.parent);
				this.parent.startMoving(this.marker);
			}).pos(l + 12, t + 86).size(PANEL_W / 2 - 16, 20).build());

			this.addRenderableWidget(Button.builder(Component.literal("Remove"),
							b -> this.minecraft.gui.setScreen(new MarkerRemoveScreen(this.parent, this.marker)))
					.pos(l + PANEL_W / 2 + 4, t + 86).size(PANEL_W / 2 - 16, 20).build());
		} else {
			boolean voted = this.marker.votes.contains(this.me());
			String count = " (" + this.marker.votes.size() + "/" + MarkerType.DESTROY_VOTES + ")";
			Component voteLabel = Component.literal(voted ? "Take back my vote" + count : "Vote to destroy" + count);
			boolean canRemove = ClientMarkers.canRemove(this.marker);
			int voteW = canRemove ? PANEL_W / 2 - 16 : PANEL_W - 24;

			this.addRenderableWidget(Button.builder(voteLabel, b -> {
				this.send(MarkerActionPayload.vote(id));
				this.onClose();
			}).pos(l + 12, t + 86).size(voteW, 20).build());

			if (canRemove) {
				this.addRenderableWidget(Button.builder(Component.literal("Remove (leader)"),
								b -> this.minecraft.gui.setScreen(new MarkerRemoveScreen(this.parent, this.marker)))
						.pos(l + PANEL_W / 2 + 4, t + 86).size(PANEL_W / 2 - 16, 20).build());
			}
		}

		// settlements: the owner or the leader of the land can edit the borders
		NationData land = MarkerIcons.owner(this.marker);
		boolean landLeader = land != null && land.leader.equals(this.me());

		if (this.marker.type.settlement && (this.mine() || landLeader)) {
			this.addRenderableWidget(Button.builder(Component.literal("Edit borders"), b -> {
				this.minecraft.gui.setScreen(this.parent);
				this.parent.startEditingArea(this.marker);
			}).pos(l + 12, t + 110).size(PANEL_W - 24, 20).build());
		}

		this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
				.pos(l + PANEL_W / 2 - 40, bottom).size(80, 20).build());
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
		graphics.fill(0, 0, this.width, this.height, 0xA0000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, 0xA0000000);
		int l = this.width / 2 - PANEL_W / 2;
		int t = this.height / 2 - PANEL_H / 2;
		graphics.fill(l - 1, t - 1, l + PANEL_W + 1, t + PANEL_H + 1, 0xFF5A5F66);
		graphics.fill(l, t, l + PANEL_W, t + PANEL_H, 0xF01B1F24);

		MarkerIcons.draw(graphics, this.marker, l + 22, t + 22, 24);
		graphics.nextStratum();

		graphics.text(this.font, Component.literal(this.marker.title())
				.withStyle(style -> style.withColor(this.marker.type.color).withBold(true)), l + 40, t + 10, 0xFFFFFFFF, true);

		NationData land = MarkerIcons.owner(this.marker);
		Component where = Component.literal(this.marker.type.displayName + "  -  ").withColor(0xAAAAAA)
				.append(land != null ? Component.literal(land.name).withColor(land.color) : Component.literal("Wilderness").withColor(0x9AA0A6));
		graphics.text(this.font, where, l + 40, t + 22, 0xFFFFFFFF, true);

		if (this.marker.type.settlement) {
			graphics.text(this.font, this.marker.population + " villagers  -  " + this.marker.area.size() + " chunks",
					l + PANEL_W - 12 - this.font.width(this.marker.population + " villagers  -  " + this.marker.area.size() + " chunks"),
					t + 10, 0xFF9CE0A0);
		}

		// placed by [head] name
		graphics.text(this.font, "Placed by", l + 40, t + 34, 0xFF888888);
		int hx = l + 40 + this.font.width("Placed by") + 4;
		Faces.draw(graphics, this.marker.owner, hx, t + 34, 8);
		graphics.text(this.font, Component.literal(this.marker.ownerName).withColor(Faces.nationColor(this.marker.owner)), hx + 11, t + 34, 0xFFFFFFFF, true);

		if (this.mine()) {
			graphics.text(this.font, "Name", l + 12, t + 48, 0xFFFFD060);

			if (!this.marker.votes.isEmpty()) {
				graphics.text(this.font, "Destroy votes against it: " + this.marker.votes.size() + "/" + MarkerType.DESTROY_VOTES,
						l + 12, t + 134, 0xFFFF8080);
			}
		} else {
			graphics.text(this.font, "If " + MarkerType.DESTROY_VOTES + " players vote, this marker is destroyed.", l + 12, t + 56, 0xFFAAAAAA);
			graphics.text(this.font, "You can take your vote back any time.", l + 12, t + 67, 0xFF777777);
		}

		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}
}
