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
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Per-frame (imageAvailable semaphore + inFlight fence, FRAMES_IN_FLIGHT slots)
 * and per-swapchain-image (renderFinished semaphore) sync primitives.
 */
final class FrameSync implements AutoCloseable
{
	static final int FRAMES_IN_FLIGHT = 3;

	private final VulkanDevice device;
	private final long[] imageAvailable = new long[FRAMES_IN_FLIGHT];
	private final long[] inFlight = new long[FRAMES_IN_FLIGHT];
	private long[] renderFinished;
	private int currentFrame;

	FrameSync(VulkanDevice device)
	{
		this.device = device;
		try (MemoryStack stack = stackPush())
		{
			VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
			VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
				.sType$Default()
				.flags(VK_FENCE_CREATE_SIGNALED_BIT);

			for (int i = 0; i < FRAMES_IN_FLIGHT; i++)
			{
				imageAvailable[i] = createSemaphore(stack, semInfo);
				inFlight[i] = createFence(stack, fenceInfo);
			}
		}
		// renderFinished[] is sized later via recreateRenderFinished so this
		// disposable registers BEFORE the Swapchain: the LIFO close-stack must
		// destroy the Swapchain first (WSI holds refs) or AMD/RADV hangs.
	}

	void recreateRenderFinished(int imageCount)
	{
		if (renderFinished != null && renderFinished.length >= imageCount)
		{
			return;
		}

		try (MemoryStack stack = stackPush())
		{
			int oldCount = renderFinished == null ? 0 : renderFinished.length;
			long[] resized = new long[imageCount];
			if (oldCount > 0)
			{
				System.arraycopy(renderFinished, 0, resized, 0, oldCount);
			}
			VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
			for (int i = oldCount; i < imageCount; i++)
			{
				resized[i] = createSemaphore(stack, info);
			}
			renderFinished = resized;
		}
	}

	long imageAvailable()
	{
		return imageAvailable[currentFrame];
	}

	long renderFinishedFor(int imageIdx)
	{
		return renderFinished[imageIdx];
	}

	long inFlightFence()
	{
		return inFlight[currentFrame];
	}

	void waitAllInFlight()
	{
		try (MemoryStack stack = stackPush())
		{
			Vk.check("vkWaitForFences (all in-flight)",
				vkWaitForFences(device.handle(), stack.longs(inFlight), true, Long.MAX_VALUE));
		}
	}

	int currentFrame()
	{
		return currentFrame;
	}

	void advance()
	{
		currentFrame = (currentFrame + 1) % FRAMES_IN_FLIGHT;
	}

	@Override
	public void close()
	{
		for (int i = 0; i < FRAMES_IN_FLIGHT; i++)
		{
			if (imageAvailable[i] != VK_NULL_HANDLE)
			{
				vkDestroySemaphore(device.handle(), imageAvailable[i], null);
				imageAvailable[i] = VK_NULL_HANDLE;
			}
			if (inFlight[i] != VK_NULL_HANDLE)
			{
				vkDestroyFence(device.handle(), inFlight[i], null);
				inFlight[i] = VK_NULL_HANDLE;
			}
		}
		if (renderFinished != null)
		{
			for (long s : renderFinished)
			{
				if (s != VK_NULL_HANDLE) vkDestroySemaphore(device.handle(), s, null);
			}
			renderFinished = null;
		}
	}

	private long createSemaphore(MemoryStack stack, VkSemaphoreCreateInfo info)
	{
		LongBuffer p = stack.mallocLong(1);
		Vk.check("vkCreateSemaphore", vkCreateSemaphore(device.handle(), info, null, p));
		return p.get(0);
	}

	private long createFence(MemoryStack stack, VkFenceCreateInfo info)
	{
		LongBuffer p = stack.mallocLong(1);
		Vk.check("vkCreateFence", vkCreateFence(device.handle(), info, null, p));
		return p.get(0);
	}
}
