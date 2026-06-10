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
package net.runelite.client.plugins.gpuvulkan;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.gpuvulkan.gfx.StreamingImage;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
import static org.lwjgl.vulkan.VK13.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK13.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK13.vkDeviceWaitIdle;

/**
 * Streaming 2D image — internally rings {@code FRAMES_IN_FLIGHT} textures
 * plus their staging buffers so the CPU write at frame N can't race the
 * GPU read at frame N-1. Wraps existing {@link Texture} and {@link Buffer}.
 *
 * <p>Format is fixed to {@code VK_FORMAT_B8G8R8A8_UNORM} — matches the
 * RuneLite BufferProvider's pixel layout and the only consumer today.
 */
@Slf4j
final class GfxStreamingImage implements StreamingImage
{
	private static final int FULL_UPLOAD_DIRTY_ROW_PERCENT = 50;
	private static final int FULL_UPLOAD_RANGE_LIMIT = 64;

	private final VulkanDevice device;
	private final FrameSync frameSync;
	private final int width;
	private final int height;
	private final Texture[] textures;
	private final Buffer[] stagings;
	private final int[][] previousPixels;
	private final boolean[] needsFullUpload;
	private final int[][] dirtyRowStarts;
	private final int[][] dirtyRowHeights;
	private final int[] dirtyRangeCounts;
	private final boolean dirtyRowsEnabled = Boolean.parseBoolean(System.getProperty("vkgpu.uiDirtyRows", "false"));
	private final boolean cachedStaging = Boolean.parseBoolean(System.getProperty("vkgpu.uiCachedStaging", "false"));
	private final boolean logDirtyRows = Boolean.parseBoolean(System.getProperty("vkgpu.uiDirtyStats", "false"));
	private long nextDirtyLogNanos = System.nanoTime() + 1_000_000_000L;
	private long dirtyLogFrames;
	private long dirtyLogRows;
	private long dirtyLogTotalRows;
	private long dirtyLogRanges;
	private long dirtyLogFullUploads;

