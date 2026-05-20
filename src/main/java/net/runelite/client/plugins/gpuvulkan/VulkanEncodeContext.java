package net.runelite.client.plugins.gpuvulkan;

/**
 * Vulkan video encode capabilities exposed by the backend.
 *
 * <p>This is intentionally conservative: the current backend creates only the
 * render device/queue, so encode may be device-capable but not enabled yet.
 * Tracker-style plugins can use this context to decide whether Vulkan encode
 * is worth wiring up and to avoid duplicating physical-device discovery.
 */
public interface VulkanEncodeContext
{
	/**
	 * Returns true when the selected physical device advertises a video encode
	 * queue plus at least one supported encode codec extension. This does not
	 * validate concrete video profiles or image formats yet.
	 */
	boolean isDeviceCapable();

	boolean isAvailable();

	String unavailableReason();

	boolean supportsH264();

	boolean supportsH265();

	boolean supportsAv1();

	int encodeQueueFamily();

	long encodeQueueHandle();

	long deviceHandle();

	long physicalDeviceHandle();
}
