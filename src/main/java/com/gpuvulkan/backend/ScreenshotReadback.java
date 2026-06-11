/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

final class ScreenshotReadback implements AutoCloseable
{
	private final VulkanDevice device;
	private final Buffer[] buffers = new Buffer[FrameSync.FRAMES_IN_FLIGHT];
	private int width;
	private int height;
	private int lastSlot = -1;

	ScreenshotReadback(VulkanDevice device)
	{
		this.device = device;
	}

	void recordCopy(VkCommandBuffer cmd, long image, int width, int height, int slot)
	{
		ensureBuffer(width, height, slot);
		lastSlot = slot;
		int presentLayout = device.supportsMetalObjects()
			? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
			: KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
		try (MemoryStack stack = stackPush())
		{
			transition(stack, cmd, image,
				presentLayout,
				VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
				VK_PIPELINE_STAGE_TRANSFER_BIT,
				0,
				VK_ACCESS_TRANSFER_READ_BIT);

			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0)
				.bufferOffset(0)
				.bufferRowLength(0)
				.bufferImageHeight(0)
				.imageOffset(o -> o.set(0, 0, 0))
				.imageExtent(e -> e.width(width).height(height).depth(1));
			region.get(0).imageSubresource()
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.mipLevel(0)
				.baseArrayLayer(0).layerCount(1);
			vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				buffers[slot].handle(), region);

			transition(stack, cmd, image,
				VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				presentLayout,
				VK_PIPELINE_STAGE_TRANSFER_BIT,
				VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
				VK_ACCESS_TRANSFER_READ_BIT,
				0);
		}
	}

	Image toImage()
	{
		if (lastSlot < 0 || buffers[lastSlot] == null || width <= 0 || height <= 0)
		{
			return null;
		}
		Buffer buffer = buffers[lastSlot];
		buffer.invalidateIfNeeded();
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] dst = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		ByteBuffer bytes = buffer.mappedByteBuffer().duplicate();
		bytes.clear();
		bytes.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(dst, 0, width * height);
		return image;
	}

	private void ensureBuffer(int width, int height, int slot)
	{
		if (buffers[slot] != null && this.width == width && this.height == height)
		{
			return;
		}
		if (this.width != width || this.height != height)
		{
			closeBuffers();
			this.width = width;
			this.height = height;
		}
		if (buffers[slot] != null)
		{
			buffers[slot].close();
		}
		buffers[slot] = new Buffer(device, (long) width * height * Integer.BYTES,
			VK_BUFFER_USAGE_TRANSFER_DST_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
			VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
		buffers[slot].mapPersistent();
	}

	private void transition(MemoryStack stack, VkCommandBuffer cmd, long image,
		int oldLayout, int newLayout, int srcStage, int dstStage,
		int srcAccess, int dstAccess)
	{
		VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
		barrier.get(0)
			.sType$Default()
			.srcAccessMask(srcAccess)
			.dstAccessMask(dstAccess)
			.oldLayout(oldLayout)
			.newLayout(newLayout)
			.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
			.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
			.image(image)
			.subresourceRange(r -> r
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0).levelCount(1)
				.baseArrayLayer(0).layerCount(1));
		vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
	}

	@Override
	public void close()
	{
		closeBuffers();
	}

	private void closeBuffers()
	{
		for (int i = 0; i < buffers.length; i++)
		{
			if (buffers[i] != null)
			{
				buffers[i].close();
				buffers[i] = null;
			}
		}
		lastSlot = -1;
	}
}
