package com.mapnationswars.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;

/**
 * Client -> server: something the player wants to do with nations.
 * target = a nation id or player id (as text) depending on the action.
 * bannerSlot: CREATE: -1 = no banner, 0+ = inventory slot. EDIT: -1 = keep, -2 = remove, 0+ = new banner.
 */
public record NationActionPayload(int action, String target, String name, int color, String ideology, int bannerSlot)
		implements CustomPacketPayload {
	public static final int CREATE = 0;
	public static final int EDIT = 1;
	public static final int REQUEST_JOIN = 2;
	public static final int CANCEL_REQUEST = 3;
	public static final int ACCEPT = 4;
	public static final int DENY = 5;
	public static final int KICK = 6;
	public static final int PROMOTE = 7;
	public static final int LEAVE = 8;
	/** Alliances - target is the other nation's id. */
	public static final int PROPOSE_ALLY = 9;
	public static final int ACCEPT_ALLY = 10;
	public static final int DECLINE_ALLY = 11;
	/** Breaks an alliance, or takes back an offer we made. */
	public static final int BREAK_ALLY = 12;
	/** Ranks - target is the member's id. */
	public static final int MAKE_OFFICER = 13;
	public static final int REMOVE_OFFICER = 14;
	/** Territory proposals from officers (leader only). */
	public static final int ACCEPT_PROPOSALS = 15;
	public static final int DENY_PROPOSALS = 16;
	/** Map Nations WARS: elections and ranks. */
	public static final int RUN_FOR_OFFICE = 17;
	public static final int WITHDRAW_CANDIDACY = 18;
	/** target = member, color = new rank (leader only) */
	public static final int SET_RANK = 19;
	/** Map Nations WARS stage 7: an Officer or Minister tries to seize power. */
	public static final int COUP = 20;
	/** 2.0: color = tax level (0 low .. 3 harsh), leader and Ministers */
	public static final int SET_TAX = 21;
	/** 2.0: ideology = FESTIVAL or GRAIN (spend from the treasury) */
	public static final int TREASURY = 22;
	/** 2.1: a candidate spends 10 emeralds on their campaign */
	public static final int CAMPAIGN = 23;
	/** 2.1: target = the candidate a member votes for */
	public static final int VOTE = 24;
	/** 2.1: ideology = BRIBE or ARMY: build up a conspiracy for a coup */
	public static final int PLOT = 25;

	public static final CustomPacketPayload.Type<NationActionPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("nation_action"));

	public static final StreamCodec<FriendlyByteBuf, NationActionPayload> CODEC =
			CustomPacketPayload.codec(NationActionPayload::write, NationActionPayload::read);

	/** Shortcut for actions that only need a target. */
	public static NationActionPayload simple(int action, String target) {
		return new NationActionPayload(action, target, "", 0, "", -1);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.action);
		buf.writeUtf(this.target);
		buf.writeUtf(this.name);
		buf.writeInt(this.color);
		buf.writeUtf(this.ideology);
		buf.writeInt(this.bannerSlot);
	}

	private static NationActionPayload read(FriendlyByteBuf buf) {
		int action = buf.readVarInt();
		String target = buf.readUtf();
		String name = buf.readUtf();
		int color = buf.readInt();
		String ideology = buf.readUtf();
		int slot = buf.readInt();
		return new NationActionPayload(action, target, name, color, ideology, slot);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
