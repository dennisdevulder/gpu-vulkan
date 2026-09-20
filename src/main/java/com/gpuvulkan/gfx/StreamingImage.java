/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

/**
 * A 2D image whose pixels are pushed from the CPU once per frame. Internally
 * holds {@code FRAMES_IN_FLIGHT} ping-pong textures plus the staging buffers
 * + descriptor cycle to avoid a write-while-read race; the consumer just
 * calls {@link #uploadPixels} each frame.
 */
public interface StreamingImage extends AutoCloseable
{
	/** {@code pixels} is width*height ints (one int per pixel), memcpy'd
	 *  immediately — the caller may reuse the array next frame. */
	void uploadPixels(int[] pixels);

	int width();
	int height();

	@Override
	void close();
}
