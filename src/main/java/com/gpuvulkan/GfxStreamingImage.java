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
package com.gpuvulkan;

import lombok.extern.slf4j.Slf4j;
import com.gpuvulkan.gfx.StreamingImage;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK13.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK13.vkDeviceWaitIdle;

/**
 * Rings FRAMES_IN_FLIGHT textures + staging buffers so CPU writes can't race
 * GPU reads. Format fixed to B8G8R8A8_UNORM (the BufferProvider pixel layout).
 */
@Slf4j
final class GfxStreamingImage implements StreamingImage
{
	private final VulkanDevice device;
	private final FrameSync frameSync;
	private final int width;
	private final int height;
	private final Texture[] textures;
	private final Buffer[] stagings;
	/** Rows staged for this slot this frame; 0 = nothing to copy. */
	private final int[] uploadedRows;

	GfxStreamingImage(VulkanDevice device, FrameSync frameSync, int width, int height)
	{
		this.device = device;
		this.frameSync = frameSync;
		this.width = width;
		this.height = height;
		this.textures = new Texture[FrameSync.FRAMES_IN_FLIGHT];
		this.stagings = new Buffer[FrameSync.FRAMES_IN_FLIGHT];
		this.uploadedRows = new int[FrameSync.FRAMES_IN_FLIGHT];

		long sizeBytes = (long) width * height * 4L;
		for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
		{
			textures[i] = new Texture(device, width, height, VK_FORMAT_B8G8R8A8_UNORM);
			stagings[i] = new Buffer(device, sizeBytes,
				VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
				VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
				VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
			stagings[i].mapPersistent();
		}
	}

	@Override
	public void uploadPixels(int[] pixels)
	{
		int slot = frameSync.currentFrame();
		int count = Math.min(pixels.length, width * height);
		int rows = count / width;
		uploadedRows[slot] = 0;
		if (rows <= 0)
		{
			return;
		}

		Buffer staging = stagings[slot];
		int rowInts = rows * width;
		staging.writeIntsUnflushed(pixels, 0, 0, rowInts);
		staging.flushRangeIfNeeded(0, (long) rowInts * Integer.BYTES);
		uploadedRows[slot] = rows;
	}

	/** Must be recorded OUTSIDE the render pass — vkCmdCopyBufferToImage is
	 *  disallowed inside one. */
	void recordCopyToImage(VkCommandBuffer cmd)
	{
		int slot = frameSync.currentFrame();
		int rows = uploadedRows[slot];
		if (rows <= 0)
		{
			return;
		}
		Texture t = textures[slot];
		t.transitionLayout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		t.recordCopyFrom(cmd, stagings[slot], width, rows);
		t.transitionLayout(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	}

	/** Per-slot view + sampler handles, exposed so {@link GfxBindGroup} can
	 *  wire its FRAMES_IN_FLIGHT descriptor sets to the ring. */
	long viewForSlot(int slot) { return textures[slot].view(); }
	long samplerForSlot(int slot) { return textures[slot].sampler(); }

	@Override
	public int width() { return width; }

	@Override
	public int height() { return height; }

	@Override
	public void close()
	{
		vkDeviceWaitIdle(device.handle());
		for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
		{
			if (textures[i] != null)
			{
				textures[i].close();
				textures[i] = null;
			}
			if (stagings[i] != null)
			{
				stagings[i].close();
				stagings[i] = null;
			}
		}
	}
}
