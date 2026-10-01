package com.mapnationswars.client;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.sdl.SDLScancode;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.KeyMapping;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.network.MarkersSyncPayload;
import com.mapnationswars.network.NationsSyncPayload;
import com.mapnationswars.network.PlayersPayload;
import com.mapnationswars.network.ProvincesSyncPayload;
import com.mapnationswars.network.StatusPayload;
import com.mapnationswars.network.TerrainTilesPayload;

/** Client entrypoint: the M key, map scanning, nation data from the server and the territory messages. */
public class MapNationsClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(MapNationsMod.id("map"));

	public static final KeyMapping OPEN_MAP = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.mapnationswars.open_map",
			InputConstants.Type.KEYBOARD,
			SDLScancode.SDL_SCANCODE_M,
			CATEGORY));

	@Override
	public void onInitializeClient() {
		TerritoryHud.register();
		Minimap.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Keeps drawing the explored terrain into the map (and saves / unloads it when you leave).
			MapData.tick(client);
			TerritoryHud.tick(client);

			while (OPEN_MAP.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					client.gui.setScreen(new MapScreen(false));
				}
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(NationsSyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientNations.apply(payload)));

		ClientPlayNetworking.registerGlobalReceiver(PlayersPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientNations.applyPlayers(payload)));

		ClientPlayNetworking.registerGlobalReceiver(StatusPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientNations.setStatus(payload.message(), payload.success())));

		ClientPlayNetworking.registerGlobalReceiver(MarkersSyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientMarkers.apply(payload)));

		ClientPlayNetworking.registerGlobalReceiver(ProvincesSyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientMarkers.applyProvinces(payload)));

		ClientPlayNetworking.registerGlobalReceiver(com.mapnationswars.network.DiplomacySyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientDiplomacy.apply(payload)));
		ClientPlayNetworking.registerGlobalReceiver(com.mapnationswars.network.WarSyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientWar.apply(payload)));

		// talked to a mayor: open the village page
		ClientPlayNetworking.registerGlobalReceiver(com.mapnationswars.network.OpenVillagePayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					try {
						java.util.UUID id = java.util.UUID.fromString(payload.province());
						context.client().gui.setScreen(new VillageScreen(null, id, true));
					} catch (IllegalArgumentException ignored) {
					}
				}));

		ClientPlayNetworking.registerGlobalReceiver(TerrainTilesPayload.TYPE, (payload, context) ->
				context.client().execute(() -> MapData.applyTiles(context.client(), payload)));

		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) ->
				client.execute(() -> {
					ClientNations.clear();
					ClientMarkers.clear();
					MapData.onDisconnect();
					ClientDiplomacy.clear();
					ClientWar.clear();
				}));
	}
}
