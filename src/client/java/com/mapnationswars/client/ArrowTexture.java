package com.mapnationswars.client;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import com.mapnationswars.MapNationsMod;

/**
 * Draws a smooth, anti-aliased map pointer once into a 64x64 texture.
 * The arrow points up; the map screen rotates it to where you are looking.
 */
final class ArrowTexture {
	static final int SIZE = 64;

	// arrow outline: tip, right wing, notch, left wing
	private static final double[] XS = {32, 55, 32, 9};
	private static final double[] YS = {5, 57, 44, 57};
	private static final double OUTLINE = 4.0;

	private static Identifier id;

	private ArrowTexture() {
	}

	static Identifier get(Minecraft mc) {
		if (id == null) {
			Identifier newId = MapNationsMod.id("player_arrow");
			NativeImage image = new NativeImage(SIZE, SIZE, false);
			paint(image);
			DynamicTexture texture = new DynamicTexture(newId::toString, image);
			mc.getTextureManager().register(newId, texture);
			texture.upload();
			id = newId;
		}

		return id;
	}

	private static void paint(NativeImage image) {
		int samples = 4; // 4x4 samples per pixel = smooth edges

		for (int py = 0; py < SIZE; py++) {
			for (int px = 0; px < SIZE; px++) {
				double a = 0;
				double r = 0;
				double g = 0;
				double b = 0;

				for (int sy = 0; sy < samples; sy++) {
					for (int sx = 0; sx < samples; sx++) {
						double x = px + (sx + 0.5) / samples;
						double y = py + (sy + 0.5) / samples;
						int c = colorAt(x, y);
						double ca = ((c >>> 24) & 0xFF) / 255.0;
						a += ca;
						r += ((c >> 16) & 0xFF) * ca;
						g += ((c >> 8) & 0xFF) * ca;
						b += (c & 0xFF) * ca;
					}
				}

				int n = samples * samples;
				int argb = 0;

				if (a > 0) {
					int outA = (int) Math.round(a / n * 255);
					int outR = (int) Math.round(r / a);
					int outG = (int) Math.round(g / a);
					int outB = (int) Math.round(b / a);
					argb = (outA << 24) | (outR << 16) | (outG << 8) | outB;
				}

				image.setPixel(px, py, argb);
			}
		}
	}

	/** Colour of one sample point: white body (right half slightly shaded), dark outline, transparent outside. */
	private static int colorAt(double x, double y) {
		if (inside(x, y)) {
			if (x < 32) {
				return 0xFFFFFFFF;
			}

			return 0xFFC9D3DE;
		}

		if (distanceToEdge(x, y) <= OUTLINE) {
			return 0xF0141A22;
		}

		return 0;
	}

	private static boolean inside(double x, double y) {
		boolean in = false;

		for (int i = 0, j = XS.length - 1; i < XS.length; j = i++) {
			if ((YS[i] > y) != (YS[j] > y) && x < (XS[j] - XS[i]) * (y - YS[i]) / (YS[j] - YS[i]) + XS[i]) {
				in = !in;
			}
		}

		return in;
	}

	private static double distanceToEdge(double x, double y) {
		double best = Double.MAX_VALUE;

		for (int i = 0, j = XS.length - 1; i < XS.length; j = i++) {
			best = Math.min(best, segmentDistance(x, y, XS[j], YS[j], XS[i], YS[i]));
		}

		return best;
	}

	private static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
		double dx = bx - ax;
		double dy = by - ay;
		double t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
		t = Math.max(0, Math.min(1, t));
		double cx = ax + t * dx - px;
		double cy = ay + t * dy - py;
		return Math.sqrt(cx * cx + cy * cy);
	}
}
