package com.mapnationswars.client;

import net.minecraft.world.item.ItemStack;

/** Added to every living entity's render state by a mixin: the helmet a villager or illager soldier wears. */
public interface WornHelmet {
	ItemStack mapnationswars$helmet();

	void mapnationswars$setHelmet(ItemStack stack);
}
