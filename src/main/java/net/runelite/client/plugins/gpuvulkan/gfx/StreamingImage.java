/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.gpuvulkan.gfx;

/**
 * A 2D image whose pixels are pushed from the CPU once per frame. Internally
 * holds {@code FRAMES_IN_FLIGHT} ping-pong textures plus the staging buffers
 * + descriptor cycle to avoid a write-while-read race; the consumer just
 * calls {@link #uploadPixels} each frame.
 *
 * <p>UI overlay is the canonical use case: RuneLite's
 * {@code BufferProvider.getPixels()} returns the canvas as a flat int[] of
 * ARGB pixels; we hand them here, the layer takes it from there.
 */
public interface StreamingImage extends AutoCloseable
{
	/**
	 * Upload {@code pixels} into the current frame's slot. Caller can re-use
	 * the same array next frame; bytes are memcpy'd into a host-visible
	 * staging region immediately, and the GPU copy is queued by the next
	 * {@link RenderEncoder} that references the bind group containing this
	 * image.
	 *
	 * @param pixels  4-byte-per-pixel data laid out as {@code width * height}
	 *                ints. Caller is responsible for matching the image's
	 *                {@code width}/{@code height} at creation time.
	 */
	void uploadPixels(int[] pixels);

	int width();
	int height();

	@Override
	void close();
}
