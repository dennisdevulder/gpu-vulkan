/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * One VkFramebuffer per swapchain image, sharing the render pass and single
 * depth view. {@link #recreate} rebuilds in place so external references stay valid.
 */
final class Framebuffers implements AutoCloseable
{
	private final VulkanDevice device;
	private long[] handles = new long[0];

	Framebuffers(VulkanDevice device, RenderPass renderPass, Swapchain swapchain,
		DepthBuffer depthBuffer, MsaaColorBuffer msaaColor)
	{
		this.device = device;
		create(renderPass, swapchain, depthBuffer, msaaColor);
	}

	void recreate(RenderPass renderPass, Swapchain swapchain,
		DepthBuffer depthBuffer, MsaaColorBuffer msaaColor)
	{
		destroy();
		create(renderPass, swapchain, depthBuffer, msaaColor);
	}

	/** Framebuffers must drop BEFORE the swapchain destroys the image views
	 *  they reference (rebuildSwapchain ordering). */
	void destroyAll()
	{
		destroy();
	}

	long get(int i)
	{
		return handles[i];
	}

	@Override
	public void close()
	{
		destroy();
	}

	private void create(RenderPass renderPass, Swapchain swapchain,
		DepthBuffer depthBuffer, MsaaColorBuffer msaaColor)
	{
		long[] views = swapchain.imageViews();
		handles = new long[views.length];
		try (MemoryStack stack = stackPush())
		{
			for (int i = 0; i < views.length; i++)
			{
				// Attachment order matches RenderPass: [msaaColor, depth, resolve];
				// without MSAA the swapchain image IS the color target.
				LongBuffer attachments = msaaColor != null
					? stack.longs(msaaColor.view(), depthBuffer.view(), views[i])
					: stack.longs(views[i], depthBuffer.view());
				VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
					.sType$Default()
					.renderPass(renderPass.handle())
					.pAttachments(attachments)
					.width(swapchain.width())
					.height(swapchain.height())
					.layers(1);
				LongBuffer p = stack.mallocLong(1);
				Vk.check("vkCreateFramebuffer", vkCreateFramebuffer(device.handle(), info, null, p));
				handles[i] = p.get(0);
			}
		}
	}

	private void destroy()
	{
		for (long fb : handles)
		{
			if (fb != VK_NULL_HANDLE)
			{
				vkDestroyFramebuffer(device.handle(), fb, null);
			}
		}
		handles = new long[0];
	}
}
