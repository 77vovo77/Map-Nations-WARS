package com.mapnationswars.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

import com.mapnationswars.nation.NationData;

/** Player heads for the map and tooltips. Remembers skins, so offline players keep their face. */
final class Faces {
	private static final Map<UUID, PlayerSkin> KNOWN = new HashMap<>();

	private Faces() {
	}

	static PlayerSkin skin(UUID id) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		PlayerInfo info = connection != null ? connection.getPlayerInfo(id) : null;

		if (info != null) {
			PlayerSkin skin = info.getSkin();
			KNOWN.put(id, skin);
			return skin;
		}

		PlayerSkin known = KNOWN.get(id);
		return known != null ? known : DefaultPlayerSkin.get(id);
	}

	/** The colour of the player's nation, or white if they have none. */
	static int nationColor(UUID id) {
		NationData n = ClientNations.nationOfPlayer(id);
		return n != null ? n.color : 0xFFFFFF;
	}

	/** Face with a 1px frame in the player's nation colour. */
	static void draw(GuiGraphicsExtractor graphics, UUID id, int x, int y, int size) {
		graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF000000 | nationColor(id));
		PlayerFaceExtractor.extractRenderState(graphics, skin(id), x, y, size);
	}
}
