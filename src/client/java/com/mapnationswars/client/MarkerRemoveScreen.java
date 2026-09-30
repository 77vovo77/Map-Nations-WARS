package com.mapnationswars.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.mapnationswars.nation.MarkerData;
import com.mapnationswars.network.MarkerActionPayload;

/** "Remove this marker?" confirmation. */
public class MarkerRemoveScreen extends Screen {
	private static final int PANEL_W = 240;
	private static final int PANEL_H = 96;

	private final MapScreen parent;
	private final MarkerData marker;

	public MarkerRemoveScreen(MapScreen parent, MarkerData marker) {
		super(Component.literal("Remove marker"));
		this.parent = parent;
		this.marker = marker;
	}

	@Override
	protected void init() {
		int left = this.width / 2 - PANEL_W / 2;
		int top = this.height / 2 - PANEL_H / 2;

		this.addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
			if (ClientPlayNetworking.canSend(MarkerActionPayload.TYPE)) {
				ClientPlayNetworking.send(MarkerActionPayload.remove(this.marker.id.toString()));
			}

			this.onClose();
		}).pos(left + 12, top + PANEL_H - 28).size(PANEL_W / 2 - 16, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Keep it"), b -> this.onClose())
				.pos(left + PANEL_W / 2 + 4, top + PANEL_H - 28).size(PANEL_W / 2 - 16, 20).build());
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
		int left = this.width / 2 - PANEL_W / 2;
		int top = this.height / 2 - PANEL_H / 2;
		graphics.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, 0xFF5A5F66);
		graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xF01B1F24);

		graphics.centeredText(this.font, "Remove this marker?", this.width / 2, top + 8, 0xFFFFD54F);
		MarkerIcons.draw(graphics, this.marker, this.width / 2f, top + 34, 20);
		graphics.nextStratum();
		graphics.centeredText(this.font, this.marker.title(), this.width / 2, top + 50, 0xFFFFFFFF);

		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}
}
