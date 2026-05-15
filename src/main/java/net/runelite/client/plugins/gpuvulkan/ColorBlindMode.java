package net.runelite.client.plugins.gpuvulkan;

/**
 * Daltonization modes for the post-fog colour correction. Matches stock
 * GPU's {@code config/ColorBlindMode.java} enum and the algorithm in
 * stock's {@code colorblind.glsl}. The ordinal is what we send to the
 * shader (0..3) — keep the order stable.
 */
public enum ColorBlindMode
{
	NONE,
	PROTANOPE,
	DEUTERANOPE,
	TRITANOPE;
}
