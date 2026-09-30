package com.mapnationswars.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;

import com.mapnationswars.client.ClientNations;

/**
 * Colours player names (name tags above heads) in their nation's colour,
 * and puts the leader's symbol (crown, star, hammer and sickle...) in front of the leader's name.
 */
@Mixin(Player.class)
public abstract class PlayerDisplayNameMixin {
	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true, require = 0)
	private void mapnationswars$nationName(CallbackInfoReturnable<Component> cir) {
		Player self = (Player) (Object) this;

		if (!self.level().isClientSide()) {
			return; // only change what this client sees
		}

		MutableComponent name = ClientNations.styledName(self.getUUID(), self.getName().getString());

		if (name == null) {
			return;
		}

		cir.setReturnValue(name);
	}
}
