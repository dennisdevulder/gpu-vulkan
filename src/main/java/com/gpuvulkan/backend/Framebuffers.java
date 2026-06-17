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
				try
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
				catch (RuntimeException e)
				{
					destroy();
					throw e;
				}
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
