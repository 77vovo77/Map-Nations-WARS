package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/** Client -> server: claim (or unclaim) every chunk in a dragged rectangle. */
public record ClaimAreaPayload(int fromX, int fromZ, int toX, int toZ, boolean claim) implements CustomPacketPayload {
	/** Biggest area one drag may cover (in chunks). */
	public static final int MAX_CHUNKS = 20;
	/** Wait between two drag claims (anti-spam). There is no land limit. */
	public static final long COOLDOWN_MS = 250;

	public static final CustomPacketPayload.Type<ClaimAreaPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("claim_area"));

	public static final StreamCodec<FriendlyByteBuf, ClaimAreaPayload> CODEC =
			CustomPacketPayload.codec(ClaimAreaPayload::write, ClaimAreaPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeInt(this.fromX);
		buf.writeInt(this.fromZ);
		buf.writeInt(this.toX);
		buf.writeInt(this.toZ);
		buf.writeBoolean(this.claim);
	}

	private static ClaimAreaPayload read(FriendlyByteBuf buf) {
		int fx = buf.readInt();
		int fz = buf.readInt();
		int tx = buf.readInt();
		int tz = buf.readInt();
		boolean claim = buf.readBoolean();
		return new ClaimAreaPayload(fx, fz, tx, tz, claim);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
