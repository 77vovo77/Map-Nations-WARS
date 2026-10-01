package com.mapnationswars;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mapnationswars.network.AllianceActionPayload;
import com.mapnationswars.network.ClaimAreaPayload;
import com.mapnationswars.network.MarkerActionPayload;
import com.mapnationswars.network.MarkerAreaPayload;
import com.mapnationswars.network.MarkersSyncPayload;
import com.mapnationswars.network.NationActionPayload;
import com.mapnationswars.network.NationsSyncPayload;
import com.mapnationswars.network.PlayersPayload;
import com.mapnationswars.network.ProvincesSyncPayload;
import com.mapnationswars.network.OpenVillagePayload;
import com.mapnationswars.network.VillageActionPayload;
import com.mapnationswars.network.DiplomacySyncPayload;
import com.mapnationswars.network.LetterActionPayload;
import com.mapnationswars.network.StatusPayload;
import com.mapnationswars.network.TerrainRequestPayload;
import com.mapnationswars.network.TerrainTilesPayload;
import com.mapnationswars.network.ToggleClaimPayload;

/**
 * Common entrypoint (runs on both the client and the server).
 * Registers the network packets and the server-side nation system.
 */
public class MapNationsMod implements ModInitializer {
	public static final String MOD_ID = "mapnationswars";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// client -> server
		PayloadTypeRegistry.serverboundPlay().register(ToggleClaimPayload.TYPE, ToggleClaimPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ClaimAreaPayload.TYPE, ClaimAreaPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NationActionPayload.TYPE, NationActionPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(MarkerActionPayload.TYPE, MarkerActionPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(MarkerAreaPayload.TYPE, MarkerAreaPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TerrainRequestPayload.TYPE, TerrainRequestPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AllianceActionPayload.TYPE, AllianceActionPayload.CODEC);
		// server -> client
		PayloadTypeRegistry.clientboundPlay().register(NationsSyncPayload.TYPE, NationsSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PlayersPayload.TYPE, PlayersPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StatusPayload.TYPE, StatusPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MarkersSyncPayload.TYPE, MarkersSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ProvincesSyncPayload.TYPE, ProvincesSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OpenVillagePayload.TYPE, OpenVillagePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(VillageActionPayload.TYPE, VillageActionPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DiplomacySyncPayload.TYPE, DiplomacySyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LetterActionPayload.TYPE, LetterActionPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TerrainTilesPayload.TYPE, TerrainTilesPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.mapnationswars.network.WarSyncPayload.TYPE, com.mapnationswars.network.WarSyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.mapnationswars.network.ArmyActionPayload.TYPE, com.mapnationswars.network.ArmyActionPayload.CODEC);

		ServerNations.init();
		ServerAlliances.init();
		WarsWorld.init();
		WarsPolitics.init();
		WarsDiplomacy.init();
		WarsWar.init();
		WarsAI.init();
		ServerMarkers.init();
		ServerPopulation.init();
		TerrainPreview.init();
		LOGGER.info("Map Nations WARS loaded");
	}

	/** Before the rename the mod was called "chunkmap": move its old save file to the new name once. */
	public static void migrateOldFile(java.nio.file.Path newFile, java.nio.file.Path oldFile) {
		try {
			if (!java.nio.file.Files.exists(newFile) && java.nio.file.Files.exists(oldFile)) {
				java.nio.file.Files.move(oldFile, newFile);
				LOGGER.info("Moved {} to {}", oldFile.getFileName(), newFile.getFileName());
			}
		} catch (java.io.IOException e) {
			LOGGER.warn("Could not move old save file {}", oldFile, e);
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Packs two chunk coordinates into one long (used as a map key). */
	public static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	public static int keyX(long key) {
		return (int) (key >> 32);
	}

	public static int keyZ(long key) {
		return (int) key;
	}
}
