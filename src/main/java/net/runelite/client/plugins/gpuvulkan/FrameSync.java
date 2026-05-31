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

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Per-frame and per-image synchronisation primitives.
 *
 * <p>Two-tier synchronisation, the standard Vulkan pattern:
 * <ul>
 * <li>Per-frame (FRAMES_IN_FLIGHT slots): {@code imageAvailable} semaphore and
 * {@code inFlight} fence — both tied to the CPU's frame slot, signalled by
 * vkAcquireNextImageKHR / vkQueueSubmit and waited on at the start of the
 * next iteration of that slot.</li>
 * <li>Per-image: {@code renderFinished} semaphore — tied to the swapchain
 * image, signalled by vkQueueSubmit and consumed by vkQueuePresentKHR.
 * Lives this long because present's wait can span frames.</li>
 * </ul>
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
		// renderFinished[] is per-swapchain-image and is sized via
		// recreateRenderFinished(...) once the Swapchain exists. We deliberately
		// don't take swapchainImageCount in the ctor: it lets us register this
		// disposable BEFORE the Swapchain so the LIFO close-stack destroys the
		// Swapchain first (releasing WSI engine references to renderFinished[*])
		// and only then destroys the semaphores. Reverse order hangs AMD/RADV
		// in vkDestroySemaphore waiting for the WSI present to complete.
	}

	void recreateRenderFinished(int imageCount)
	{
		try (MemoryStack stack = stackPush())
		{
			if (renderFinished != null)
			{
				vkDeviceWaitIdle(device.handle());
				for (long s : renderFinished)
				{
					vkDestroySemaphore(device.handle(), s, null);
				}
			}
			renderFinished = new long[imageCount];
			VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
			for (int i = 0; i < imageCount; i++)
			{
				renderFinished[i] = createSemaphore(stack, info);
			}
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
			if (imageAvailable[i] != VK_NULL_HANDLE) vkDestroySemaphore(device.handle(), imageAvailable[i], null);
			if (inFlight[i] != VK_NULL_HANDLE) vkDestroyFence(device.handle(), inFlight[i], null);
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
