package com.mapnationswars.nation;

/** The colours nations can pick from. Each colour can only belong to one nation. */
public final class NationColors {
	public static final int[] PALETTE = {
			0xE53935, 0xD81B60, 0x880E4F, 0x8E24AA, 0x5E35B1, 0x3949AB,
			0x0D47A1, 0x1E88E5, 0x039BE5, 0x00ACC1, 0x00897B, 0x1B5E20,
			0x43A047, 0x7CB342, 0xC0CA33, 0xFDD835, 0xFFB300, 0xFB8C00,
			0xF4511E, 0x6D4C41, 0x757575, 0x546E7A, 0xF5F5F5, 0x212121
	};

	private NationColors() {
	}

	public static boolean isValid(int color) {
		for (int c : PALETTE) {
			if (c == color) {
				return true;
			}
		}

		return false;
	}
}
