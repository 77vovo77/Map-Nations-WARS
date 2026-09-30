package com.mapnationswars.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Matrix3x2fStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.MarkerData;
import com.mapnationswars.nation.MarkerType;
import com.mapnationswars.nation.NationData;

/**
 * Vanilla-map-style pixel icons (16x16) for markers.
 * How a marker looks tells you who can see it:
 *  - Everyone: just the icon
 *  - My nation: the icon outlined in the nation's colour
 *  - Only me: your head peeking out behind the icon
 * Important places also show a small banner of the nation that owns the land, in the background.
 */
final class MarkerIcons {
	static final int SPRITE = 16;
	/** Index of the "!" conflict sprite (after all marker types). */
	static final int CONFLICT = MarkerType.values().length;

	// One 16x16 picture per MarkerType (same order as the enum), then the conflict sign.
	private static final String[][] SPRITES = {
		// CAPITAL
		{
			"................",
			".......kk.......",
			"......kyyk......",
			".kk...kyyk...kk.",
			".kyk..kyyk..kyk.",
			".kyyk.kyyk.kyyk.",
			".kyyykyyyykyyyk.",
			".kyyyyyyyyyyyyk.",
			".kyryyyccyyyryk.",
			".kyyyyyyyyyyyyk.",
			".kYYYYYYYYYYYYk.",
			".kyyyyyyyyyyyyk.",
			".kYYYYYYYYYYYYk.",
			".kkkkkkkkkkkkkk.",
			"................",
			"................"
		},
		// CITY
		{
			"................",
			"......kkkkk.....",
			"......kclck.....",
			"......klllk.....",
			"......kclck.....",
			".kkkkkklllk.....",
			".kcllckclck.....",
			".kllllklllkkkkk.",
			".kcllckclckclck.",
			".kllllklllklllk.",
			".kcllckclckclck.",
			".kllllklllklllk.",
			".kcllckclckclck.",
			".kllllklllklllk.",
			".kkkkkkkkkkkkkk.",
			"................"
		},
		// CASTLE
		{
			"................",
			"......kkkk......",
			"......kssk......",
			".kkkk.kGGk.kkkk.",
			".kssk.kssk.kssk.",
			".kGsk.kssk.ksGk.",
			".ksskkksskkkssk.",
			".kssssssssssssk.",
			".ksgssssssssgsk.",
			".ksssskkkkssssk.",
			".ksssskBBkssssk.",
			".ksssskBBkssssk.",
			".ksssskBBkssssk.",
			".kkkkkkkkkkkkkk.",
			"................",
			"................"
		},
		// FORT
		{
			"................",
			".......kk.......",
			".......krk......",
			".......krrk.....",
			".......krrrk....",
			".......kkkk.....",
			".......k........",
			"..kk.kkkkk.kk...",
			".kbbkbbkbbkbbk..",
			".kbBkbBkbBkbBk..",
			".kbBkbBkbBkbBk..",
			".kbBkkkkkkkbBk..",
			".kbBkGGGGGkbBk..",
			".kbBkGGGGGkbBk..",
			".kkkkkkkkkkkkk..",
			"................"
		},
		// VILLAGE
		{
			"................",
			"................",
			"....kk..........",
			"...krrk.........",
			"..krrrrk........",
			".krrrrrrk.......",
			"krrrrrrrrk..kk..",
			"kkkkkkkkkk.krrk.",
			".kbbbbbbk.krrrrk",
			".kbwwbbbkkkkkkkk",
			".kbwwbbbk.kbbbk.",
			".kbbbBBbk.kbBbk.",
			".kbbbBBbk.kbBbk.",
			".kkkkkkkkkkkkkk.",
			"................",
			"................"
		},
		// PORT
		{
			"................",
			"......kkkk......",
			".....kskksk.....",
			"......kssk......",
			"...kssssssssk...",
			"...kkkkssskkk...",
			"......kssk......",
			"......kssk......",
			"......kssk......",
			".k....kssk....k.",
			".ksk..kssk..ksk.",
			".kssk.kssk.kssk.",
			"..kssssssssssk..",
			"...kkkkkkkkkk...",
			"................",
			"................"
		},
		// MARKET
		{
			"................",
			".kkkkkkkkkkkkkk.",
			".krwwrrwwrrwwrk.",
			".krwwrrwwrrwwrk.",
			".kkkkkkkkkkkkkk.",
			"..kbk......kbk..",
			"..kbk......kbk..",
			"..kbk......kbk..",
			"..kbkooyyeekbk..",
			".kkkkkkkkkkkkkk.",
			".kbbbbbbbbbbbbk.",
			".kBBBBBBBBBBBBk.",
			".kbbbbbbbbbbbbk.",
			".kkkkkkkkkkkkkk.",
			"................",
			"................"
		},
		// TEMPLE
		{
			"................",
			".......kk.......",
			"......kyyk......",
			".....kllllk.....",
			"....kllllllk....",
			"...kllllllllk...",
			"..kkkkkkkkkkkk..",
			"...klk.klk.klk..",
			"...klk.klk.klk..",
			"...klk.klk.klk..",
			"...klk.klk.klk..",
			"..kkkkkkkkkkkk..",
			"..kssssssssssk..",
			"..kkkkkkkkkkkk..",
			"................",
			"................"
		},
		// FARM
		{
			"................",
			"..kk...kk...kk..",
			".kyyk.kyyk.kyyk.",
			".kYyk.kYyk.kYyk.",
			".kyYk.kyYk.kyYk.",
			".kYyk.kYyk.kYyk.",
			"..kk...kk...kk..",
			"..ke...ke...ke..",
			".kee..kee..kee..",
			"..ek...ek...ek..",
			"kkkkkkkkkkkkkkk.",
			"kBbBbBbBbBbBbBk.",
			"kbBbBbBbBbBbBbk.",
			"kkkkkkkkkkkkkkk.",
			"................",
			"................"
		},
		// MINE
		{
			"................",
			"................",
			".kkkkkkkkkkkkk..",
			".kbbbbbbbbbbbk..",
			".kBBBBBBBBBBBk..",
			".kbbkkkkkkkbbk..",
			".kbbkPPPPPkbbk..",
			".kbbkPPPPPkbbk..",
			".kbbkPPPPPkbbk..",
			".kbbkPPPPPkbbk..",
			".kbbkPPPPPkbbk..",
			".kbbkPGPGPkbbk..",
			".kbbkPGPGPkbbk..",
			"kkkkkkGkGkkkkkk.",
			"................",
			"................"
		},
		// HOME
		{
			"................",
			"..........kk....",
			".......kk.kGk...",
			"......kcckkGk...",
			".....kcccckGk...",
			"....kcccccck....",
			"...kcccccccck...",
			"..kcccccccccck..",
			".kkkkkkkkkkkkkk.",
			"..kllllllllllk..",
			"..klrrllllBBlk..",
			"..klrrllllBBlk..",
			"..klllllllBBlk..",
			"..kkkkkkkkkkkk..",
			"................",
			"................"
		},
		// PORTAL
		{
			"................",
			"................",
			"...kkkkkkkkkk...",
			"...kPPPPPPPPk...",
			"...kPppppppPk...",
			"...kPpwppppPk...",
			"...kPppppppPk...",
			"...kPpppwppPk...",
			"...kPppppppPk...",
			"...kPppppppPk...",
			"...kPpwppppPk...",
			"...kPppppppPk...",
			"...kPPPPPPPPk...",
			"...kkkkkkkkkk...",
			"................",
			"................"
		},
		// DANGER
		{
			"................",
			".....kkkkkk.....",
			"....kllllllk....",
			"...kllllllllk...",
			"..kllllllllllk..",
			"..kllllllllllk..",
			"..klkkkllkkklk..",
			"..klkkkllkkklk..",
			"..klkkkllkkklk..",
			"..kllllkkllllk..",
			"...kllllllllk...",
			"....kllllllk....",
			"....klklklkk....",
			"....kkkkkkk.....",
			"................",
			"................"
		},
		// LANDMARK
		{
			"................",
			"................",
			".......kk.......",
			"......kyyk......",
			"......kyyk......",
			".kkkkkkyykkkkkk.",
			".kyyyyyyyyyyyyk.",
			"..kyyyyyyyyyyk..",
			"...kyyyyyyyyk...",
			"....kyyyyyyk....",
			"....kyyyyyyk....",
			"...kyyykkyyyk...",
			"..kyykk..kkyyk..",
			"..kkk......kkk..",
			"................",
			"................"
		},
		// ARROW_N
		{
			"................",
			"................",
			".......kk.......",
			"......kwwk......",
			".....kwwwwk.....",
			"....kwwwwwwk....",
			"...kwwwwwwwwk...",
			"..kkkkwwwwkkkk..",
			".....kwwwwk.....",
			".....kwwwwk.....",
			".....kwwwwk.....",
			".....kwwwwk.....",
			".....kwwwwk.....",
			".....kkkkkk.....",
			"................",
			"................"
		},
		// ARROW_NE
		{
			"................",
			".....kkkkkkkkk..",
			".....kwwwwwwwk..",
			"......kwwwwwwk..",
			".......kwwwwwk..",
			"......kwwwwwwk..",
			".....kwwwwwwwk..",
			"....kwwwwkkwwk..",
			"...kwwwwk..kwk..",
			"..kwwwwk....k...",
			".kwwwwk.........",
			".kwwwk..........",
			"..kwk...........",
			"...k............",
			"................",
			"................"
		},
		// ARROW_E
		{
			"................",
			"................",
			"........k.......",
			"........kk......",
			"........kwk.....",
			"..kkkkkkkwwk....",
			"..kwwwwwwwwwk...",
			"..kwwwwwwwwwwk..",
			"..kwwwwwwwwwwk..",
			"..kwwwwwwwwwk...",
			"..kkkkkkkwwk....",
			"........kwk.....",
			"........kk......",
			"........k.......",
			"................",
			"................"
		},
		// ARROW_SE
		{
			"................",
			"...k............",
			"..kwk...........",
			".kwwwk..........",
			".kwwwwk.........",
			"..kwwwwk....k...",
			"...kwwwwk..kwk..",
			"....kwwwwkkwwk..",
			".....kwwwwwwwk..",
			"......kwwwwwwk..",
			".......kwwwwwk..",
			"......kwwwwwwk..",
			".....kwwwwwwwk..",
			".....kkkkkkkkk..",
			"................",
			"................"
		},
		// ARROW_S
		{
			"................",
			"................",
			".....kkkkkk.....",
			".....kwwwwk.....",
			".....kwwwwk.....",
			".....kwwwwk.....",
			".....kwwwwk.....",
			".....kwwwwk.....",
			"..kkkkwwwwkkkk..",
			"...kwwwwwwwwk...",
			"....kwwwwwwk....",
			".....kwwwwk.....",
			"......kwwk......",
			".......kk.......",
			"................",
			"................"
		},
		// ARROW_SW
		{
			"................",
			"...........k....",
			"..........kwk...",
			".........kwwwk..",
			"........kwwwwk..",
			"..k....kwwwwk...",
			".kwk..kwwwwk....",
			".kwwkkwwwwk.....",
			".kwwwwwwwk......",
			".kwwwwwwk.......",
			".kwwwwwk........",
			".kwwwwwwk.......",
			".kwwwwwwwk......",
			".kkkkkkkkk......",
			"................",
			"................"
		},
		// ARROW_W
		{
			"................",
			"................",
			".......k........",
			"......kk........",
			".....kwk........",
			"....kwwkkkkkkk..",
			"...kwwwwwwwwwk..",
			"..kwwwwwwwwwwk..",
			"..kwwwwwwwwwwk..",
			"...kwwwwwwwwwk..",
			"....kwwkkkkkkk..",
			".....kwk........",
			"......kk........",
			".......k........",
			"................",
			"................"
		},
		// ARROW_NW
		{
			"................",
			".kkkkkkkkk......",
			".kwwwwwwwk......",
			".kwwwwwwk.......",
			".kwwwwwk........",
			".kwwwwwwk.......",
			".kwwwwwwwk......",
			".kwwkkwwwwk.....",
			".kwk..kwwwwk....",
			"..k....kwwwwk...",
			"........kwwwwk..",
			".........kwwwk..",
			"..........kwk...",
			"...........k....",
			"................",
			"................"
		},
		// X_MARK
		{
			"................",
			"..kk........kk..",
			".krrk......krrk.",
			".krrrk....krrrk.",
			"..krrrk..krrrk..",
			"...krrrkkrrrk...",
			"....krrrrrrk....",
			".....krrrrk.....",
			"....krrrrrrk....",
			"...krrrkkrrrk...",
			"..krrrk..krrrk..",
			".krrrk....krrrk.",
			".krrk......krrk.",
			"..kk........kk..",
			"................",
			"................"
		},
		// PLUS
		{
			"................",
			"................",
			"......kkkk......",
			"......kwwk......",
			"......kwwk......",
			"......kwwk......",
			"..kkkkkwwkkkkk..",
			"..kwwwwwwwwwwk..",
			"..kwwwwwwwwwwk..",
			"..kkkkkwwkkkkk..",
			"......kwwk......",
			"......kwwk......",
			"......kwwk......",
			"......kkkk......",
			"................",
			"................"
		},
		// CIRCLE
		{
			"................",
			"................",
			".....kkkkkk.....",
			"....kwwwwwwk....",
			"...kwwkkkkwwk...",
			"..kwwk....kwwk..",
			"..kwk......kwk..",
			"..kwk......kwk..",
			"..kwk......kwk..",
			"..kwk......kwk..",
			"..kwwk....kwwk..",
			"...kwwkkkkwwk...",
			"....kwwwwwwk....",
			".....kkkkkk.....",
			"................",
			"................"
		},
		// CHECK
		{
			"................",
			"................",
			"............kk..",
			"...........keek.",
			"..........keeek.",
			".........keeek..",
			"..kk....keeek...",
			".keek..keeek....",
			".keeekkeeek.....",
			"..keeeeeeek.....",
			"...keeeeek......",
			"....keeek.......",
			".....kek........",
			"......k.........",
			"................",
			"................"
		},
		// CONFLICT
		{
			"................",
			".......rr.......",
			"......ryyr......",
			"......ryyr......",
			".....ryyyyr.....",
			".....rykkyr.....",
			"....ryykkyyr....",
			"....ryykkyyr....",
			"...ryyykkyyyr...",
			"...ryyykkyyyr...",
			"..ryyyyyyyyyyr..",
			"..ryyyykkyyyyr..",
			".ryyyyykkyyyyyr.",
			".rrrrrrrrrrrrrr.",
			"................",
			"................"
		}
	};

