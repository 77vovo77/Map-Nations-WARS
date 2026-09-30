package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/**
 * Client -> server: something a nation leader wants to do with alliances.
 * target = an alliance id or a nation id (as text) depending on the action.
 * bannerSlot: CREATE: -1 = no banner, 0+ = inventory slot. EDIT: -1 = keep, -2 = remove, 0+ = new banner.
 */
public record AllianceActionPayload(int action, String target, String name, int color, int bannerSlot) implements CustomPacketPayload {
	public static final int CREATE = 0;
	public static final int EDIT = 1;
	/** target = alliance */
	public static final int REQUEST_JOIN = 2;
	public static final int CANCEL_REQUEST = 3;
	/** target = nation (head only) */
	public static final int ACCEPT = 4;
	public static final int DENY = 5;
	public static final int KICK = 6;
	/** Make another member nation the head of the alliance. target = nation */
	public static final int MAKE_HEAD = 7;
	/** Your nation leaves its alliance. */
	public static final int LEAVE = 8;

	public static final CustomPacketPayload.Type<AllianceActionPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("alliance_action"));

	public static final StreamCodec<FriendlyByteBuf, AllianceActionPayload> CODEC =
			CustomPacketPayload.codec(AllianceActionPayload::write, AllianceActionPayload::read);

	public static AllianceActionPayload simple(int action, String target) {
		return new AllianceActionPayload(action, target, "", 0, -1);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.action);
		buf.writeUtf(this.target);
		buf.writeUtf(this.name);
		buf.writeInt(this.color);
		buf.writeInt(this.bannerSlot);
	}

	private static AllianceActionPayload read(FriendlyByteBuf buf) {
		int action = buf.readVarInt();
		String target = buf.readUtf();
		String name = buf.readUtf();
		int color = buf.readInt();
		int slot = buf.readInt();
		return new AllianceActionPayload(action, target, name, color, slot);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
