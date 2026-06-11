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

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.VK13.*;

/**
 * VkSwapchainKHR + per-image VkImage / VkImageView. Picks BGRA8_SRGB (the
 * format basically every desktop GPU lists first) and FIFO present mode
 * (always available, V-synced — fine for an OSRS-like workload).
 *
 * <p>{@link #recreate(int, int)} tears down and rebuilds when the canvas
 * resizes or {@code vkAcquireNextImageKHR} returns {@code VK_ERROR_OUT_OF_DATE_KHR}.
 */
@lombok.extern.slf4j.Slf4j
final class Swapchain implements AutoCloseable
{
	private static final int CREATE_RETRIES = 4;

	private final VulkanDevice device;
	private final VulkanSurface surface;
	private final GpuVulkanPluginConfig.FpsMode fpsMode;
	private long handle;
	private long[] images = new long[0];
	private long[] imageViews = new long[0];
	private int imageFormat;
	private int width;
	private int height;

	Swapchain(VulkanDevice device, VulkanSurface surface, int desiredWidth, int desiredHeight,
		GpuVulkanPluginConfig.FpsMode fpsMode)
	{
		this.device = device;
		this.surface = surface;
		this.fpsMode = fpsMode;
		createWithRetry(desiredWidth, desiredHeight, VK_NULL_HANDLE);
	}

	long handle()
	{
		return handle;
	}

	int imageCount()
	{
		return images.length;
	}

	long[] imageViews()
	{
		return imageViews;
	}

	long image(int index)
	{
		return images[index];
	}

	int imageFormat()
	{
		return imageFormat;
	}

	int width()
	{
		return width;
	}

	int height()
	{
		return height;
	}

	void recreate(int desiredWidth, int desiredHeight)
	{
		// vkDeviceWaitIdle drains queue submits but NOT the WSI present
		// engine — so we pass the old VkSwapchainKHR via oldSwapchain
		// (spec-clean handover, driver retires + releases WSI refs).
		Vk.check("vkDeviceWaitIdle", vkDeviceWaitIdle(device.handle()));
		long retiringHandle = handle;
		long[] retiringViews = imageViews;
		handle = VK_NULL_HANDLE;
		imageViews = new long[0];
		images = new long[0];
		createWithRetry(desiredWidth, desiredHeight, retiringHandle);
		for (long view : retiringViews)
		{
			if (view != VK_NULL_HANDLE) vkDestroyImageView(device.handle(), view, null);
		}
		if (retiringHandle != VK_NULL_HANDLE)
		{
			// LANDMINE (MoltenVK #2609): second drain before destroying the
			// retired handle. Without it Apple Silicon shows artifacts when
			// the new swapchain still touches the old chain's Metal
			// resources at destroy time.
			Vk.check("vkDeviceWaitIdle", vkDeviceWaitIdle(device.handle()));
			KHRSwapchain.vkDestroySwapchainKHR(device.handle(), retiringHandle, null);
		}
	}

	@Override
	public void close()
	{
		destroy();
	}

	private void createWithRetry(int desiredWidth, int desiredHeight, long oldSwapchainHandle)
	{
		for (int attempt = 1; ; attempt++)
		{
			try
			{
				create(desiredWidth, desiredHeight, oldSwapchainHandle);
				return;
			}
			catch (StaleSurfaceException e)
			{
				if (attempt >= CREATE_RETRIES)
				{
					throw e;
				}
				log.debug("vkCreateSwapchainKHR reported stale surface during creation; retry {}/{}",
					attempt, CREATE_RETRIES);
				sleepBeforeRetry();
			}
		}
	}

	private void create(int desiredWidth, int desiredHeight, long oldSwapchainHandle)
	{
		try (MemoryStack stack = stackPush())
		{
			VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
			Vk.check("vkGetPhysicalDeviceSurfaceCapabilitiesKHR",
				KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.physicalDevice(), surface.handle(), caps));
			resolveExtent(caps, desiredWidth, desiredHeight);

			VkSurfaceFormatKHR format = pickFormat(stack);
			imageFormat = format.format();
			int presentMode = pickPresentMode(stack);

			// Triple-buffer pinned: MoltenVK's Runtime Guide recommends 3
			// on Apple Silicon for Direct-to-Display; other platforms
			// happen to land on 3 anyway via minImageCount+1.
			int imageCount = Math.max(3, caps.minImageCount());
			if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount())
			{
				imageCount = caps.maxImageCount();
			}
			log.info("Vulkan swapchain: {}x{} images={} presentMode={}",
				width, height, imageCount, presentModeName(presentMode));

