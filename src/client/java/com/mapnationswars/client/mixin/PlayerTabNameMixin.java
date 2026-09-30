package com.mapnationswars.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import com.mapnationswars.client.ClientNations;
import com.mapnationswars.nation.NationData;

/**
 * The player list (hold Tab): names in their nation's colour, with the leader's symbol or the officer star,
 * and the nation's name after it.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabNameMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true, require = 0)
	private void mapnationswars$nationName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		java.util.UUID id = info.getProfile().id();
		Component original = cir.getReturnValue();
		String plain = original != null ? original.getString() : info.getProfile().name();
		MutableComponent name = ClientNations.styledName(id, plain);

		if (name == null) {
			return;
		}

		NationData nation = ClientNations.nationOfPlayer(id);
		int dim = (nation.color >> 1) & 0x7F7F7F; // darker version of the nation colour
		name = name.append(Component.literal("  [" + nation.name + "]").withColor(0x808080 | dim));
		cir.setReturnValue(name);
	}
}
