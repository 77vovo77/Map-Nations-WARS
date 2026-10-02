package com.mapnationswars.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;

import com.mapnationswars.client.WornHelmet;

/** Copies a villager's or illager's helmet into its render state, so HelmetLayer can draw it. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingRendererHelmetMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void mapnationswars$helmet(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
		if (state instanceof WornHelmet worn) {
			worn.mapnationswars$setHelmet(entity instanceof Villager || entity instanceof AbstractIllager ? entity.getItemBySlot(EquipmentSlot.HEAD) : ItemStack.EMPTY);
		}
	}
}
