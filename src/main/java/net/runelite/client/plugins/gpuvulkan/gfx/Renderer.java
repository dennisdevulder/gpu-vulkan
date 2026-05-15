package net.runelite.client.plugins.gpuvulkan.gfx;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Top-level entry point for the rendering device layer. Wraps a Vulkan
 * device + swapchain + per-frame command buffers; provides factory methods
 * for resources and pipelines.
 *
 * <p>Today the {@code Renderer} is constructed by the plugin's existing
 * init code via {@link #wrap}, which adopts already-built
 * {@code VulkanDevice}, {@code FrameSync}, and {@code RenderPass} handles
 * rather than constructing them itself. Migrating those into the layer is
 * future work; the wrapper lets consumers (e.g. {@code InterfaceRenderer})
 * adopt the API today without rebuilding the world.
 *
 * <p>Resources returned by {@code create*} are owned by the caller and must
 * be {@code close()}-ed when no longer needed.
 */
public interface Renderer extends AutoCloseable
{
	ShaderModule createShaderModule(byte[] spirv);

	BindGroupLayout createBindGroupLayout(BindGroupLayoutDesc desc);

	BindGroup createBindGroup(BindGroupDesc desc);

	RenderPipeline createRenderPipeline(RenderPipelineDesc desc);

	/**
	 * Creates a streaming 2D image whose pixels are uploaded once per frame
	 * from the CPU. Internally rings {@code FRAMES_IN_FLIGHT} slots so the
	 * CPU write can't clobber a GPU read in flight. Format is currently
	 * fixed to {@code VK_FORMAT_B8G8R8A8_UNORM} (matches RuneLite's
	 * BufferProvider pixel layout).
	 */
	StreamingImage createStreamingImage(int width, int height);

	/** Index of the per-frame slot the next encoder will record into.
	 *  Mostly an escape hatch for consumers that still need to coordinate
	 *  with raw Vulkan handles outside the layer. */
	int currentSlot();

	/** Wraps the layer's encoder around a command buffer that's already
	 *  been begun and is mid-render-pass. Until the layer owns the frame
	 *  loop itself, the plugin's existing
	 *  {@code VulkanRenderer.recordClearPass} drives begin/end; consumers
	 *  inside that scope grab an encoder via this method. */
	RenderEncoder encodeInto(VkCommandBuffer cmd);

	/** Drops layer state. Resources created via this Renderer are NOT
	 *  closed transitively — their owners are still responsible. (Avoids
	 *  surprising teardown ordering when the consumer's own
	 *  {@code Disposables} stack already manages them.) */
	@Override
	void close();
}
