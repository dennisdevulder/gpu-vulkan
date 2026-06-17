/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import java.awt.Canvas;

/**
 * Plugin Hub build does not ship macOS native presentation support.
 */
final class MacOSMetalHelper
{
	private MacOSMetalHelper() {}

	static void detachMetalLayer() {}

	static void resizeMetalLayer(Canvas canvas) {}

	static void resizeMetalLayerSize(int width, int height) {}

	static long[] nextDrawable()
	{
		return null;
	}

	static void presentDrawable(long drawable, long mtlQueue) {}

	static void retainObject(long ptr) {}

	static void releaseObject(long ptr) {}
}
