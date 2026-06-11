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
package com.gpuvulkan;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Multi-sampled color attachment used as the renderpass's primary color
 * target. The renderpass resolves it down into the single-sampled swapchain
 * image as the resolve attachment, so the final presented frame is at 1×
 * sampling but edges have been MSAA-resolved.
 *
 * <p>Same lifecycle as {@link DepthBuffer}: created with the swapchain extent
 * + chosen sample count, rebuilt in place via {@link #recreate} on swapchain
 * resize. {@code TRANSIENT_ATTACHMENT} usage hints that the contents don't
 * need to persist past the renderpass — drivers can use tiled/lazy memory.
 */
final class MsaaColorBuffer implements AutoCloseable
{
	private final VulkanDevice device;
	private final int format;
	private final int samples;
	private long image;
	private long memory;
	private long view;

	MsaaColorBuffer(VulkanDevice device, int width, int height, int format, int samples)
	{
		this.device = device;
		this.format = format;
		this.samples = samples;
		create(width, height);
	}

	void recreate(int width, int height)
	{
		destroy();
		create(width, height);
	}

	long view()
	{
		return view;
	}

	@Override
	public void close()
	{
		destroy();
	}

	private void create(int width, int height)
	{
		Vk.require(width > 0 && height > 0,
			"MSAA color buffer extent must be positive, got " + width + "x" + height);
		try (MemoryStack stack = stackPush())
		{
			VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
				.sType$Default()
				.imageType(VK_IMAGE_TYPE_2D)
				.format(format)
				.extent(e -> e.width(width).height(height).depth(1))
				.mipLevels(1)
				.arrayLayers(1)
				.samples(samples)
				.tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
					| VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

			LongBuffer pImage = stack.mallocLong(1);
			Vk.check("vkCreateImage (msaa color)", vkCreateImage(device.handle(), info, null, pImage));
			image = pImage.get(0);

			VkMemoryRequirements memReq = VkMemoryRequirements.calloc(stack);
			vkGetImageMemoryRequirements(device.handle(), image, memReq);

			int memType = Buffer.findMemoryType(device, memReq.memoryTypeBits(),
				VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);
			VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
				.sType$Default()
				.allocationSize(memReq.size())
				.memoryTypeIndex(memType);

			LongBuffer pMem = stack.mallocLong(1);
			int allocResult = vkAllocateMemory(device.handle(), alloc, null, pMem);
			if (allocResult != VK_SUCCESS)
			{
				vkDestroyImage(device.handle(), image, null);
				throw Vk.fail("vkAllocateMemory (msaa color)", allocResult);
			}
			memory = pMem.get(0);
			Vk.check("vkBindImageMemory (msaa color)", vkBindImageMemory(device.handle(), image, memory, 0));

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
				.sType$Default()
				.image(image)
				.viewType(VK_IMAGE_VIEW_TYPE_2D)
				.format(format);
			viewInfo.subresourceRange()
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0).levelCount(1)
				.baseArrayLayer(0).layerCount(1);

			LongBuffer pView = stack.mallocLong(1);
			Vk.check("vkCreateImageView (msaa color)", vkCreateImageView(device.handle(), viewInfo, null, pView));
			view = pView.get(0);
		}
	}

	private void destroy()
	{
		if (view != VK_NULL_HANDLE)
		{
			vkDestroyImageView(device.handle(), view, null);
			view = VK_NULL_HANDLE;
		}
		if (image != VK_NULL_HANDLE)
		{
			vkDestroyImage(device.handle(), image, null);
			image = VK_NULL_HANDLE;
		}
		if (memory != VK_NULL_HANDLE)
		{
			vkFreeMemory(device.handle(), memory, null);
			memory = VK_NULL_HANDLE;
		}
	}
}
