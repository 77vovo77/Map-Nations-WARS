package com.mapnationswars.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.PortalSite;

/** Server -> client: every portal and how close it is to opening (stage 8). */
public record PortalsSyncPayload(List<PortalSite> portals) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<PortalsSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(MapNationsMod.id("portals_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PortalsSyncPayload> CODEC =
			CustomPacketPayload.codec(PortalsSyncPayload::write, PortalsSyncPayload::read);

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(this.portals.size());

		for (PortalSite s : this.portals) {
			s.write(buf);
		}
	}

	private static PortalsSyncPayload read(RegistryFriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<PortalSite> list = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			list.add(PortalSite.read(buf));
		}

		return new PortalsSyncPayload(list);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
