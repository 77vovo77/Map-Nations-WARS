package com.mapnationswars.client;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

/**
 * Shows your inventory. Click a banner to put it into the nation's banner slot.
 * (The banner stays in your inventory - the nation just copies its design.)
 */
public class BannerPickerScreen extends Screen {
	private static final int CELL = 18;

	private final Screen parent;
	private final java.util.function.BiConsumer<Integer, ItemStack> onDone;
	private int chosenSlot;
	private ItemStack chosen;

	private int gridX;
	private int gridY;
	private int slotX;
	private int slotY;

	/** onDone gets the chosen inventory slot (-1 = none) and a copy of the banner. */
	public BannerPickerScreen(Screen parent, int startSlot, ItemStack start, java.util.function.BiConsumer<Integer, ItemStack> onDone) {
		super(Component.literal("Choose a banner"));
		this.parent = parent;
		this.chosenSlot = startSlot;
		this.chosen = start;
		this.onDone = onDone;
	}

	private List<ItemStack> items() {
		return this.minecraft.player != null ? this.minecraft.player.getInventory().getNonEquipmentItems() : List.of();
	}

	/** Inventory slot index shown at grid position (row 0-2 = main inventory, row 3 = hotbar). */
	private static int slotAt(int row, int col) {
		return row < 3 ? 9 + row * 9 + col : col;
	}

	@Override
	protected void init() {
		int panelW = 9 * CELL + 16;
		this.gridX = this.width / 2 - 9 * CELL / 2;
		this.slotX = this.width / 2 - 13;
		this.slotY = Math.max(22, this.height / 2 - 78);
		this.gridY = this.slotY + 44;

		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
			this.onDone.accept(this.chosenSlot, this.chosen);
			this.onClose();
		}).pos(this.width / 2 - panelW / 2, this.gridY + 4 * CELL + 16).size(panelW / 2 - 2, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
				.pos(this.width / 2 + 2, this.gridY + 4 * CELL + 16).size(panelW / 2 - 2, 20).build());
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
		graphics.fill(0, 0, this.width, this.height, 0xC0000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, 0xC0000000);

		int panelW = 9 * CELL + 16;
		int panelX = this.width / 2 - panelW / 2;
		int panelTop = this.slotY - 24;
		int panelBottom = this.gridY + 4 * CELL + 42;

		// vanilla-like grey inventory panel
		graphics.fill(panelX - 1, panelTop - 1, panelX + panelW + 1, panelBottom + 1, 0xFF000000);
		graphics.fill(panelX, panelTop, panelX + panelW, panelBottom, 0xFFC6C6C6);

		graphics.centeredText(this.font, "Put a banner in the slot", this.width / 2, panelTop - 14, 0xFFFFFFFF);
		graphics.text(this.font, "Nation banner", panelX + 8, panelTop + 6, 0xFF404040, false);

		// the nation's banner slot
		graphics.fill(this.slotX, this.slotY, this.slotX + 26, this.slotY + 26, 0xFF8B8B8B);
		graphics.fill(this.slotX + 1, this.slotY + 1, this.slotX + 25, this.slotY + 25, 0xFF373737);

		if (!this.chosen.isEmpty()) {
			graphics.item(this.chosen, this.slotX + 5, this.slotY + 5);
		}

		graphics.text(this.font, "Inventory", panelX + 8, this.gridY - 11, 0xFF404040, false);

		List<ItemStack> items = this.items();
		ItemStack hovered = ItemStack.EMPTY;

		for (int row = 0; row < 4; row++) {
			for (int col = 0; col < 9; col++) {
				int slot = slotAt(row, col);
				int x = this.gridX + col * CELL;
				int y = this.gridY + row * CELL + (row == 3 ? 4 : 0);

				graphics.fill(x, y, x + CELL - 1, y + CELL - 1, 0xFF8B8B8B);
				graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0xFF373737);

				if (slot >= items.size()) {
					continue;
				}

				ItemStack stack = items.get(slot);

				if (stack.isEmpty()) {
					continue;
				}

				graphics.item(stack, x + 1, y + 1);
				boolean isBanner = stack.is(ItemTags.BANNERS);

				if (!isBanner) {
					graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0xA0373737); // greyed out
				} else if (slot == this.chosenSlot) {
					graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0x60FFFFFF);
				}

				if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
					hovered = stack;

					if (isBanner) {
						graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0x40FFFFFF);
					}
				}
			}
		}

		super.extractRenderState(graphics, mouseX, mouseY, delta);

		if (!hovered.isEmpty()) {
			Component tip = hovered.is(ItemTags.BANNERS)
					? hovered.getHoverName()
					: Component.literal("Only banners can be used").withColor(0xFF8080);
			graphics.setTooltipForNextFrame(this.font, tip, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
		if (super.mouseClicked(click, doubleClick)) {
			return true;
		}

		List<ItemStack> items = this.items();

		for (int row = 0; row < 4; row++) {
			for (int col = 0; col < 9; col++) {
				int x = this.gridX + col * CELL;
				int y = this.gridY + row * CELL + (row == 3 ? 4 : 0);

				if (click.x() >= x && click.x() < x + CELL && click.y() >= y && click.y() < y + CELL) {
					int slot = slotAt(row, col);

					if (slot < items.size() && items.get(slot).is(ItemTags.BANNERS)) {
						this.chosenSlot = slot;
						this.chosen = items.get(slot).copyWithCount(1);
					}

					return true;
				}
			}
		}

		// clicking the banner slot takes the banner out again
		if (click.x() >= this.slotX && click.x() < this.slotX + 26 && click.y() >= this.slotY && click.y() < this.slotY + 26) {
			this.chosenSlot = -1;
			this.chosen = ItemStack.EMPTY;
			return true;
		}

		return false;
	}
}
