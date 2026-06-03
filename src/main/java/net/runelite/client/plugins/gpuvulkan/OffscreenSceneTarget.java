/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package net.runelite.client.plugins.gpuvulkan;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

final class OffscreenSceneTarget implements AutoCloseable
{
	private final VulkanDevice device;
	private final RenderPass renderPass;
	private final int format;
	private final int samples;

	private int width;
	private int height;
	private long colorImage;
	private long colorMemory;
	private long colorView;
	private long sampler;
	private DepthBuffer depth;
	private MsaaColorBuffer msaaColor;
	private long framebuffer;

	OffscreenSceneTarget(VulkanDevice device, RenderPass renderPass, int width, int height, int format, int samples)
	{
		this.device = device;
		this.renderPass = renderPass;
		this.format = format;
		this.samples = samples;
		recreate(width, height);
	}

	void recreate(int width, int height)
	{
		width = Math.max(width, 1);
		height = Math.max(height, 1);
		if (this.width == width && this.height == height && framebuffer != VK_NULL_HANDLE)
		{
			return;
		}
		destroy();
		this.width = width;
		this.height = height;
		createColor();
		depth = new DepthBuffer(device, width, height, samples);
		if (samples != VK_SAMPLE_COUNT_1_BIT)
		{
			msaaColor = new MsaaColorBuffer(device, width, height, format, samples);
		}
		createFramebuffer();
	}

	int width()
	{
		return width;
	}

	int height()
	{
		return height;
	}

	long framebuffer()
	{
		return framebuffer;
	}

	long colorImage()
	{
		return colorImage;
	}

	long colorView()
	{
		return colorView;
	}

	long sampler()
	{
		return sampler;
	}

	private void createColor()
	{
		try (MemoryStack stack = stackPush())
		{
			VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
				.sType$Default()
				.imageType(VK_IMAGE_TYPE_2D)
				.format(format)
				.extent(e -> e.width(width).height(height).depth(1))
				.mipLevels(1)
				.arrayLayers(1)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
					| VK_IMAGE_USAGE_SAMPLED_BIT
					| VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

			LongBuffer pImage = stack.mallocLong(1);
			Vk.check("vkCreateImage (offscreen scene)", vkCreateImage(device.handle(), info, null, pImage));
			colorImage = pImage.get(0);

			VkMemoryRequirements memReq = VkMemoryRequirements.calloc(stack);
			vkGetImageMemoryRequirements(device.handle(), colorImage, memReq);
			int memType = Buffer.findMemoryType(device, memReq.memoryTypeBits(),
				VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);
			VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
				.sType$Default()
				.allocationSize(memReq.size())
				.memoryTypeIndex(memType);

			LongBuffer pMem = stack.mallocLong(1);
			Vk.check("vkAllocateMemory (offscreen scene)", vkAllocateMemory(device.handle(), alloc, null, pMem));
			colorMemory = pMem.get(0);
			vkBindImageMemory(device.handle(), colorImage, colorMemory, 0);

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
				.sType$Default()
				.image(colorImage)
				.viewType(VK_IMAGE_VIEW_TYPE_2D)
				.format(format);
			viewInfo.subresourceRange()
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0).levelCount(1)
				.baseArrayLayer(0).layerCount(1);
			LongBuffer pView = stack.mallocLong(1);
			Vk.check("vkCreateImageView (offscreen scene)", vkCreateImageView(device.handle(), viewInfo, null, pView));
			colorView = pView.get(0);

			VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
				.sType$Default()
				.magFilter(VK_FILTER_LINEAR)
				.minFilter(VK_FILTER_LINEAR)
				.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
				.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
				.addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
				.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
				.minLod(0f)
				.maxLod(0f)
				.unnormalizedCoordinates(false);
			LongBuffer pSampler = stack.mallocLong(1);
			Vk.check("vkCreateSampler (offscreen scene)", vkCreateSampler(device.handle(), samplerInfo, null, pSampler));
			sampler = pSampler.get(0);
		}
	}

	private void createFramebuffer()
	{
		try (MemoryStack stack = stackPush())
		{
			LongBuffer attachments = msaaColor != null
				? stack.longs(msaaColor.view(), depth.view(), colorView)
				: stack.longs(colorView, depth.view());
			VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
				.sType$Default()
				.renderPass(renderPass.handle())
				.pAttachments(attachments)
				.width(width)
				.height(height)
				.layers(1);
			LongBuffer p = stack.mallocLong(1);
			Vk.check("vkCreateFramebuffer (offscreen scene)", vkCreateFramebuffer(device.handle(), info, null, p));
			framebuffer = p.get(0);
		}
	}

	@Override
	public void close()
	{
		destroy();
	}

	private void destroy()
	{
		if (framebuffer != VK_NULL_HANDLE)
		{
			vkDestroyFramebuffer(device.handle(), framebuffer, null);
			framebuffer = VK_NULL_HANDLE;
		}
		if (msaaColor != null)
		{
			msaaColor.close();
			msaaColor = null;
		}
		if (depth != null)
		{
			depth.close();
			depth = null;
		}
		if (sampler != VK_NULL_HANDLE)
		{
			vkDestroySampler(device.handle(), sampler, null);
			sampler = VK_NULL_HANDLE;
		}
		if (colorView != VK_NULL_HANDLE)
		{
			vkDestroyImageView(device.handle(), colorView, null);
			colorView = VK_NULL_HANDLE;
		}
		if (colorImage != VK_NULL_HANDLE)
		{
			vkDestroyImage(device.handle(), colorImage, null);
			colorImage = VK_NULL_HANDLE;
		}
		if (colorMemory != VK_NULL_HANDLE)
		{
			vkFreeMemory(device.handle(), colorMemory, null);
			colorMemory = VK_NULL_HANDLE;
		}
	}
}