			createSwapchainHandle(stack, caps, format, presentMode, imageCount, oldSwapchainHandle);
			fetchImagesAndViews(stack);
		}
	}

	// currentExtent == (0xFFFFFFFF, 0xFFFFFFFF) means "you pick"; Java reads
	// those as -1. Otherwise the surface extent must be matched exactly.
	private void resolveExtent(VkSurfaceCapabilitiesKHR caps, int desiredWidth, int desiredHeight)
	{
		VkExtent2D ext = caps.currentExtent();
		if (ext.width() != -1)
		{
			width = ext.width();
			height = ext.height();
		}
		else
		{
			width = clamp(desiredWidth, caps.minImageExtent().width(), caps.maxImageExtent().width());
			height = clamp(desiredHeight, caps.minImageExtent().height(), caps.maxImageExtent().height());
		}
	}

	private void createSwapchainHandle(MemoryStack stack, VkSurfaceCapabilitiesKHR caps,
		VkSurfaceFormatKHR format, int presentMode, int imageCount, long oldSwapchainHandle)
	{
		VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
			.sType$Default()
			.surface(surface.handle())
			.minImageCount(imageCount)
			.imageFormat(format.format())
			.imageColorSpace(format.colorSpace())
			.imageExtent(e -> e.width(width).height(height))
			.imageArrayLayers(1)
			.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
			.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
			.preTransform(caps.currentTransform())
			.compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
			.presentMode(presentMode)
			.clipped(true)
			.oldSwapchain(oldSwapchainHandle);

		LongBuffer pSwap = stack.mallocLong(1);
		int createResult = KHRSwapchain.vkCreateSwapchainKHR(device.handle(), info, null, pSwap);
		if (createResult == VK_ERROR_OUT_OF_DATE_KHR)
		{
			throw new StaleSurfaceException();
		}
		Vk.check("vkCreateSwapchainKHR", createResult);
		handle = pSwap.get(0);
	}

	private void fetchImagesAndViews(MemoryStack stack)
	{
		IntBuffer count = stack.mallocInt(1);
		Vk.check("vkGetSwapchainImagesKHR (count)",
			KHRSwapchain.vkGetSwapchainImagesKHR(device.handle(), handle, count, null));
		LongBuffer pImages = stack.mallocLong(count.get(0));
		Vk.check("vkGetSwapchainImagesKHR (images)",
			KHRSwapchain.vkGetSwapchainImagesKHR(device.handle(), handle, count, pImages));

		images = new long[count.get(0)];
		imageViews = new long[count.get(0)];
		for (int i = 0; i < images.length; i++)
		{
			images[i] = pImages.get(i);
			imageViews[i] = createView(stack, images[i], imageFormat);
		}
	}

	private static void sleepBeforeRetry()
	{
		try
		{
			Thread.sleep(16L);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new StaleSurfaceException();
		}
	}

	private static final class StaleSurfaceException extends RuntimeException
	{
		private StaleSurfaceException()
		{
			super("vkCreateSwapchainKHR failed: " + VK_ERROR_OUT_OF_DATE_KHR);
		}
	}

	private long createView(MemoryStack stack, long image, int fmt)
	{
		VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
			.sType$Default()
			.image(image)
			.viewType(VK_IMAGE_VIEW_TYPE_2D)
			.format(fmt);
		info.subresourceRange()
			.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
			.baseMipLevel(0).levelCount(1)
			.baseArrayLayer(0).layerCount(1);

		LongBuffer p = stack.mallocLong(1);
		Vk.check("vkCreateImageView (swapchain)", vkCreateImageView(device.handle(), info, null, p));
		return p.get(0);
	}

	private VkSurfaceFormatKHR pickFormat(MemoryStack stack)
	{
		IntBuffer count = stack.mallocInt(1);
		Vk.check("vkGetPhysicalDeviceSurfaceFormatsKHR (count)",
			KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device.physicalDevice(), surface.handle(), count, null));
		VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
		Vk.check("vkGetPhysicalDeviceSurfaceFormatsKHR (formats)",
			KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device.physicalDevice(), surface.handle(), count, formats));

		// LANDMINE: prefer UNORM. OSRS art is already in display-sRGB; an
		// SRGB framebuffer would re-encode it as linear→sRGB and
		// ~2x-brighten everything.
		for (int i = 0; i < formats.capacity(); i++)
		{
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.format() == VK_FORMAT_B8G8R8A8_UNORM
				&& f.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
			{
				return VkSurfaceFormatKHR.create(f.address());
			}
		}
		// Fallback: SRGB, then whatever the driver listed first.
		for (int i = 0; i < formats.capacity(); i++)
		{
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.format() == VK_FORMAT_B8G8R8A8_SRGB
				&& f.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
			{
				return VkSurfaceFormatKHR.create(f.address());
			}
		}
		return VkSurfaceFormatKHR.create(formats.get(0).address());
	}

	private int pickPresentMode(MemoryStack stack)
	{
		IntBuffer count = stack.mallocInt(1);
		Vk.check("vkGetPhysicalDeviceSurfacePresentModesKHR (count)",
			KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device.physicalDevice(), surface.handle(), count, null));
		IntBuffer modes = stack.mallocInt(count.get(0));
		Vk.check("vkGetPhysicalDeviceSurfacePresentModesKHR (modes)",
			KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device.physicalDevice(), surface.handle(), count, modes));

		boolean hasImmediate = false, hasMailbox = false, hasFifoRelaxed = false;
		for (int i = 0; i < modes.capacity(); i++)
		{
			int m = modes.get(i);
			if (m == KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR) hasImmediate = true;
			else if (m == KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR) hasMailbox = true;
			else if (m == KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR) hasFifoRelaxed = true;
		}

		// LANDMINE: avoid MAILBOX on macOS for paced modes. MoltenVK's
		// MAILBOX over CAMetalLayer can present drawables out of order for
		// one refresh, flickering the previous frame's average colour
		// through. UNCAPPED still needs IMMEDIATE/MAILBOX; FIFO hard-caps
		// presentation to the display refresh rate.
		boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
		if (isMac && fpsMode != GpuVulkanPluginConfig.FpsMode.UNCAPPED)
		{
			return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
		}
		switch (fpsMode)
		{
			case UNCAPPED:
				if (hasImmediate) return KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
				if (hasMailbox)   return KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
				return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
			case TRIPLE_BUFFER:
				if (hasMailbox)   return KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
				return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
			case ADAPTIVE_VSYNC:
				if (hasFifoRelaxed) return KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR;
				return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
			case VSYNC:
			default:
				return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
		}
	}

	private static String presentModeName(int presentMode)
	{
		switch (presentMode)
		{
			case KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR:
				return "IMMEDIATE";
			case KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR:
				return "MAILBOX";
			case KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR:
				return "FIFO_RELAXED";
			case KHRSurface.VK_PRESENT_MODE_FIFO_KHR:
				return "FIFO";
			default:
				return "unknown(" + presentMode + ")";
		}
	}

	private static int clamp(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	private void destroy()
	{
		// LANDMINE: drain again here. AMD/RADV silently exits the JVM
		// from inside vkDestroySwapchainKHR if the outer queue-idle gets
		// stale across intervening dispose calls.
		vkDeviceWaitIdle(device.handle());

		for (long view : imageViews)
		{
			if (view != VK_NULL_HANDLE)
			{
				vkDestroyImageView(device.handle(), view, null);
			}
		}
		imageViews = new long[0];
		images = new long[0];

		if (handle != VK_NULL_HANDLE)
		{
			KHRSwapchain.vkDestroySwapchainKHR(device.handle(), handle, null);
			handle = VK_NULL_HANDLE;
		}
	}
}
