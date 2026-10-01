package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/** Client -> server: something done at a village (give emeralds, order a building). */
public record VillageActionPayload(String province, int action, String argument, int amount) implements CustomPacketPayload {
	/** argument = building name (HOUSE, FARM, WORKSHOP) */
	public static final int ORDER = 0;
	/** amount = emeralds (0 = all you carry) */
	public static final int DONATE = 1;

	public static final CustomPacketPayload.Type<VillageActionPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("village_action"));

	public static final StreamCodec<FriendlyByteBuf, VillageActionPayload> CODEC =
			CustomPacketPayload.codec(VillageActionPayload::write, VillageActionPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.province);
		buf.writeVarInt(this.action);
		buf.writeUtf(this.argument);
		buf.writeVarInt(this.amount);
	}

	private static VillageActionPayload read(FriendlyByteBuf buf) {
		String province = buf.readUtf();
		int action = buf.readVarInt();
		String argument = buf.readUtf();
		int amount = buf.readVarInt();
		return new VillageActionPayload(province, action, argument, amount);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
