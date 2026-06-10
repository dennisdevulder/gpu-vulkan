/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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
			String detail = device.videoEncodeUnavailableDetail();
			return detail != null ? detail
				: "Backend did not create the video encode queue.";
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
