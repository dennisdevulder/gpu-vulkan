package net.runelite.client.plugins.gpuvulkan;

import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK13.*;

/**
 * Owns the UI texture, staging buffer, descriptor set and UI pipeline. Per
 * frame, accepts a fresh {@code int[]} of canvas pixels (BGRA-packed),
 * uploads via the staging buffer, and records a fullscreen-quad draw inside
 * the existing render pass. Recreates texture + staging when the canvas
 * dimensions change.
 */
final class InterfaceRenderer implements AutoCloseable
{
	private final VulkanDevice device;
	private final RenderPass renderPass;
	private final Descriptors descriptors;
	private final UiPipeline uiPipeline;

	private Texture texture;
	private Buffer staging;
	private int textureWidth;
	private int textureHeight;

	InterfaceRenderer(VulkanDevice device, RenderPass renderPass)
	{
		this.device = device;
		this.renderPass = renderPass;
		this.descriptors = new Descriptors(device);
		this.uiPipeline = new UiPipeline(device, renderPass, descriptors, renderPass.samples());
	}

	/** CPU-side pixel upload. Call before recording the command buffer. */
	void uploadPixels(int[] pixels, int width, int height)
	{
		ensureTexture(width, height);
		// pixels.length may exceed width*height (the OSRS BufferProvider can
		// over-allocate); copy only what the texture wants.
		int count = Math.min(pixels.length, width * height);
		staging.writeInts(pixels, 0, count);
	}

	/** Records the staging-buffer→image copy and layout transitions. */
	void recordCopyToImage(VkCommandBuffer cmd)
	{
		texture.transitionLayout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		texture.recordCopyFrom(cmd, staging, textureWidth, textureHeight);
		texture.transitionLayout(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	}

	/** Records the bind + fullscreen-quad draw. Must be inside an active render pass. */
	void recordDraw(VkCommandBuffer cmd)
	{
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, uiPipeline.handle());
		vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
			uiPipeline.layout(), 0,
			new long[]{descriptors.descriptorSet()},
			null);
		vkCmdDraw(cmd, 3, 1, 0, 0);
	}

	@Override
	public void close()
	{
		// Reverse-order teardown of the pieces we own.
		vkDeviceWaitIdle(device.handle());
		uiPipeline.close();
		descriptors.close();
		if (texture != null) texture.close();
		if (staging != null) staging.close();
	}

	private void ensureTexture(int width, int height)
	{
		if (texture != null && textureWidth == width && textureHeight == height)
		{
			return;
		}

		// Resize: dispose old, allocate new, rebind descriptor set.
		vkDeviceWaitIdle(device.handle());
		if (texture != null) texture.close();
		if (staging != null) staging.close();

		textureWidth = width;
		textureHeight = height;

		// VK_FORMAT_B8G8R8A8_UNORM: the BufferProvider pixels are sRGB-encoded
		// bytes, AND the swapchain is now UNORM (no auto-conversion). Sample
		// raw, write raw, display sees raw — identity pass-through, matching
		// what stock GpuPlugin does (linear GL framebuffer, no gamma anywhere).
		// Using SRGB here while the swapchain is UNORM would darken the UI
		// (sampler decodes to linear, swapchain doesn't re-encode); using
		// SRGB on both sides was the source of our 2x-too-bright scene.
		texture = new Texture(device, width, height, VK_FORMAT_B8G8R8A8_UNORM);

		long sizeBytes = (long) width * height * 4L;
		staging = new Buffer(device, sizeBytes,
			VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		staging.mapPersistent();

		descriptors.updateBinding(texture);
	}
}
