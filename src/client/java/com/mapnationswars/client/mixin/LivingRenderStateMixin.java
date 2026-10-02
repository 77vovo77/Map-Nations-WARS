package com.mapnationswars.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.item.ItemStack;

import com.mapnationswars.client.WornHelmet;

/** Gives every living entity's render state a place for the helmet (see HelmetLayer). */
@Mixin(LivingEntityRenderState.class)
public abstract class LivingRenderStateMixin implements WornHelmet {
	@Unique
	private ItemStack mapnationswars$helmet = ItemStack.EMPTY;

	@Override
	public ItemStack mapnationswars$helmet() {
		return mapnationswars$helmet;
	}

	@Override
	public void mapnationswars$setHelmet(ItemStack stack) {
		mapnationswars$helmet = stack;
	}
}
