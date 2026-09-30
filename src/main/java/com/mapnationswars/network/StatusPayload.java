package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/** Server -> client: a short message like "Nation created!" or "That colour is taken". */
public record StatusPayload(String message, boolean success) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<StatusPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("status"));

	public static final StreamCodec<FriendlyByteBuf, StatusPayload> CODEC =
			CustomPacketPayload.codec(StatusPayload::write, StatusPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.message);
		buf.writeBoolean(this.success);
	}

	private static StatusPayload read(FriendlyByteBuf buf) {
		String msg = buf.readUtf();
		boolean ok = buf.readBoolean();
		return new StatusPayload(msg, ok);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
