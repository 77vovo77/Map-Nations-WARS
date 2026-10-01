package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/** Client -> server: orders for the army. */
public record ArmyActionPayload(int action, String id, String argument, int x, int z) implements CustomPacketPayload {
	/** id = province to raise it in, argument = kind (INFANTRY, CAVALRY, ARCHERS, SIEGE) */
	public static final int RAISE = 0;
	/** id = division, x / z = where to go, argument = province id to go to (or "") */
	public static final int MOVE = 1;
	/** id = division */
	public static final int HALT = 2;
	public static final int DISBAND = 3;
	/** id = division, x = how many soldiers to hire */
	public static final int HIRE = 4;
	/** id = division: split it in two */
	public static final int SPLIT = 5;
	/** id = division: the nearest division of the same kind joins it */
	public static final int MERGE = 6;

	public static final CustomPacketPayload.Type<ArmyActionPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("army_action"));

	public static final StreamCodec<FriendlyByteBuf, ArmyActionPayload> CODEC =
			CustomPacketPayload.codec(ArmyActionPayload::write, ArmyActionPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.action);
		buf.writeUtf(this.id);
		buf.writeUtf(this.argument);
		buf.writeInt(this.x);
		buf.writeInt(this.z);
	}

	private static ArmyActionPayload read(FriendlyByteBuf buf) {
		int action = buf.readVarInt();
		String id = buf.readUtf();
		String argument = buf.readUtf();
		int x = buf.readInt();
		int z = buf.readInt();
		return new ArmyActionPayload(action, id, argument, x, z);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
