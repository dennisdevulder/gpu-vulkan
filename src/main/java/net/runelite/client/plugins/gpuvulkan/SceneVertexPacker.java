/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package net.runelite.client.plugins.gpuvulkan;

import org.lwjgl.system.MemoryUtil;

final class SceneVertexPacker
{
	private SceneVertexPacker()
	{
	}

	static void writePacked(long p, float x, float y, float z, int hsl16, float u, float v, int texLayer)
	{
		MemoryUtil.memPutShort(p, clampShort(Math.round(x)));
		MemoryUtil.memPutShort(p + 2, clampShort(Math.round(y)));
		MemoryUtil.memPutShort(p + 4, clampShort(Math.round(z)));
		MemoryUtil.memPutShort(p + 6, (short) 0);
		MemoryUtil.memPutInt(p + 8, (texLayer & 0xFFFF0000) | (hsl16 & 0xFFFF));
		MemoryUtil.memPutShort(p + 12, clampShort(texLayer & 0xFFFF));
		MemoryUtil.memPutShort(p + 14, clampShort(Math.round(u * 256f)));
		MemoryUtil.memPutShort(p + 16, clampShort(Math.round(v * 256f)));
		MemoryUtil.memPutShort(p + 18, (short) 0);
	}

	private static short clampShort(int value)
	{
		return (short) (value < Short.MIN_VALUE ? Short.MIN_VALUE : Math.min(value, Short.MAX_VALUE));
	}
}
