package com.mapnationswars.client;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Shared parts of the Map / Claims / Nations / Alliances screens: the title, the tab bar and closing with M.
 * The map fills the whole screen; the other tabs are a window in the middle (the game stays visible around it)
 * that shrinks to fit any GUI scale.
 */
abstract class MapNationsBaseScreen extends Screen {
	static final int TOP_BAR = 32;
	static final String TITLE = "Map Nations WARS";

	// colours shared by all screens
	static final int C_WINDOW = 0xF0161A1F;
	static final int C_BAR = 0xFF0E1114;
	static final int C_EDGE = 0xFF3A4048;
	static final int C_ACCENT = 0xFFFFD54F;
	static final int C_TEXT_DIM = 0xFF9AA0A6;

	enum Tab {
		MAP("Map", "Map"),
		YOU("You", "You"),
		NATIONS("Nations", "Nations"),
		ALLIANCES("Alliances", "Allies"),
		LETTERS("Letters", "Mail"),
		WAR("War", "War");

		final String label;
		final String shortLabel;

		Tab(String label, String shortLabel) {
			this.label = label;
			this.shortLabel = shortLabel;
		}
	}

	private final Tab tab;

	/** The area this screen uses (the whole screen, or the window). */
	protected int ox;
	protected int oy;
	protected int fw;
	protected int fh;
	private int tabsX;

	MapNationsBaseScreen(Tab tab) {
		super(Component.literal(tab.label));
		this.tab = tab;
	}

	/** True = drawn as a window in the middle of the screen instead of covering everything. */
	protected boolean windowed() {
		return false;
	}

	/** Works out where the screen / window goes. Called at the start of init. */
	protected void computeFrame() {
		if (this.windowed()) {
			this.fw = Math.min(this.width - 12, Math.max(Math.min(this.width - 12, 400), (int) (this.width * 0.82)));
			this.fw = Math.min(this.fw, 640);
			this.fh = Math.min(this.height - 10, Math.max(Math.min(this.height - 10, 240), (int) (this.height * 0.86)));
			this.fh = Math.min(this.fh, 420);
			this.ox = (this.width - this.fw) / 2;
			this.oy = (this.height - this.fh) / 2;
		} else {
			this.ox = 0;
			this.oy = 0;
			this.fw = this.width;
			this.fh = this.height;
		}
	}

	protected int left() {
		return this.ox;
	}

	protected int top() {
		return this.oy;
	}

	protected int right() {
		return this.ox + this.fw;
	}

	protected int bottom() {
		return this.oy + this.fh;
	}

	/** Bottom edge of the tab bar. */
	protected int barBottom() {
		return this.oy + TOP_BAR;
	}

	/** Small screens get narrower buttons so everything fits in the top bar. */
	protected boolean compact() {
		return this.fw < 560;
	}

	/** Room the screen needs right of the tabs (the map has its buttons there). */
	protected int topBarReserve() {
		return 0;
	}

	/** Tabs share the room that is left, so all six always fit. */
	private int tabW() {
		int room = this.fw - 12 - this.titleW() - 6 - this.topBarReserve();
		return Math.max(30, Math.min(60, room / Tab.values().length - 2));
	}

	private String tabLabel(Tab t) {
		return this.font.width(t.label) + 8 <= this.tabW() ? t.label : t.shortLabel;
	}

	/** On narrow screens the mod name is shortened so all five tabs and the map buttons fit. */
	private String titleText() {
		return this.fw < 600 ? "WARS" : TITLE;
	}

	private int titleW() {
		return this.font.width(this.titleText()) + 18;
	}

	/** Adds the tab buttons after the title. Returns the x where free space starts. */
	protected int addTabs() {
		this.computeFrame();
		int x = this.ox + 6 + this.titleW();
		this.tabsX = x;

		for (Tab t : Tab.values()) {
			Button b = this.addRenderableWidget(Button.builder(Component.literal(this.tabLabel(t)), btn -> this.openTab(t))
					.pos(x, this.oy + 6).size(this.tabW(), 20).build());
			b.active = t != this.tab; // the open tab is shown as pressed
			x += this.tabW() + 2;
		}

		return x + 6;
	}