	private static Identifier atlas;
	private static final Map<Long, Identifier> OUTLINES = new HashMap<>();

	private MarkerIcons() {
	}

	private static int color(char c) {
		return switch (c) {
			case 'k' -> 0xFF1B1B1B;
			case 'w' -> 0xFFFFFFFF;
			case 'l' -> 0xFFE0E0E0;
			case 's' -> 0xFFBDBDBD;
			case 'g' -> 0xFF9E9E9E;
			case 'G' -> 0xFF616161;
			case 'y' -> 0xFFF2C230;
			case 'Y' -> 0xFFB8860B;
			case 'r' -> 0xFFD32F2F;
			case 'R' -> 0xFF8E1B1B;
			case 'b' -> 0xFF9C6B43;
			case 'B' -> 0xFF5D4037;
			case 'c' -> 0xFF4FA3E0;
			case 'C' -> 0xFF1E5AA8;
			case 'e' -> 0xFF7CB342;
			case 'E' -> 0xFF33691E;
			case 'p' -> 0xFFB04BD0;
			case 'P' -> 0xFF3A1452;
			case 'o' -> 0xFFFB8C00;
			default -> 0;
		};
	}

	private static char at(int sprite, int x, int y) {
		return x >= 0 && y >= 0 && x < SPRITE && y < SPRITE ? SPRITES[sprite][y].charAt(x) : '.';
	}

