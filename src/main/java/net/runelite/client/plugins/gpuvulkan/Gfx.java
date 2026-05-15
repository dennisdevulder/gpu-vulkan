package net.runelite.client.plugins.gpuvulkan;

import net.runelite.client.plugins.gpuvulkan.gfx.Renderer;

/**
 * Entry point for the {@code gfx} rendering device layer. The layer's public
 * API lives in {@code net.runelite.client.plugins.gpuvulkan.gfx} as
 * interfaces; the implementation classes are package-private in this top
 * package so they can keep using the existing
 * {@link Texture}/{@link Buffer}/{@link Descriptors} wrappers without
 * forcing those types to become public.
 *
 * <p>Phase 1 (the current slice): the layer is constructed by adopting
 * already-built Vulkan handles via {@link #wrap}. The plugin's existing
 * init path stays unchanged. The migrated consumer today is
 * {@link InterfaceRenderer}; it dropped the old hardcoded
 * {@code Descriptors} + {@code UiPipeline} pair in favour of the layer's
 * {@link Renderer}-built pipeline + bind group.
 *
 * <p>Phase 2 (future): the layer constructs the instance / device /
 * swapchain itself, swallowing the rest of the boilerplate that the
 * existing classes contain.
 */
public final class Gfx
{
	private Gfx() {}

	/**
	 * Builds a {@link Renderer} that adopts the already-constructed Vulkan
	 * state passed in. The Renderer does not assume ownership of any of
	 * these handles — its {@code close()} only releases resources the
	 * Renderer itself created (shader modules, pipelines, bind groups,
	 * streaming images).
	 *
	 * @param device         The VulkanDevice wrapping the active
	 *                       {@code VkDevice} + queue.
	 * @param frameSync      Per-slot fences/semaphores; the Renderer reads
	 *                       {@link FrameSync#currentFrame} to route streaming
	 *                       resource updates to the right slot.
	 * @param renderPass     The swapchain's main render pass; pipelines
	 *                       built via the Renderer target it.
	 */
	public static Renderer wrap(VulkanDevice device, FrameSync frameSync, RenderPass renderPass)
	{
		return new GfxRenderer(device, frameSync, renderPass);
	}
}
