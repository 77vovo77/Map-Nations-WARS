package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/**
 * Client -> server (2.3): CREATIVEMOD, for players in creative mode.
 * nation = the nation picked in the creative panel, target = another nation / a province / a division / an army kind.
 */
public record CreativeActionPayload(int action, String nation, String target, int x1, int z1, int x2, int z2) implements CustomPacketPayload {
	public static final int TOGGLE = 0;
	/** chunks x1,z1 .. x2,z2 become the nation's land ("" nation = nobody's) */
	public static final int PAINT = 1;
	/** target = province: it now belongs to the nation */
	public static final int GIVE_PROVINCE = 2;
	/** target = army kind, x1 / z1 = where: a full army of the nation appears */
	public static final int SPAWN_ARMY = 3;
	/** target = division: it is gone */
	public static final int DELETE_ARMY = 4;
	/** you become the leader of the nation */
	public static final int LEAD = 5;
	/** x1 = emeralds added to the treasury */
	public static final int TREASURY = 6;
	/** target = the other nation */
	public static final int WAR = 7;
	public static final int PEACE = 8;
	public static final int ALLY = 9;
	/** every province of the nation: no unrest, happy, fed */
	public static final int CALM = 10;
	/** target = province: more villagers, houses, farms, workshops */
	public static final int BOOST = 11;
	/** target = division, x1 / z1 = where: the army is there at once */
	public static final int TELEPORT_ARMY = 12;

	public static final CustomPacketPayload.Type<CreativeActionPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("creative_action"));

	public static final StreamCodec<FriendlyByteBuf, CreativeActionPayload> CODEC =
			CustomPacketPayload.codec(CreativeActionPayload::write, CreativeActionPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.action);
		buf.writeUtf(this.nation);
		buf.writeUtf(this.target);
		buf.writeInt(this.x1);
		buf.writeInt(this.z1);
		buf.writeInt(this.x2);
		buf.writeInt(this.z2);
	}

	private static CreativeActionPayload read(FriendlyByteBuf buf) {
		return new CreativeActionPayload(buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
