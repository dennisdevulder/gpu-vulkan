package net.runelite.client.plugins.gpuvulkan;

import java.awt.Canvas;

import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;

/**
 * Wraps a {@code VkSurfaceKHR} created from an AWT canvas via JAWT. Surface
 * creation is delegated to a {@link PlatformSurface} that knows the right
 * Vulkan extension + JAWT layout for the current OS.
 */
final class VulkanSurface implements AutoCloseable
{
	private final VulkanInstance instance;
	private final long handle;

	VulkanSurface(VulkanInstance instance, PlatformSurface platform, Canvas canvas)
	{
		this.instance = instance;
		this.handle = platform.createSurface(instance, canvas);
	}

	long handle()
	{
		return handle;
	}

	@Override
	public void close()
	{
		vkDestroySurfaceKHR(instance.handle(), handle, null);
	}
}
