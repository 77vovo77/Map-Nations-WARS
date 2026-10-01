package com.mapnationswars;

import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Helpers for real items (emeralds are the money of Map Nations WARS). */
final class WarsItems {
	private WarsItems() {
	}

	/** How many emeralds the player carries. */
	static int countEmeralds(ServerPlayer player) {
		int count = 0;

		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (stack.is(Items.EMERALD)) {
				count += stack.getCount();
			}
		}

		return count;
	}

	/** Takes up to `amount` emeralds from the inventory (amount <= 0 = all of them). Returns how many were taken. */
	static int takeEmeralds(ServerPlayer player, int amount) {
		List<ItemStack> items = player.getInventory().getNonEquipmentItems();
		int wanted = amount <= 0 ? Integer.MAX_VALUE : amount;
		int taken = 0;

		for (ItemStack stack : items) {
			if (taken >= wanted) {
				break;
			}

			if (stack.is(Items.EMERALD)) {
				int take = Math.min(stack.getCount(), wanted - taken);
				stack.shrink(take);
				taken += take;
			}
		}

		return taken;
	}
}
