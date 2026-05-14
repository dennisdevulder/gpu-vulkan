package net.runelite.client.plugins.gpuvulkan;

import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK13.*;

/**
 * Owns the UI textures, staging buffers, descriptor sets and UI pipeline. Per
 * frame, accepts a fresh {@code int[]} of canvas pixels (BGRA-packed), uploads
 * via the staging buffer for the current in-flight slot, and records a
 * fullscreen-quad draw inside the existing render pass.
 *
 * <p>Resources are arrays sized to {@link FrameSync#FRAMES_IN_FLIGHT}. Without
 * per-slot copies, frame N+1's CPU write would clobber the staging buffer
 * while frame N's GPU copy is still in flight, and frame N+1's texture copy
 * would race frame N's UI-quad sampling — producing patterned artifacts
 * across the displayed canvas.
 */
final class InterfaceRenderer implements AutoCloseable
{
	private final VulkanDevice device;
	private final FrameSync sync;
	private final Descriptors descriptors;
	private final UiPipeline uiPipeline;

	private final Texture[] textures = new Texture[FrameSync.FRAMES_IN_FLIGHT];
	private final Buffer[] stagings = new Buffer[FrameSync.FRAMES_IN_FLIGHT];
	private int textureWidth;
	private int textureHeight;

	InterfaceRenderer(VulkanDevice device, FrameSync sync, RenderPass renderPass)
	{
		this.device = device;
		this.sync = sync;
		this.descriptors = new Descriptors(device);
		this.uiPipeline = new UiPipeline(device, renderPass, descriptors, renderPass.samples());
	}

	/** CPU-side pixel upload into the current slot's staging buffer. */
	void uploadPixels(int[] pixels, int width, int height)
	{
		ensureTextures(width, height);
		int count = Math.min(pixels.length, width * height);
		stagings[sync.currentFrame()].writeInts(pixels, 0, count);
	}

	/** Records the staging→image copy + layout transitions for this slot. */
	void recordCopyToImage(VkCommandBuffer cmd)
	{
		Texture texture = textures[sync.currentFrame()];
		Buffer staging = stagings[sync.currentFrame()];
		texture.transitionLayout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		texture.recordCopyFrom(cmd, staging, textureWidth, textureHeight);
		texture.transitionLayout(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	}

	/** Records the bind + fullscreen-quad draw using this slot's descriptor set. */
	void recordDraw(VkCommandBuffer cmd)
	{
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, uiPipeline.handle());
		vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
			uiPipeline.layout(), 0,
			new long[]{descriptors.descriptorSet(sync.currentFrame())},
			null);
		vkCmdDraw(cmd, 3, 1, 0, 0);
	}

	@Override
	public void close()
	{
		vkDeviceWaitIdle(device.handle());
		uiPipeline.close();
		descriptors.close();
		for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
		{
			if (textures[i] != null) textures[i].close();
			if (stagings[i] != null) stagings[i].close();
		}
	}

	private void ensureTextures(int width, int height)
	{
		if (textures[0] != null && textureWidth == width && textureHeight == height)
		{
			return;
		}

		// Resize: dispose all slots, allocate new, rebind descriptor sets.
		vkDeviceWaitIdle(device.handle());
		for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
		{
			if (textures[i] != null) textures[i].close();
			if (stagings[i] != null) stagings[i].close();
		}

		textureWidth = width;
		textureHeight = height;

		// VK_FORMAT_B8G8R8A8_UNORM: the BufferProvider pixels are sRGB-encoded
		// bytes, AND the swapchain is now UNORM (no auto-conversion). Sample
		// raw, write raw, display sees raw — identity pass-through, matching
		// what stock GpuPlugin does. SRGB on both sides was the source of our
		// 2x-too-bright scene.
		long sizeBytes = (long) width * height * 4L;
		for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
		{
			textures[i] = new Texture(device, width, height, VK_FORMAT_B8G8R8A8_UNORM);
			stagings[i] = new Buffer(device, sizeBytes,
				VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
				VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
			stagings[i].mapPersistent();
			descriptors.updateBinding(i, textures[i]);
		}
	}
}
