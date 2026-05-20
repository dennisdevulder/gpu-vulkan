package net.runelite.client.plugins.gpuvulkan;

/**
 * Public service surface for plugins that want to share the Vulkan backend.
 */
public interface VulkanRenderBackend
{
	boolean isReady();

	/**
	 * Register a render extension. If the backend is not ready yet, the
	 * extension is attached during the next successful startup.
	 *
	 * @return registration handle; closing it unregisters and closes the
	 * extension.
	 */
	AutoCloseable registerExtension(VulkanRenderExtension extension);
}
