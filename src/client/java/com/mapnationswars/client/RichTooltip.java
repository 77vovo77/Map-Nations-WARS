package com.mapnationswars.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import com.mapnationswars.nation.NationData;

/**
 * A tooltip that can show players as "[head] Name" in their nation colour.
 * Used everywhere on the map where a player's name appears.
 */
final class RichTooltip {
	private sealed interface Line permits TextLine, PlayerLine {
	}

	private record TextLine(Component text) implements Line {
	}

	private record PlayerLine(String prefix, UUID id, String name, String suffix) implements Line {
	}

	private final List<Line> lines = new ArrayList<>();

	RichTooltip text(Component text) {
		this.lines.add(new TextLine(text));
		return this;
	}

	/** "prefix [head] Name suffix" - the name is coloured by the player's nation. */
	RichTooltip player(String prefix, UUID id, String name, String suffix) {
		this.lines.add(new PlayerLine(prefix, id, name, suffix));
		return this;
	}

	private static Component playerText(PlayerLine p) {
		NationData nation = ClientNations.nationOfPlayer(p.id());
		int color = nation != null ? nation.color : 0xFFFFFF;
		String symbol = nation != null && p.id().equals(nation.leader) ? nation.ideology.symbol + " " : "";
		return Component.literal(symbol).withColor(0xFFD54F)
				.append(Component.literal(p.name()).withColor(color))
				.append(Component.literal(p.suffix()).withColor(0xAAAAAA));
	}

	private static int lineHeight(Line line) {
		return line instanceof PlayerLine ? 12 : 10;
	}

	private int lineWidth(Font font, Line line) {
		if (line instanceof PlayerLine p) {
			return font.width(p.prefix()) + 12 + font.width(playerText(p).getString());
		}

		return font.width(((TextLine) line).text().getString());
	}

	/** Draws on top of everything else, next to the mouse, kept inside the screen. */
	void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, int screenW, int screenH) {
		if (this.lines.isEmpty()) {
			return;
		}

		int w = 0;
		int h = 0;

		for (Line line : this.lines) {
			w = Math.max(w, this.lineWidth(font, line));
			h += lineHeight(line);
		}

		int x = mouseX + 12;
		int y = mouseY - 12;

		if (x + w + 8 > screenW) {
			x = mouseX - w - 16;
		}

		y = Math.max(4, Math.min(y, screenH - h - 8));
		x = Math.max(4, x);

		graphics.nextStratum(); // everything below is drawn above the map

		// vanilla-style tooltip box
		graphics.fill(x - 3, y - 4, x + w + 3, y + h + 3, 0xF0100010);
		graphics.fill(x - 4, y - 3, x - 3, y + h + 2, 0xF0100010);
		graphics.fill(x + w + 3, y - 3, x + w + 4, y + h + 2, 0xF0100010);
		graphics.fill(x - 3, y - 3, x + w + 3, y - 2, 0x805000FF);
		graphics.fill(x - 3, y + h + 1, x + w + 3, y + h + 2, 0x5028007F);
		graphics.fill(x - 3, y - 2, x - 2, y + h + 1, 0x805000FF);
		graphics.fill(x + w + 2, y - 2, x + w + 3, y + h + 1, 0x805000FF);

		int cy = y;

		for (Line line : this.lines) {
			if (line instanceof PlayerLine p) {
				int px = x;

				if (!p.prefix().isEmpty()) {
					graphics.text(font, p.prefix(), px, cy + 2, 0xFFAAAAAA, true);
					px += font.width(p.prefix());
				}

				Faces.draw(graphics, p.id(), px + 1, cy + 1, 8);
				graphics.text(font, playerText(p), px + 12, cy + 2, 0xFFFFFFFF, true);
			} else {
				graphics.text(font, ((TextLine) line).text(), x, cy, 0xFFFFFFFF, true);
			}

			cy += lineHeight(line);
		}
	}
}
