package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/** Sent by the client when the player clicks a chunk in claim mode. */
public record ToggleClaimPayload(int chunkX, int chunkZ) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ToggleClaimPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("toggle_claim"));

	public static final StreamCodec<FriendlyByteBuf, ToggleClaimPayload> CODEC =
			CustomPacketPayload.codec(ToggleClaimPayload::write, ToggleClaimPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeInt(this.chunkX);
		buf.writeInt(this.chunkZ);
	}

	private static ToggleClaimPayload read(FriendlyByteBuf buf) {
		int x = buf.readInt();
		int z = buf.readInt();
		return new ToggleClaimPayload(x, z);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
