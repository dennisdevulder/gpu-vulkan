package net.runelite.client.plugins.gpuvulkan.gfx;

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
public interface Renderer extends RenderDevice, AutoCloseable
{
	/** Drops layer state. Resources created via this Renderer are NOT
	 *  closed transitively — their owners are still responsible. (Avoids
	 *  surprising teardown ordering when the consumer's own
	 *  {@code Disposables} stack already manages them.) */
	@Override
	void close();
}
