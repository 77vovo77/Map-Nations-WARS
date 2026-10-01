package com.mapnationswars.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

import com.mapnationswars.MapNationsMod;

/** Your own settings (saved in .minecraft/config/mapnationswars-client.properties). */
final class ClientConfig {
	private static boolean loaded = false;
	/** The small map in a corner (top-left by default). Off until you turn it on in the map. */
	private static boolean minimap = false;
	/** 0 = bottom-left (chat is drawn over it), 1 = top-left, 2 = top-right */
	private static int minimapCorner = 1; // top-left: the bottom corners covered the player's arm
	/** 0 = small, 1 = medium, 2 = large */
	private static int minimapSize = 0;
	static final String[] CORNERS = {"Bottom-left", "Top-left", "Top-right"};
	static final String[] SIZES = {"Small", "Medium", "Large"};

	private ClientConfig() {
	}

	static boolean minimap() {
		load();
		return minimap;
	}

	static void setMinimap(boolean on) {
		load();
		minimap = on;
		save();
	}

	static int minimapCorner() {
		load();
		return minimapCorner;
	}

	static void cycleMinimapCorner() {
		load();
		minimapCorner = (minimapCorner + 1) % CORNERS.length;
		save();
	}

	static int minimapSize() {
		load();
		return minimapSize;
	}

	static void cycleMinimapSize() {
		load();
		minimapSize = (minimapSize + 1) % SIZES.length;
		save();
	}

	private static Path file() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("mapnationswars-client.properties");
		return file;
	}

	private static void load() {
		if (loaded) {
			return;
		}

		loaded = true;
		Path file = file();

		if (!Files.exists(file)) {
			return;
		}

		Properties props = new Properties();

		try (Reader in = Files.newBufferedReader(file)) {
			props.load(in);
			minimap = Boolean.parseBoolean(props.getProperty("minimap", "false"));
			minimapCorner = Math.floorMod(parseInt(props.getProperty("minimapPlace"), 1), CORNERS.length); // new key in 2.0: everyone starts top-left
			minimapSize = Math.floorMod(parseInt(props.getProperty("minimapSize"), 0), SIZES.length);
		} catch (IOException e) {
			MapNationsMod.LOGGER.warn("Could not read {}", file, e);
		}
	}

	private static void save() {
		Path file = file();
		Properties props = new Properties();
		props.setProperty("minimap", Boolean.toString(minimap));
		props.setProperty("minimapPlace", Integer.toString(minimapCorner));
		props.setProperty("minimapSize", Integer.toString(minimapSize));

		try {
			Files.createDirectories(file.getParent());

			try (Writer out = Files.newBufferedWriter(file)) {
				props.store(out, "Map Nations WARS settings");
			}
		} catch (IOException e) {
			MapNationsMod.LOGGER.warn("Could not save {}", file, e);
		}
	}

	private static int parseInt(String s, int fallback) {
		try {
			return s == null ? fallback : Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
