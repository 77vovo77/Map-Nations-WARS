package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/** Client -> server: things one player does on their own (1.9). */
public record PersonalActionPayload(int action, String target) implements CustomPacketPayload {
	/** target = duty id */
	public static final int ABANDON_DUTY = 0;
	/** target = province id: stir up its people against their rulers (at its mayor) */
	public static final int STIR_UNREST = 1;
	/** target = "" : ask for new duties now */
	public static final int NEW_DUTY = 2;

	public static final CustomPacketPayload.Type<PersonalActionPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("personal_action"));

	public static final StreamCodec<FriendlyByteBuf, PersonalActionPayload> CODEC =
			CustomPacketPayload.codec(PersonalActionPayload::write, PersonalActionPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.action);
		buf.writeUtf(this.target);
	}

	private static PersonalActionPayload read(FriendlyByteBuf buf) {
		int action = buf.readVarInt();
		String target = buf.readUtf();
		return new PersonalActionPayload(action, target);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
