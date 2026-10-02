package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/** Server -> client (2.3): is CREATIVEMOD on for you? */
public record CreativeSyncPayload(boolean on) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<CreativeSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("creative_sync"));

	public static final StreamCodec<FriendlyByteBuf, CreativeSyncPayload> CODEC =
			CustomPacketPayload.codec(CreativeSyncPayload::write, CreativeSyncPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeBoolean(this.on);
	}

	private static CreativeSyncPayload read(FriendlyByteBuf buf) {
		return new CreativeSyncPayload(buf.readBoolean());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
