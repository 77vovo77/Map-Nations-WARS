package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/** Client -> server: send a letter (target = nation; personal = from you, not your nation), or answer one (target = letter id). */
public record LetterActionPayload(int action, String target, String letterType, int amount, String text, boolean personal) implements CustomPacketPayload {
	public static final int SEND = 0;
	public static final int ACCEPT = 1;
	public static final int REFUSE = 2;

	public static final CustomPacketPayload.Type<LetterActionPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("letter_action"));

	public static final StreamCodec<FriendlyByteBuf, LetterActionPayload> CODEC =
			CustomPacketPayload.codec(LetterActionPayload::write, LetterActionPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.action);
		buf.writeUtf(this.target);
		buf.writeUtf(this.letterType);
		buf.writeVarInt(this.amount);
		buf.writeUtf(this.text);
		buf.writeBoolean(this.personal);
	}

	private static LetterActionPayload read(FriendlyByteBuf buf) {
		int action = buf.readVarInt();
		String target = buf.readUtf();
		String type = buf.readUtf();
		int amount = buf.readVarInt();
		String text = buf.readUtf(300);
		boolean personal = buf.readBoolean();
		return new LetterActionPayload(action, target, type, amount, text, personal);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
