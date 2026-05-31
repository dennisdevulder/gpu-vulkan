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
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Device-local 2D texture with view + sampler. Tracks current image layout
 * so {@link #transitionLayout} can pick the correct stage/access masks.
 *
 * <p>M4 uses a single texture sized to the canvas, recreated when the canvas
 * resizes.
 */
final class Texture implements AutoCloseable
{
	private final VulkanDevice device;
	private final long image;
	private final long memory;
	private final long view;
	private final long sampler;
	private final int width;
	private final int height;
	private int currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;

	Texture(VulkanDevice device, int width, int height, int format)
	{
		this.device = device;
		this.width = width;
		this.height = height;

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
				.usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

			LongBuffer pImage = stack.mallocLong(1);
			Vk.check("vkCreateImage", vkCreateImage(device.handle(), info, null, pImage));
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
			if (vkAllocateMemory(device.handle(), alloc, null, pMem) != VK_SUCCESS)
			{
				vkDestroyImage(device.handle(), image, null);
				throw new RuntimeException("vkAllocateMemory failed (texture)");
			}
			memory = pMem.get(0);
			vkBindImageMemory(device.handle(), image, memory, 0);

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
			Vk.check("vkCreateImageView", vkCreateImageView(device.handle(), viewInfo, null, pView));
			view = pView.get(0);

			VkSamplerCreateInfo sampInfo = VkSamplerCreateInfo.calloc(stack)
				.sType$Default()
				.magFilter(VK_FILTER_LINEAR)
				.minFilter(VK_FILTER_LINEAR)
				.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
				.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
				.addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
				.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
				.borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
				.unnormalizedCoordinates(false);

			LongBuffer pSamp = stack.mallocLong(1);
			Vk.check("vkCreateSampler", vkCreateSampler(device.handle(), sampInfo, null, pSamp));
			sampler = pSamp.get(0);
		}
	}

	long image() { return image; }
	long view() { return view; }
	long sampler() { return sampler; }
	int width() { return width; }
	int height() { return height; }

	/**
	 * Layout-transition helper for the three transitions the UI texture cycle
	 * needs:
	 * <ol>
	 *   <li>{@code UNDEFINED -> TRANSFER_DST_OPTIMAL} — initial upload.
	 *       {@code TOP_OF_PIPE} as the src stage is correct for an initial
	 *       transition: there are no prior writes to synchronise against,
	 *       only the layout switch itself.</li>
	 *   <li>{@code TRANSFER_DST_OPTIMAL -> SHADER_READ_ONLY_OPTIMAL} — after
	 *       upload, before sampling.</li>
	 *   <li>{@code SHADER_READ_ONLY_OPTIMAL -> TRANSFER_DST_OPTIMAL} — start of
	 *       the next frame's upload cycle.</li>
	 * </ol>
	 * Any other (old, new) pair throws — the texture's usage pattern is
	 * deliberately constrained and adding new transitions should be a
	 * conscious change, not a silently-accepted one.
	 */
	void transitionLayout(VkCommandBuffer cmd, int newLayout)
	{
		try (MemoryStack stack = stackPush())
		{
			int srcStage, dstStage, srcAccess, dstAccess;
			if (currentLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
			{
				srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
				dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
				srcAccess = 0;
				dstAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
			}
			else if (currentLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
			{
				srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
				dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
				srcAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
				dstAccess = VK_ACCESS_SHADER_READ_BIT;
			}
			else if (currentLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
			{
				srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
				dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
				srcAccess = VK_ACCESS_SHADER_READ_BIT;
				dstAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
			}
			else
			{
				throw new IllegalStateException(
					"Texture.transitionLayout: unsupported (oldLayout=" + currentLayout
						+ ", newLayout=" + newLayout + "). Only the UI upload cycle's "
						+ "three transitions are wired up — add a new branch with the "
						+ "matching stage/access masks if you need another.");
			}

			VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
			barrier.get(0)
				.sType$Default()
				.srcAccessMask(srcAccess)
				.dstAccessMask(dstAccess)
				.oldLayout(currentLayout)
				.newLayout(newLayout)
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.image(image)
				.subresourceRange(r -> r
					.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
					.baseMipLevel(0).levelCount(1)
					.baseArrayLayer(0).layerCount(1));

			vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
			currentLayout = newLayout;
		}
	}

	/** Records a buffer→image copy. The image must already be in TRANSFER_DST_OPTIMAL. */
	void recordCopyFrom(VkCommandBuffer cmd, Buffer staging, int copyWidth, int copyHeight)
	{
		try (MemoryStack stack = stackPush())
		{
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0)
				.bufferOffset(0)
				.bufferRowLength(0)
				.bufferImageHeight(0)
				.imageOffset(o -> o.set(0, 0, 0))
				.imageExtent(e -> e.width(copyWidth).height(copyHeight).depth(1));
			region.get(0).imageSubresource()
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.mipLevel(0)
				.baseArrayLayer(0).layerCount(1);

			vkCmdCopyBufferToImage(cmd, staging.handle(), image,
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
		}
	}

	/** Records full-width row-range copies from a tightly packed full-image staging buffer. */
	void recordCopyRowsFrom(VkCommandBuffer cmd, Buffer staging, int copyWidth, int[] rowStarts, int[] rowHeights, int rangeCount)
	{
		if (rangeCount <= 0)
		{
			return;
		}
		try (MemoryStack stack = stackPush())
		{
			VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(rangeCount, stack);
			for (int i = 0; i < rangeCount; i++)
			{
				int rowStart = rowStarts[i];
				int rowHeight = rowHeights[i];
				regions.get(i)
					.bufferOffset((long) rowStart * copyWidth * Integer.BYTES)
					.bufferRowLength(copyWidth)
					.bufferImageHeight(height)
					.imageOffset(o -> o.set(0, rowStart, 0))
					.imageExtent(e -> e.width(copyWidth).height(rowHeight).depth(1));
				regions.get(i).imageSubresource()
					.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
					.mipLevel(0)
					.baseArrayLayer(0).layerCount(1);
			}

			vkCmdCopyBufferToImage(cmd, staging.handle(), image,
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);
		}
	}

	@Override
	public void close()
	{
		vkDestroySampler(device.handle(), sampler, null);
		vkDestroyImageView(device.handle(), view, null);
		vkDestroyImage(device.handle(), image, null);
		vkFreeMemory(device.handle(), memory, null);
	}
}