	private void openTab(Tab t) {
		if (t == this.tab) {
			return;
		}

		Screen next = switch (t) {
			case MAP -> new MapScreen(false);
			case YOU -> new CareerScreen();
			case NATIONS -> new NationsScreen();
			case ALLIANCES -> new AlliancesScreen();
			case LETTERS -> new LettersScreen();
			case WAR -> new WarScreen();
		};

		this.minecraft.gui.setScreen(next);
	}

	/** Dims the game behind a windowed screen and draws the window. */
	protected void drawWindow(GuiGraphicsExtractor graphics) {
		graphics.fill(0, 0, this.width, this.height, 0x90000000);
		int l = this.left();
		int t = this.top();
		int r = this.right();
		int b = this.bottom();
		graphics.fill(l + 3, t + 3, r + 3, b + 3, 0x60000000); // shadow
		graphics.fill(l - 1, t - 1, r + 1, b + 1, C_EDGE);
		graphics.fill(l, t, r, b, C_WINDOW);
	}

	/** The bar with the mod name and the tabs, with a coloured line under the open tab. */
	protected void drawTopBar(GuiGraphicsExtractor graphics) {
		int l = this.left();
		int t = this.top();
		graphics.fill(l, t, this.right(), t + TOP_BAR, this.windowed() ? C_BAR : 0xC8000000);
		graphics.fill(l, t + TOP_BAR - 1, this.right(), t + TOP_BAR, 0x50FFFFFF);

		// the mod name, with a little gold banner in front
		int x = l + 8;
		int y = t + 12;
		graphics.fill(x, y - 3, x + 7, y + 7, 0xFF7A5A12);
		graphics.fill(x + 1, y - 2, x + 6, y + 6, C_ACCENT);
		graphics.fill(x + 1, y + 6, x + 3, y + 9, C_ACCENT);
		graphics.fill(x + 4, y + 6, x + 6, y + 9, C_ACCENT);
		graphics.text(this.font, Component.literal(this.titleText()).withStyle(style -> style.withColor(0xFFE7B0).withBold(false)), x + 11, y, 0xFFFFFFFF, true);

		int tx = this.tabsX + this.tab.ordinal() * (this.tabW() + 2);
		graphics.fill(tx + 2, t + 27, tx + this.tabW() - 2, t + 29, C_ACCENT);
	}

	/** Server messages near the bottom of the screen. */
	protected void drawStatus(GuiGraphicsExtractor graphics, int y) {
		String status = ClientNations.status();

		if (!status.isEmpty()) {
			while (this.font.width(status) > this.fw - 16 && status.length() > 4) {
				status = status.substring(0, status.length() - 4) + "...";
			}

			int w = this.font.width(status);
			int x = this.left() + this.fw / 2 - w / 2;
			int color = ClientNations.statusOk() ? 0xFF9CFF9C : 0xFFFF8080;
			graphics.fill(x - 7, y - 5, x + w + 7, y + 13, 0xE0101317);
			graphics.fill(x - 7, y - 5, x - 5, y + 13, color);
			graphics.text(this.font, status, x, y, color);
		}
	}

	/** Splits text into lines no wider than maxWidth (keeps "   " separated parts together when it can). */
	protected List<String> wrapText(String text, int maxWidth) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();

		for (String part : text.split("   ")) {
			String test = line.length() == 0 ? part : line + "   " + part;

			if (this.font.width(test) <= maxWidth) {
				line = new StringBuilder(test);
				continue;
			}

			if (line.length() > 0) {
				out.add(line.toString());
				line = new StringBuilder();
			}

			// the part alone is too long: split it word by word
			for (String word : part.split(" ")) {
				String t = line.length() == 0 ? word : line + " " + word;

				if (this.font.width(t) > maxWidth && line.length() > 0) {
					out.add(line.toString());
					line = new StringBuilder(word);
				} else {
					line = new StringBuilder(t);
				}
			}
		}

		if (line.length() > 0) {
			out.add(line.toString());
		}

		return out;
	}

	protected boolean typingInTextBox() {
		return this.getFocused() instanceof EditBox box && box.isFocused();
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		if (!this.typingInTextBox() && input.key() == KeyMappingHelper.getBoundKeyOf(MapNationsClient.OPEN_MAP).getValue()) {
			this.onClose();
			return true;
		}

		return super.keyPressed(input);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