	private static int scale(int rgb, double f) {
		int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * f);
		int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * f);
		int b = (int) Math.min(255, (rgb & 0xFF) * f);
		return (r << 16) | (g << 8) | b;
	}

	/**
	 * The colour of one pixel, drawn like Minecraft item textures: outlines are a dark shade of the colour next to them
	 * (not plain black), the top-left edges catch the light and the bottom edges are a bit darker.
	 */
	private static int shaded(int sprite, int x, int y) {
		char c = at(sprite, x, y);

		if (c == '.') {
			return 0;
		}

		if (c == 'k') {
			int r = 0;
			int g = 0;
			int b = 0;
			int n = 0;
			int[][] near = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};

			for (int i = 0; i < near.length; i++) {
				char o = at(sprite, x + near[i][0], y + near[i][1]);

				if (o != '.' && o != 'k' && (i < 4 || n == 0)) {
					int col = color(o);
					r += (col >> 16) & 0xFF;
					g += (col >> 8) & 0xFF;
					b += col & 0xFF;
					n++;
				}
			}

			if (n == 0) {
				return 0xFF2A2624;
			}

			int avg = ((r / n) << 16) | ((g / n) << 8) | (b / n);
			int dark = scale(avg, 0.42);
			int lum = (((dark >> 16) & 0xFF) * 3 + ((dark >> 8) & 0xFF) * 6 + (dark & 0xFF)) / 10;

			if (lum > 64) {
				dark = scale(dark, 64.0 / lum); // bright things (white arrows) still get a strong edge
			}

			return 0xFF000000 | dark;
		}

		int col = color(c) & 0xFFFFFF;
		char up = at(sprite, x, y - 1);
		char left = at(sprite, x - 1, y);
		char down = at(sprite, x, y + 1);

		if (up == '.' || up == 'k' || left == '.' || left == 'k') {
			col = scale(col, 1.12);
		} else if (down == '.' || down == 'k') {
			col = scale(col, 0.86);
		}

		return 0xFF000000 | col;
	}

	private static boolean opaque(int sprite, int x, int y) {
		return x >= 0 && y >= 0 && x < SPRITE && y < SPRITE && SPRITES[sprite][y].charAt(x) != '.';
	}

	private static Identifier register(Minecraft mc, Identifier id, NativeImage image) {
		DynamicTexture texture = new DynamicTexture(id::toString, image);
		mc.getTextureManager().register(id, texture);
		texture.upload();
		return id;
	}

	private static Identifier atlas(Minecraft mc) {
		if (atlas == null) {
			NativeImage image = new NativeImage(SPRITE * SPRITES.length, SPRITE, false);

			for (int i = 0; i < SPRITES.length; i++) {
				for (int y = 0; y < SPRITE; y++) {
					for (int x = 0; x < SPRITE; x++) {
						image.setPixel(i * SPRITE + x, y, shaded(i, x, y));
					}
				}
			}

			atlas = register(mc, MapNationsMod.id("marker_icons"), image);
		}

		return atlas;
	}

	/** A 1-pixel outline around the sprite's shape, in one colour. */
	private static Identifier outline(Minecraft mc, int sprite, int rgb) {
		long key = ((long) sprite << 32) | (rgb & 0xFFFFFFL);
		Identifier id = OUTLINES.get(key);

		if (id == null) {
			NativeImage image = new NativeImage(SPRITE, SPRITE, false);

			for (int y = 0; y < SPRITE; y++) {
				for (int x = 0; x < SPRITE; x++) {
					boolean edge = !opaque(sprite, x, y)
							&& (opaque(sprite, x - 1, y) || opaque(sprite, x + 1, y) || opaque(sprite, x, y - 1) || opaque(sprite, x, y + 1)
							|| opaque(sprite, x - 1, y - 1) || opaque(sprite, x + 1, y - 1) || opaque(sprite, x - 1, y + 1) || opaque(sprite, x + 1, y + 1));
					image.setPixel(x, y, edge ? 0xFF000000 | rgb : 0);
				}
			}

			id = register(mc, MapNationsMod.id("marker_outline/" + OUTLINES.size()), image);
			OUTLINES.put(key, id);
		}

		return id;
	}

	/** Size on screen (GUI pixels) at normal zoom. The map shrinks them when zoomed out. */
	static int size(MarkerType type) {
		return switch (type) {
			case CAPITAL -> 16;
			case CITY, CASTLE -> 14;
			default -> 12;
		};
	}

	private static void blitSprite(GuiGraphicsExtractor graphics, Identifier texture, int u, int textureWidth, float cx, float cy, int size) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.scale(size / (float) SPRITE);
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, -SPRITE / 2, -SPRITE / 2, (float) u, 0.0F, SPRITE, SPRITE, textureWidth, SPRITE);
		pose.popMatrix();
	}

	/** Draws one sprite centred on (cx, cy). */
	static void sprite(GuiGraphicsExtractor graphics, int index, float cx, float cy, int size) {
		blitSprite(graphics, atlas(Minecraft.getInstance()), index * SPRITE, SPRITE * SPRITES.length, cx, cy, size);
	}

	/** The nation that owns the land a marker stands on (null = wilderness). */
	static NationData owner(MarkerData m) {
		return ClientNations.nationAt(m.dimension, Math.floorDiv(m.x, 16), Math.floorDiv(m.z, 16));
	}

	static void draw(GuiGraphicsExtractor graphics, MarkerData m, float cx, float cy, int size) {
		draw(graphics, m.type, owner(m), m.visibility, m.owner, m.nation, cx, cy, size);
	}

	/**
	 * Draws a marker: background banner / head first, then the nation outline, then the icon.
	 * nation = the nation the marker is shared with (for "My nation" markers).
	 */
	static void draw(GuiGraphicsExtractor graphics, MarkerType type, NationData landOwner, int visibility, UUID placedBy,
			UUID nation, float cx, float cy, int size) {
		boolean background = false;

		if (type.showsBanner && landOwner != null) {
			drawSmallBanner(graphics, landOwner, cx + size * 0.38f, cy - size * 0.42f, size);
			background = true;
		}

		if (visibility == MarkerType.PRIVATE && placedBy != null) {
			int h = Math.max(6, Math.round(size * 0.6f));
			int hx = Math.round(cx + size * 0.12f);
			int hy = Math.round(cy + size * 0.02f);
			graphics.fill(hx - 1, hy - 1, hx + h + 1, hy + h + 1, 0xFF1B1B1B);
			PlayerFaceExtractor.extractRenderState(graphics, Faces.skin(placedBy), hx, hy, h);
			background = true;
		}

		if (background) {
			graphics.nextStratum(); // the icon goes in front
		}

		if (visibility == MarkerType.NATION && !type.alwaysPublic) {
			NationData shared = ClientNations.get(nation);
			int rgb = shared != null ? shared.color : 0x4FC3F7;
			blitSprite(graphics, outline(Minecraft.getInstance(), type.ordinal(), rgb), 0, SPRITE, cx, cy, size);
		}

		sprite(graphics, type.ordinal(), cx, cy, size);
	}

	/** A small banner behind the icon, just to show whose it is. Nations without a banner get a little flag. */
	static void drawSmallBanner(GuiGraphicsExtractor graphics, NationData nation, float centerX, float centerY, int iconSize) {
		float scale = Math.max(0.3f, iconSize / 34f);

		if (!nation.banner.isEmpty()) {
			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(centerX, centerY);
			pose.scale(scale);
			graphics.item(nation.banner, -8, -8);
			pose.popMatrix();
		} else {
			int x = Math.round(centerX) - 2;
			int y = Math.round(centerY) - 3;
			int w = Math.max(3, Math.round(iconSize * 0.3f));
			int h = Math.max(2, Math.round(iconSize * 0.22f));
			graphics.fill(x, y, x + 1, y + h + Math.max(2, h), 0xFF1B1B1B);
			graphics.fill(x + 1, y, x + 1 + w, y + h, 0xFF000000 | nation.color);
		}
	}
}
