package com.mapnationswars.client;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import com.mapnationswars.MapNationsMod;

/**
 * A 256 x 256 block piece of the map (16 x 16 chunks).
 * pixels[] is the real data (ARGB colours, 0 = not explored yet);
 * the texture is only used to draw it on screen.
 */
final class MapRegion {
	static final int SIZE = 256;
	private static int textureCounter = 0;

	final int regionX;
	final int regionZ;
	final int[] pixels = new int[SIZE * SIZE];

	/** Pixels changed and the texture must be re-uploaded. */
	boolean textureDirty = true;
	/** Pixels changed and the file on disk is out of date. */
	boolean unsaved = false;

	private DynamicTexture texture;
	private Identifier textureId;

	MapRegion(int regionX, int regionZ) {
		this.regionX = regionX;
		this.regionZ = regionZ;
	}

	void setPixel(int localX, int localZ, int argb) {
		int index = localZ * SIZE + localX;

		if (this.pixels[index] != argb) {
			this.pixels[index] = argb;
			this.textureDirty = true;
			this.unsaved = true;
		}
	}

	/**
	 * Returns the texture to draw, creating / updating it if needed.
	 * Returns null if it has never been uploaded and allowUpload is false.
	 */
	Identifier prepareTexture(Minecraft mc, boolean allowUpload) {
		if (this.texture == null) {
			if (!allowUpload) {
				return null;
			}

			this.textureId = MapNationsMod.id("region/" + (textureCounter++));
			NativeImage image = new NativeImage(SIZE, SIZE, false);
			Identifier id = this.textureId;
			this.texture = new DynamicTexture(id::toString, image);
			mc.getTextureManager().register(this.textureId, this.texture);
			this.textureDirty = true;
		}

		if (this.textureDirty && allowUpload) {
			NativeImage image = this.texture.getPixels();

			if (image != null) {
				for (int z = 0; z < SIZE; z++) {
					for (int x = 0; x < SIZE; x++) {
						image.setPixel(x, z, this.pixels[z * SIZE + x]);
					}
				}

				this.texture.upload();
			}

			this.textureDirty = false;
		}

		return this.textureId;
	}

	void releaseTexture(Minecraft mc) {
		if (this.texture != null) {
			mc.getTextureManager().release(this.textureId);
			this.texture.close();
			this.texture = null;
			this.textureId = null;
		}
	}
}
