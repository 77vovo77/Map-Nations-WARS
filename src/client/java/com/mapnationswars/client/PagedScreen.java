package com.mapnationswars.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * A window with a list on the left and a scrolling page on the right (Nations and Alliances tabs).
 * Everything is placed relative to the window, and the page scrolls when it doesn't fit,
 * so it works with any GUI scale.
 */
abstract class PagedScreen extends MapNationsBaseScreen {
	protected static final int ROW_H = 24;
	protected static final int SCROLL_STEP = 20;

	protected int listX;
	protected int listY;
	protected int listW;
	protected int listBottom;
	protected int panelX;
	protected int panelW;
	protected int listScroll = 0;
	/** How far the page on the right is scrolled down, in pixels. */
	protected int scroll = 0;
	/** Height of everything on the page (to know how far it can scroll). */
	protected int contentHeight = 0;

	PagedScreen(Tab tab) {
		super(tab);
	}

	@Override
	protected boolean windowed() {
		return true;
	}

	/** Places the list and the page inside the window. Call after addTabs(). */
	protected void layoutFrame() {
		this.listX = this.left() + 8;
		this.listY = this.barBottom() + 22;
		this.listW = Math.max(118, Math.min(210, (int) (this.fw * 0.3)));
		this.listBottom = this.bottom() - 8;
		this.panelX = this.listX + this.listW + 12;
		this.panelW = this.right() - this.panelX - 8;
	}

	/** Top and bottom of the scrolling page. */
	protected int viewTop() {
		return this.barBottom() + 4;
	}

	protected int viewBottom() {
		return this.bottom() - 4;
	}

	protected int maxScroll() {
		return Math.max(0, this.contentHeight - (this.viewBottom() - this.viewTop()) + 8);
	}

	/** Usable width of the page (leaves room for the scroll bar). */
	protected int innerW() {
		return this.panelW - 8;
	}

	/** Makes sure the scroll is still valid after the page changed; rebuilds once if it wasn't. */
	protected void clampScroll() {
		int clamped = Math.max(0, Math.min(this.scroll, this.maxScroll()));

		if (clamped != this.scroll) {
			this.scroll = clamped;
			this.rebuildWidgets();
		}
	}

	// ---------------------------------------------------------------- text helpers

