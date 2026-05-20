package net.runelite.client.plugins.gpuvulkan;

import net.runelite.api.Client;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderDevice;

/**
 * Stable host context exposed to Vulkan render extensions.
 *
 * <p>The backend still owns the Vulkan instance, device, surface, swapchain and
 * frame lifecycle. Extensions can inspect handles when they need to allocate
 * their own Vulkan resources, but they should not destroy backend-owned
 * objects.
 */
public interface VulkanRenderContext
{
	Client client();

	GpuVulkanPluginConfig config();

	/**
	 * Shared rendering-device facade for creating bind groups, pipelines,
	 * shader modules and streaming images.
	 */
	RenderDevice renderer();

	/**
	 * Creates an extension-owned scene renderer using the backend's current
	 * Vulkan device, frame sync, render pass and texture array.
	 */
	VulkanSceneRenderer createSceneRenderer();

	/**
	 * Vulkan video encode capability context. The context is always present,
	 * but {@link VulkanEncodeContext#isAvailable()} may be false when the
	 * current backend device was not created with encode queues/extensions.
	 */
	VulkanEncodeContext encode();

	String deviceName();

	long deviceHandle();

	long physicalDeviceHandle();

	long renderPassHandle();

	int renderPassSamples();

	int graphicsQueueFamily();

	int framesInFlight();
}
