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
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMetalObjects;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkImportMetalTextureInfoEXT;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Per-CAMetalDrawable {@link org.lwjgl.vulkan.VK13#vkCreateImage VkImage} +
 * view + framebuffer cache for the macOS custom-present path. Replaces the
 * role of {@link Swapchain} on macOS.
 *
 * <p>{@code CAMetalLayer} recycles a small pool (≤ {@code maximumDrawableCount},
 * default 3) of drawables. Each recurrence brings the same
 * {@code id<MTLTexture>} pointer, so we key the cache by that pointer — first
 * sight: import the MTLTexture as a {@link org.lwjgl.vulkan.VK13#vkCreateImage
 * VkImage} via {@link VkImportMetalTextureInfoEXT}, build a colour view and a
 * framebuffer (MSAA layout: {@code [msaaColor, depth, this image]} matching
 * {@link RenderPass}); subsequent sights hit the cache.
 *
 * <p>The MoltenVK imports do NOT own the Metal texture's storage — we only
 * destroy the {@code VkImage} + view + framebuffer wrappers on close. The
 * underlying {@code id<MTLTexture>} belongs to its {@code CAMetalDrawable}.
 *
 * <p>Resize handling: when an entry's cached width/height no longer matches
 * the supplied dimensions (drawableSize changed on canvas resize), we drop
 * the entry and rebuild. Entries whose underlying MTLTexture has been
 * replaced will simply never be looked up again (new pointer ⇒ new entry);
 * the resize path explicitly flushes the map to free Vulkan-side wrappers.
 */
@Slf4j
final class MetalDrawableSet implements AutoCloseable
{
	/** Format must match the CAMetalLayer.pixelFormat we set in rlmtl.m
	 *  (MTLPixelFormatBGRA8Unorm). The render pass + UI/scene pipelines
	 *  already target this format on macOS. */
	static final int IMAGE_FORMAT = VK_FORMAT_B8G8R8A8_UNORM;

	private final VulkanDevice device;

	static final class Entry
	{
		long textureHandle;
		long image;
		long view;
		long framebuffer;
		int width;
		int height;
		int layout = VK_IMAGE_LAYOUT_UNDEFINED;
	}

	private final Map<Long, Entry> byTextureHandle = new HashMap<>();

	MetalDrawableSet(VulkanDevice device)
	{
		this.device = device;
	}

	/**
	 * @param textureHandle {@code id<MTLTexture>} pointer from
	 *                      {@link MacOSMetalHelper#nextDrawable()}'s
	 *                      element [1]
	 * @param width         drawable texture width in pixels
	 * @param height        drawable texture height in pixels
	 * @return cached or freshly-built entry for this texture
	 */
	Entry acquire(long textureHandle, int width, int height,
				  RenderPass renderPass, DepthBuffer depth, MsaaColorBuffer msaa)
	{
		Entry e = byTextureHandle.get(textureHandle);
		if (e != null && e.width == width && e.height == height)
		{
			return e;
		}
		if (e != null)
		{
			// Drawable size changed under us. Free the stale wrappers and
			// rebuild against the new dimensions.
			destroyEntry(e);
			byTextureHandle.remove(textureHandle);
		}

		e = buildEntry(textureHandle, width, height, renderPass, depth, msaa);
		byTextureHandle.put(textureHandle, e);
		return e;
	}

	/** Flushes all cached entries. Call on canvas resize before the depth /
	 *  MSAA buffers get rebuilt — the framebuffers reference those views, so
	 *  they MUST be torn down first. */
	void flush()
	{
		for (Entry e : byTextureHandle.values())
		{
			destroyEntry(e);
		}
		byTextureHandle.clear();
	}

	@Override
	public void close()
	{
		flush();
	}

	private Entry buildEntry(long textureHandle, int width, int height,
							 RenderPass renderPass, DepthBuffer depth, MsaaColorBuffer msaa)
	{
		try (MemoryStack stack = stackPush())
		{
			long image = importDrawableImage(stack, textureHandle, width, height);

			// Imported images come pre-bound to the Metal storage. The spec
			// note in VK_EXT_metal_objects: no vkBindImageMemory call —
			// MoltenVK has already wired the MTLTexture as the backing.

			long view = createDrawableView(stack, image);
			long framebuffer = createDrawableFramebuffer(stack, image, view, width, height, renderPass, depth, msaa);

			Entry e = new Entry();
			e.textureHandle = textureHandle;
			e.image = image;
			e.view = view;
			e.framebuffer = framebuffer;
			e.width = width;
			e.height = height;
			MacOSMetalHelper.retainObject(textureHandle);
			log.debug("MetalDrawableSet: built entry for MTLTexture 0x{} {}x{}",
				Long.toHexString(textureHandle), width, height);
			return e;
		}
	}

	private long importDrawableImage(MemoryStack stack, long textureHandle, int width, int height)
	{
		// Import the MTLTexture as a VkImage. MoltenVK reads the pointer
		// out of VkImportMetalTextureInfoEXT and binds it as the image's
		// Metal backing — no Vulkan-side allocation happens for the
		// storage. The plane (BGRA single plane = 0 / PLANE_0_BIT) is
		// the only sensible value for a CAMetalDrawable's color texture.
		VkImportMetalTextureInfoEXT importInfo = VkImportMetalTextureInfoEXT.calloc(stack)
			.sType$Default()
			.plane(VK_IMAGE_ASPECT_PLANE_0_BIT)
			.mtlTexture(textureHandle);

		VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
			.sType$Default()
			.pNext(importInfo.address())
			.imageType(VK_IMAGE_TYPE_2D)
			.format(IMAGE_FORMAT)
			.extent(ext -> ext.set(width, height, 1))
			.mipLevels(1)
			.arrayLayers(1)
			.samples(VK_SAMPLE_COUNT_1_BIT)
			.tiling(VK_IMAGE_TILING_OPTIMAL)
			.usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
				| VK_IMAGE_USAGE_TRANSFER_SRC_BIT
				| VK_IMAGE_USAGE_TRANSFER_DST_BIT)
			.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
			.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

		LongBuffer pImage = stack.mallocLong(1);
		Vk.check("vkCreateImage (imported MTLTexture)", vkCreateImage(device.handle(), imageInfo, null, pImage));
		return pImage.get(0);
	}

	private long createDrawableView(MemoryStack stack, long image)
	{
		VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
			.sType$Default()
			.image(image)
			.viewType(VK_IMAGE_VIEW_TYPE_2D)
			.format(IMAGE_FORMAT)
			.subresourceRange(rng -> rng
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0).levelCount(1)
				.baseArrayLayer(0).layerCount(1));
		LongBuffer pView = stack.mallocLong(1);
		if (vkCreateImageView(device.handle(), viewInfo, null, pView) != VK_SUCCESS)
		{
			vkDestroyImage(device.handle(), image, null);
			throw new RuntimeException("vkCreateImageView (imported MTLTexture) failed");
		}
		return pView.get(0);
	}

	private long createDrawableFramebuffer(MemoryStack stack, long image, long view, int width, int height,
		RenderPass renderPass, DepthBuffer depth, MsaaColorBuffer msaa)
	{
		// Framebuffer attachment order matches RenderPass declaration.
		// MSAA: [msaaColor, depth, drawable]. Non-MSAA: [drawable, depth].
		LongBuffer attachments = msaa != null
			? stack.longs(msaa.view(), depth.view(), view)
			: stack.longs(view, depth.view());
		VkFramebufferCreateInfo fbInfo = VkFramebufferCreateInfo.calloc(stack)
			.sType$Default()
			.renderPass(renderPass.handle())
			.pAttachments(attachments)
			.width(width)
			.height(height)
			.layers(1);
		LongBuffer pFb = stack.mallocLong(1);
		if (vkCreateFramebuffer(device.handle(), fbInfo, null, pFb) != VK_SUCCESS)
		{
			vkDestroyImageView(device.handle(), view, null);
			vkDestroyImage(device.handle(), image, null);
			throw new RuntimeException("vkCreateFramebuffer (drawable) failed");
		}
		return pFb.get(0);
	}

	private void destroyEntry(Entry e)
	{
		if (e.framebuffer != VK_NULL_HANDLE)
		{
			vkDestroyFramebuffer(device.handle(), e.framebuffer, null);
			e.framebuffer = VK_NULL_HANDLE;
		}
		if (e.view != VK_NULL_HANDLE)
		{
			vkDestroyImageView(device.handle(), e.view, null);
			e.view = VK_NULL_HANDLE;
		}
		if (e.image != VK_NULL_HANDLE)
		{
			vkDestroyImage(device.handle(), e.image, null);
			e.image = VK_NULL_HANDLE;
		}
		if (e.textureHandle != VK_NULL_HANDLE)
		{
			MacOSMetalHelper.releaseObject(e.textureHandle);
			e.textureHandle = VK_NULL_HANDLE;
		}
	}

	/** Accessors mirroring the {@link Swapchain#imageFormat()} hook so the
	 *  rest of the pipeline doesn't need a special case. */
	int imageFormat()
	{
		return IMAGE_FORMAT;
	}
}
