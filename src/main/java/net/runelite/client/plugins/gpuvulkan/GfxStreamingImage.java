package net.runelite.client.plugins.gpuvulkan;

import java.util.Arrays;
import net.runelite.client.plugins.gpuvulkan.gfx.StreamingImage;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
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
final class GfxStreamingImage implements StreamingImage
{
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
				VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
			stagings[i].mapPersistent();
			previousPixels[i] = new int[width * height];
			needsFullUpload[i] = true;
			dirtyRowStarts[i] = new int[height];
			dirtyRowHeights[i] = new int[height];
		}
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
		int[] previous = previousPixels[slot];
		if (needsFullUpload[slot])
		{
			int rowInts = rows * width;
			staging.writeInts(pixels, 0, 0, rowInts);
			System.arraycopy(pixels, 0, previous, 0, rowInts);
			dirtyRowStarts[slot][0] = 0;
			dirtyRowHeights[slot][0] = rows;
			dirtyRangeCounts[slot] = 1;
			needsFullUpload[slot] = false;
			staging.flushIfNeeded();
			return;
		}

		int rangeCount = 0;
		for (int y = 0; y < rows; )
		{
			int rowOffset = y * width;
			if (!rowDiffers(pixels, previous, rowOffset, width))
			{
				y++;
				continue;
			}

			int startRow = y;
			do
			{
				rowOffset = y * width;
				System.arraycopy(pixels, rowOffset, previous, rowOffset, width);
				y++;
			}
			while (y < rows && rowDiffers(pixels, previous, y * width, width));

			int rowCount = y - startRow;
			int intOffset = startRow * width;
			staging.writeInts(previous, intOffset, intOffset, rowCount * width);
			dirtyRowStarts[slot][rangeCount] = startRow;
			dirtyRowHeights[slot][rangeCount] = rowCount;
			rangeCount++;
		}

		dirtyRangeCounts[slot] = rangeCount;
		if (rangeCount > 0)
		{
			staging.flushIfNeeded();
		}
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
			if (textures[i] != null) textures[i].close();
			if (stagings[i] != null) stagings[i].close();
		}
	}
}
