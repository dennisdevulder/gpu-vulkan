/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package net.runelite.client.plugins.gpuvulkan;

final class HslColor
{
	static final float[] RGB_TABLE = buildRgbTable();

	private HslColor()
	{
	}

	static int applyOverride(int hsl16, byte overrideHue, byte overrideSaturation,
		byte overrideLuminance, byte overrideAmount)
	{
		int amount = overrideAmount & 0xFF;
		int h = (hsl16 >> 10) & 0x3F;
		int s = (hsl16 >> 7) & 0x07;
		int l = hsl16 & 0x7F;
		if (overrideHue != -1)
		{
			h = clamp(h + ((amount * ((overrideHue & 0xFF) - h)) >> 7), 0x3F);
		}
		if (overrideSaturation != -1)
		{
			s = clamp(s + ((amount * ((overrideSaturation & 0xFF) - s)) >> 7), 0x07);
		}
		if (overrideLuminance != -1)
		{
			l = clamp(l + ((amount * ((overrideLuminance & 0xFF) - l)) >> 7), 0x7F);
		}
		return (h << 10) | (s << 7) | l;
	}

	private static int clamp(int value, int max)
	{
		return value < 0 ? 0 : Math.min(value, max);
	}

	private static float[] buildRgbTable()
	{
		float[] table = new float[0x10000 * 3];
		for (int hsl16 = 0; hsl16 < 0x10000; hsl16++)
		{
			int h = (hsl16 >> 10) & 0x3F;
			int s = (hsl16 >> 7) & 0x07;
			int l = hsl16 & 0x7F;
			float hue = h / 64f + 0.0078125f;
			float sat = s / 8f + 0.0625f;
			float lum = l / 128f;
			float q = lum < 0.5f ? lum * (1f + sat) : lum + sat - lum * sat;
			float p = 2f * lum - q;
			int offset = hsl16 * 3;
			table[offset] = hueToChannel(p, q, hue + 1f / 3f);
			table[offset + 1] = hueToChannel(p, q, hue);
			table[offset + 2] = hueToChannel(p, q, hue - 1f / 3f);
		}
		return table;
	}

	private static float hueToChannel(float p, float q, float t)
	{
		if (t > 1f)
		{
			t -= 1f;
		}
		else if (t < 0f)
		{
			t += 1f;
		}
		if (6f * t < 1f)
		{
			return p + (q - p) * 6f * t;
		}
		if (2f * t < 1f)
		{
			return q;
		}
		if (3f * t < 2f)
		{
			return p + (q - p) * (2f / 3f - t) * 6f;
		}
		return p;
	}
}