	/** Splits text into lines that fit the given width. */
	protected List<String> wrap(String text, int width) {
		List<String> lines = new ArrayList<>();
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

	/** Cuts text with "..." so it fits the width. */
	protected String fit(String text, int width) {
		if (this.font.width(text) <= width) {
			return text;
		}

		String t = text;

		while (t.length() > 1 && this.font.width(t + "...") > width) {
			t = t.substring(0, t.length() - 1);
		}

		return t + "...";
	}

	protected int drawWrapped(GuiGraphicsExtractor graphics, String text, int x, int y, int width, int color) {
		List<String> lines = this.wrap(text, width);

		for (int i = 0; i < lines.size(); i++) {
			graphics.text(this.font, lines.get(i), x, y + i * 11, color);
		}

		return lines.size() * 11;
	}

	/** A small heading with a thin line after it. */
	protected void drawHeading(GuiGraphicsExtractor graphics, String text, int x, int y, int color) {
		graphics.text(this.font, text, x, y, color);
		int lineX = x + this.font.width(text) + 6;
		int end = this.panelX + this.innerW();

		if (lineX < end) {
			graphics.fill(lineX, y + 4, end, y + 5, 0x30FFFFFF);
		}
	}

	// ---------------------------------------------------------------- buttons on the page

	/** A button that only shows when it's fully inside the scrolling area. */
	protected Button addScrolled(Button b) {
		b.visible = b.getY() >= this.viewTop() && b.getY() + b.getHeight() <= this.viewBottom();
		return this.addRenderableWidget(b);
	}

	protected record ButtonSpec(String label, int width, Button.OnPress action) {
	}

	/** Places buttons left to right, starting a new row when there is no room. Returns the height used. */
	protected int flowButtons(List<ButtonSpec> specs, int x, int y, boolean create) {
		int cx = x;
		int cy = y;

		for (ButtonSpec spec : specs) {
			if (cx > x && cx + spec.width() > x + this.innerW()) {
				cx = x;
				cy += 24;
			}

			if (create) {
				this.addScrolled(Button.builder(Component.literal(spec.label()), spec.action()).pos(cx, cy).size(spec.width(), 20).build());
			}

			cx += spec.width() + 4;
		}

		return specs.isEmpty() ? 0 : cy - y + 24;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		this.drawWindow(graphics);
	}

	/** Window, top bar, the list box and the line between list and page. */
	protected void drawFrame(GuiGraphicsExtractor graphics, String listTitle) {
		this.drawWindow(graphics);
		this.drawTopBar(graphics);
		graphics.text(this.font, listTitle, this.listX, this.barBottom() + 8, 0xFFFFD060);
		graphics.fill(this.listX - 1, this.listY - 1, this.listX + this.listW + 1, this.listBottom + 1, 0x40FFFFFF);
		graphics.fill(this.listX, this.listY, this.listX + this.listW, this.listBottom, 0xA0080A0C);
		graphics.fill(this.panelX - 6, this.barBottom() + 6, this.panelX - 5, this.bottom() - 6, 0x30FFFFFF);
	}

	/** Scroll bar on the right edge when the page is taller than the window. */
	protected void drawScrollBar(GuiGraphicsExtractor graphics) {
		int max = this.maxScroll();

		if (max <= 0) {
			return;
		}

		int top = this.viewTop();
		int trackH = this.viewBottom() - top;
		int barH = Math.max(16, trackH * trackH / (trackH + max));
		int barY = top + (trackH - barH) * this.scroll / max;
		int x = this.panelX + this.panelW - 3;
		graphics.fill(x, top, x + 3, top + trackH, 0x30FFFFFF);
		graphics.fill(x, barY, x + 3, barY + barH, 0xB0FFFFFF);
	}

	/** Scroll bar for the list on the left. */
	protected void drawListScrollBar(GuiGraphicsExtractor graphics, int count, int visible) {
		if (count <= visible) {
			return;
		}

		int trackH = this.listBottom - this.listY;
		int barH = Math.max(12, trackH * visible / count);
		int barY = this.listY + (trackH - barH) * this.listScroll / Math.max(1, count - visible);
		graphics.fill(this.listX + this.listW - 3, this.listY, this.listX + this.listW, this.listBottom, 0x30FFFFFF);
		graphics.fill(this.listX + this.listW - 3, barY, this.listX + this.listW, barY + barH, 0xB0FFFFFF);
	}

	/** Row background in the list (selected / hovered). */
	protected void drawListRow(GuiGraphicsExtractor graphics, int y, int color, boolean selected, boolean hover) {
		if (selected) {
			graphics.fill(this.listX, y, this.listX + this.listW, y + ROW_H, 0x40FFFFFF);
		} else if (hover) {
			graphics.fill(this.listX, y, this.listX + this.listW, y + ROW_H, 0x20FFFFFF);
		}

		graphics.fill(this.listX, y + 1, this.listX + 3, y + ROW_H - 1, 0xFF000000 | color);
		graphics.fill(this.listX + 4, y + ROW_H - 1, this.listX + this.listW - 4, y + ROW_H, 0x14FFFFFF);
	}

	// ---------------------------------------------------------------- scrolling

	/** How many rows the list on the left has. */
	protected abstract int listCount();

	/** Something else (like an open drop-down) wants the mouse wheel first. */
	protected boolean scrollOther(double verticalAmount) {
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (this.scrollOther(verticalAmount)) {
			return true;
		}

		if (mouseX >= this.listX && mouseX < this.listX + this.listW) {
			int visible = (this.listBottom - this.listY) / ROW_H;
			this.listScroll = Math.max(0, Math.min(Math.max(0, this.listCount() - visible), this.listScroll - (int) Math.signum(verticalAmount)));
			return true;
		}

		if (mouseX >= this.panelX - 6 && this.maxScroll() > 0) {
			int before = this.scroll;
			this.scroll = Math.max(0, Math.min(this.maxScroll(), this.scroll - (int) Math.signum(verticalAmount) * SCROLL_STEP));

			if (this.scroll != before) {
				this.rebuildWidgets(); // move the buttons with the page
			}

			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}
}