	GfxStreamingImage(VulkanDevice device, FrameSync frameSync, int width, int height)
	{
		this.device = device;
		this.frameSync = frameSync;
		this.width = width;
		this.height = height;
		this.textures = new Texture[FrameSync.FRAMES_IN_FLIGHT];
		this.stagings = new Buffer[FrameSync.FRAMES_IN_FLIGHT];
		this.previousPixels = new int[FrameSync.FRAMES_IN_FLIGHT][];
		this.needsFullUpload = new boolean[FrameSync.FRAMES_IN_FLIGHT];
		this.dirtyRowStarts = new int[FrameSync.FRAMES_IN_FLIGHT][];
		this.dirtyRowHeights = new int[FrameSync.FRAMES_IN_FLIGHT][];
		this.dirtyRangeCounts = new int[FrameSync.FRAMES_IN_FLIGHT];

		long sizeBytes = (long) width * height * 4L;
		for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
		{
			textures[i] = new Texture(device, width, height, VK_FORMAT_B8G8R8A8_UNORM);
			stagings[i] = new Buffer(device, sizeBytes,
				VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
				VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
				cachedStaging ? VK_MEMORY_PROPERTY_HOST_CACHED_BIT : VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
			stagings[i].mapPersistent();
			previousPixels[i] = dirtyRowsEnabled ? new int[width * height] : null;
			needsFullUpload[i] = true;
			dirtyRowStarts[i] = new int[height];
			dirtyRowHeights[i] = new int[height];
		}
		log.debug("UI staging memory flags: 0x{} (cachedStaging={})",
			Integer.toHexString(stagings[0].memoryPropertyFlags()), cachedStaging);
	}

	@Override
	public void uploadPixels(int[] pixels)
	{
		int slot = frameSync.currentFrame();
		int count = Math.min(pixels.length, width * height);
		int rows = count / width;
		dirtyRangeCounts[slot] = 0;
		if (rows <= 0)
		{
			return;
		}

		Buffer staging = stagings[slot];
		int rowInts = rows * width;
		if (!dirtyRowsEnabled)
		{
			fullUpload(slot, staging, pixels, null, rows, rowInts, false);
			recordDirtyUploadStats(rows, rows, 1, true);
			return;
		}

		int[] previous = previousPixels[slot];
		if (needsFullUpload[slot])
		{
			fullUpload(slot, staging, pixels, previous, rows, rowInts, true);
			needsFullUpload[slot] = false;
			recordDirtyUploadStats(rows, rows, 1, true);
			return;
		}

		int rangeCount = 0;
		int dirtyRows = 0;
		int flushStart = rowInts;
		int flushEnd = 0;
		for (int searchOffset = 0; searchOffset < rowInts; )
		{
			int mismatch = Arrays.mismatch(pixels, searchOffset, rowInts, previous, searchOffset, rowInts);
			if (mismatch < 0)
			{
				break;
			}

			int y = (searchOffset + mismatch) / width;
			int startRow = y;
			do
			{
				int rowOffset = y * width;
				System.arraycopy(pixels, rowOffset, previous, rowOffset, width);
				y++;
			}
			while (y < rows && rowDiffers(pixels, previous, y * width, width));

			int rowCount = y - startRow;
			dirtyRows += rowCount;
			int intOffset = startRow * width;
			staging.writeIntsUnflushed(previous, intOffset, intOffset, rowCount * width);
			flushStart = Math.min(flushStart, intOffset);
			flushEnd = Math.max(flushEnd, intOffset + rowCount * width);
			dirtyRowStarts[slot][rangeCount] = startRow;
			dirtyRowHeights[slot][rangeCount] = rowCount;
			rangeCount++;
			searchOffset = y * width;
		}

		if (shouldFullUpload(rows, dirtyRows, rangeCount))
		{
			fullUpload(slot, staging, pixels, previous, rows, rowInts, true);
			recordDirtyUploadStats(rows, rows, 1, true);
			return;
		}

		dirtyRangeCounts[slot] = rangeCount;
		if (rangeCount > 0)
		{
			staging.flushRangeIfNeeded((long) flushStart * Integer.BYTES,
				(long) (flushEnd - flushStart) * Integer.BYTES);
		}
		recordDirtyUploadStats(rows, dirtyRows, rangeCount, false);
	}

	private void fullUpload(int slot, Buffer staging, int[] pixels, int[] previous, int rows, int rowInts,
							boolean updatePrevious)
	{
		staging.writeIntsUnflushed(pixels, 0, 0, rowInts);
		if (updatePrevious)
		{
			System.arraycopy(pixels, 0, previous, 0, rowInts);
		}
		dirtyRowStarts[slot][0] = 0;
		dirtyRowHeights[slot][0] = rows;
		dirtyRangeCounts[slot] = 1;
		staging.flushRangeIfNeeded(0, (long) rowInts * Integer.BYTES);
	}

	private static boolean shouldFullUpload(int totalRows, int dirtyRows, int dirtyRanges)
	{
		return dirtyRanges > FULL_UPLOAD_RANGE_LIMIT
			|| dirtyRows * 100 >= totalRows * FULL_UPLOAD_DIRTY_ROW_PERCENT;
	}

	/** Records the staging→image copy + the layout transitions around it,
	 *  for the current frame's slot. Called by the consumer outside the
	 *  render pass (Vulkan disallows {@code vkCmdCopyBufferToImage} inside
	 *  one). */
	void recordCopyToImage(VkCommandBuffer cmd)
	{
		int slot = frameSync.currentFrame();
		int rangeCount = dirtyRangeCounts[slot];
		if (rangeCount <= 0)
		{
			return;
		}
		Texture t = textures[slot];
		Buffer s = stagings[slot];
		t.transitionLayout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		t.recordCopyRowsFrom(cmd, s, width, dirtyRowStarts[slot], dirtyRowHeights[slot], rangeCount);
		t.transitionLayout(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	}

	private static boolean rowDiffers(int[] current, int[] previous, int offset, int width)
	{
		return Arrays.mismatch(current, offset, offset + width, previous, offset, offset + width) >= 0;
	}

	private void recordDirtyUploadStats(int totalRows, int dirtyRows, int dirtyRanges, boolean fullUpload)
	{
		if (!logDirtyRows)
		{
			return;
		}

		dirtyLogFrames++;
		dirtyLogRows += dirtyRows;
		dirtyLogTotalRows += totalRows;
		dirtyLogRanges += dirtyRanges;
		if (fullUpload)
		{
			dirtyLogFullUploads++;
		}

		long now = System.nanoTime();
		if (now < nextDirtyLogNanos)
		{
			return;
		}
		nextDirtyLogNanos = now + 1_000_000_000L;
		long frames = dirtyLogFrames;
		long rows = dirtyLogRows;
		long total = dirtyLogTotalRows;
		long ranges = dirtyLogRanges;
		long full = dirtyLogFullUploads;
		dirtyLogFrames = 0;
		dirtyLogRows = 0;
		dirtyLogTotalRows = 0;
		dirtyLogRanges = 0;
		dirtyLogFullUploads = 0;

		double dirtyPct = total == 0 ? 0.0 : (rows * 100.0) / total;
		double avgRows = frames == 0 ? 0.0 : rows / (double) frames;
		double avgRanges = frames == 0 ? 0.0 : ranges / (double) frames;
		log.info("uiDirty | frames={} dirtyRows={}/{} ({}) avgRows={} avgRanges={} fullUploads={}",
			frames, rows, total, String.format("%.1f%%", dirtyPct),
			String.format("%.1f", avgRows), String.format("%.1f", avgRanges), full);
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
