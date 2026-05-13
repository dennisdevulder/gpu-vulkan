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
		create(desiredWidth, desiredHeight, VK_NULL_HANDLE);
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
		// Drain queue submits before tearing down the old per-image views.
		// Note: this does NOT drain the WSI presentation engine — that's why
		// we pass the old VkSwapchainKHR handle to the new create() call as
		// oldSwapchain (per VkSwapchainCreateInfoKHR spec): the driver
		// performs a smooth handover, retiring the old swapchain (releasing
		// its WSI references) and letting us destroy the old handle cleanly.
		vkDeviceWaitIdle(device.handle());
		long retiringHandle = handle;
		long[] retiringViews = imageViews;
		// Clear our state so create() doesn't try to destroy what it's about
		// to inherit-from. We keep retiringHandle in scope to pass as
		// oldSwapchain and to destroy after the new chain is built.
		handle = VK_NULL_HANDLE;
		imageViews = new long[0];
		images = new long[0];
		create(desiredWidth, desiredHeight, retiringHandle);
		// Now destroy the retired swapchain + its old views. Per spec the
		// retired swapchain is in a state where it can no longer be acquired
		// from but is still safe to destroy.
		for (long view : retiringViews)
		{
			if (view != VK_NULL_HANDLE) vkDestroyImageView(device.handle(), view, null);
		}
		if (retiringHandle != VK_NULL_HANDLE)
		{
			KHRSwapchain.vkDestroySwapchainKHR(device.handle(), retiringHandle, null);
		}
	}

	@Override
	public void close()
	{
		destroy();
	}

	private void create(int desiredWidth, int desiredHeight, long oldSwapchainHandle)
	{
		try (MemoryStack stack = stackPush())
		{
			VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
			KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.physicalDevice(), surface.handle(), caps);

			// currentExtent == (0xFFFFFFFF, 0xFFFFFFFF) means "we don't care, you pick".
			// Otherwise we must match it. Java reads those as -1.
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

			VkSurfaceFormatKHR format = pickFormat(stack);
			imageFormat = format.format();

			int presentMode = pickPresentMode(stack);

			int imageCount = caps.minImageCount() + 1;
			if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount())
			{
				imageCount = caps.maxImageCount();
			}

			VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
				.sType$Default()
				.surface(surface.handle())
				.minImageCount(imageCount)
				.imageFormat(format.format())
				.imageColorSpace(format.colorSpace())
				.imageExtent(e -> e.width(width).height(height))
				.imageArrayLayers(1)
				.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
				.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.preTransform(caps.currentTransform())
				.compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
				.presentMode(presentMode)
				.clipped(true)
				.oldSwapchain(oldSwapchainHandle);

			LongBuffer pSwap = stack.mallocLong(1);
			int r = KHRSwapchain.vkCreateSwapchainKHR(device.handle(), info, null, pSwap);
			if (r != VK_SUCCESS)
			{
				throw new RuntimeException("vkCreateSwapchainKHR failed: " + r);
			}
			handle = pSwap.get(0);

			IntBuffer count = stack.mallocInt(1);
			KHRSwapchain.vkGetSwapchainImagesKHR(device.handle(), handle, count, null);
			LongBuffer pImages = stack.mallocLong(count.get(0));
			KHRSwapchain.vkGetSwapchainImagesKHR(device.handle(), handle, count, pImages);

			images = new long[count.get(0)];
			imageViews = new long[count.get(0)];
			for (int i = 0; i < images.length; i++)
			{
				images[i] = pImages.get(i);
				imageViews[i] = createView(stack, images[i], imageFormat);
			}
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
		if (vkCreateImageView(device.handle(), info, null, p) != VK_SUCCESS)
		{
			throw new RuntimeException("vkCreateImageView failed");
		}
		return p.get(0);
	}

	private VkSurfaceFormatKHR pickFormat(MemoryStack stack)
	{
		IntBuffer count = stack.mallocInt(1);
		KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device.physicalDevice(), surface.handle(), count, null);
		VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
		KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device.physicalDevice(), surface.handle(), count, formats);

		// Prefer UNORM over SRGB so the framebuffer does NOT apply implicit
		// linear→sRGB conversion on shader output. OSRS art (HSL-decoded RGB,
		// UI BufferProvider pixels) is already in display-sRGB space — if we
		// hand sRGB-intended values to an SRGB framebuffer, Vulkan treats
		// them as linear and re-encodes, ~2x-brightening everything. Stock
		// GpuPlugin uses a linear GL framebuffer (no auto-conversion) — UNORM
		// matches that behavior exactly: shader output bytes land on the
		// monitor unchanged.
		for (int i = 0; i < formats.capacity(); i++)
		{
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.format() == VK_FORMAT_B8G8R8A8_UNORM
				&& f.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
			{
				return VkSurfaceFormatKHR.create(f.address());
			}
		}
		// Fall back to SRGB if UNORM isn't offered (rare).
		for (int i = 0; i < formats.capacity(); i++)
		{
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.format() == VK_FORMAT_B8G8R8A8_SRGB
				&& f.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
			{
				return VkSurfaceFormatKHR.create(f.address());
			}
		}
		// Last resort: whatever the driver listed first — guaranteed to exist.
		return VkSurfaceFormatKHR.create(formats.get(0).address());
	}

	private int pickPresentMode(MemoryStack stack)
	{
		IntBuffer count = stack.mallocInt(1);
		KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device.physicalDevice(), surface.handle(), count, null);
		IntBuffer modes = stack.mallocInt(count.get(0));
		KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device.physicalDevice(), surface.handle(), count, modes);

		boolean hasImmediate = false, hasMailbox = false;
		for (int i = 0; i < modes.capacity(); i++)
		{
			int m = modes.get(i);
			if (m == KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR) hasImmediate = true;
			else if (m == KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR) hasMailbox = true;
		}

		// FIFO is always supported and is the spec-required fallback.
		switch (fpsMode)
		{
			case UNCAPPED:
				if (hasImmediate) return KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
				if (hasMailbox)   return KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
				return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
			case TRIPLE_BUFFER:
				if (hasMailbox)   return KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
				return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
			case VSYNC:
			default:
				return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
		}
	}

	private static int clamp(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	private void destroy()
	{
		// Drain GPU work right here, even though shutDown already called
		// vkDeviceWaitIdle. Confirmed via breadcrumbs: on AMD/RADV the JVM
		// silently exits inside vkDestroySwapchainKHR without this — the
		// outer queue-idle isn't sufficient by the time several disposes
		// (VulkanRenderer/Framebuffers/DepthBuffer) have run between it
		// and this call. Cheap belt-and-braces against WSI lifetime
		// state we can't otherwise drain through the public API.
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
