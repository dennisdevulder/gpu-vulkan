package net.runelite.client.plugins.gpuvulkan;

import net.runelite.client.plugins.gpuvulkan.gfx.StreamingImage;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
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
final class GfxStreamingImage implements StreamingImage
{
	private final VulkanDevice device;
	private final FrameSync frameSync;
	private final int width;
	private final int height;
	private final Texture[] textures;
	private final Buffer[] stagings;

	GfxStreamingImage(VulkanDevice device, FrameSync frameSync, int width, int height)
	{
		this.device = device;
		this.frameSync = frameSync;
		this.width = width;
		this.height = height;
		this.textures = new Texture[FrameSync.FRAMES_IN_FLIGHT];
		this.stagings = new Buffer[FrameSync.FRAMES_IN_FLIGHT];

		long sizeBytes = (long) width * height * 4L;
		for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
		{
			textures[i] = new Texture(device, width, height, VK_FORMAT_B8G8R8A8_UNORM);
			stagings[i] = new Buffer(device, sizeBytes,
				VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
				VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
			stagings[i].mapPersistent();
		}
	}

	@Override
	public void uploadPixels(int[] pixels)
	{
		int count = Math.min(pixels.length, width * height);
		stagings[frameSync.currentFrame()].writeInts(pixels, 0, count);
	}

	/** Records the staging→image copy + the layout transitions around it,
	 *  for the current frame's slot. Called by the consumer outside the
	 *  render pass (Vulkan disallows {@code vkCmdCopyBufferToImage} inside
	 *  one). */
	void recordCopyToImage(VkCommandBuffer cmd)
	{
		Texture t = textures[frameSync.currentFrame()];
		Buffer s = stagings[frameSync.currentFrame()];
		t.transitionLayout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		t.recordCopyFrom(cmd, s, width, height);
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
			if (textures[i] != null) textures[i].close();
			if (stagings[i] != null) stagings[i].close();
		}
	}
}
