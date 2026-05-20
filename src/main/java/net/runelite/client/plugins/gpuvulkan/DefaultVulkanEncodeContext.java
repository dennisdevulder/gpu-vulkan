package net.runelite.client.plugins.gpuvulkan;

final class DefaultVulkanEncodeContext implements VulkanEncodeContext
{
	private final VulkanDevice device;

	DefaultVulkanEncodeContext(VulkanDevice device)
	{
		this.device = device;
	}

	@Override
	public boolean isDeviceCapable()
	{
		return hasExtensionLevelCapability();
	}

	@Override
	public boolean isAvailable()
	{
		return device.videoEncodeQueue() != null;
	}

	@Override
	public String unavailableReason()
	{
		if (!device.supportsVideoEncodeQueue())
		{
			return "No queue family advertises VK_QUEUE_VIDEO_ENCODE_BIT_KHR with VK_KHR_video_queue and VK_KHR_video_encode_queue.";
		}
		if (!device.supportsVideoEncodeH264() && !device.supportsVideoEncodeH265() && !device.supportsVideoEncodeAv1())
		{
			return "Device exposes the base video encode queue but no H.264, H.265, or AV1 encode codec extension.";
		}
		if (device.videoEncodeQueue() == null)
		{
			return "Backend has not enabled Vulkan video encode extensions/queue creation yet.";
		}
		return "";
	}

	boolean hasExtensionLevelCapability()
	{
		return device.supportsVideoEncodeQueue()
			&& (device.supportsVideoEncodeH264()
				|| device.supportsVideoEncodeH265()
				|| device.supportsVideoEncodeAv1());
	}

	@Override
	public boolean supportsH264()
	{
		return device.supportsVideoEncodeH264();
	}

	@Override
	public boolean supportsH265()
	{
		return device.supportsVideoEncodeH265();
	}

	@Override
	public boolean supportsAv1()
	{
		return device.supportsVideoEncodeAv1();
	}

	@Override
	public int encodeQueueFamily()
	{
		return device.videoEncodeQueueFamily();
	}

	@Override
	public long encodeQueueHandle()
	{
		return device.videoEncodeQueue() == null ? 0L : device.videoEncodeQueue().address();
	}

	@Override
	public long deviceHandle()
	{
		return device.handle().address();
	}

	@Override
	public long physicalDeviceHandle()
	{
		return device.physicalDevice().address();
	}
}
