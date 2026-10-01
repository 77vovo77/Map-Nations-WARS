package com.mapnationswars.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.NationData;

/** Server -> client: you spoke to a king - open the royal court of that nation (2.0). */
public record CourtPayload(UUID nation, String greeting) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<CourtPayload> TYPE = new CustomPacketPayload.Type<>(MapNationsMod.id("court"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CourtPayload> CODEC = CustomPacketPayload.codec(CourtPayload::write, CourtPayload::read);

	private void write(RegistryFriendlyByteBuf buf) {
		NationData.writeUuid(buf, this.nation);
		buf.writeUtf(this.greeting);
	}

	private static CourtPayload read(RegistryFriendlyByteBuf buf) {
		return new CourtPayload(NationData.readUuid(buf), buf.readUtf());
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
