/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import org.lwjgl.system.MemoryUtil;

final class SceneVertexPacker
{
	private SceneVertexPacker()
	{
	}

	static void writePacked(long p, float x, float y, float z, int hsl16, float u, float v, int texLayer)
	{
		MemoryUtil.memPutFloat(p, x);
		MemoryUtil.memPutFloat(p + 4, y);
		MemoryUtil.memPutFloat(p + 8, z);
		MemoryUtil.memPutInt(p + 12, (texLayer & 0xFFFF0000) | (hsl16 & 0xFFFF));
		MemoryUtil.memPutShort(p + 16, clampShort(texLayer & 0xFFFF));
		MemoryUtil.memPutShort(p + 18, clampShort(Math.round(u * 256f)));
		MemoryUtil.memPutShort(p + 20, clampShort(Math.round(v * 256f)));
		MemoryUtil.memPutShort(p + 22, (short) 0);
	}

	private static short clampShort(int value)
	{
		return (short) (value < Short.MIN_VALUE ? Short.MIN_VALUE : Math.min(value, Short.MAX_VALUE));
	}
}
