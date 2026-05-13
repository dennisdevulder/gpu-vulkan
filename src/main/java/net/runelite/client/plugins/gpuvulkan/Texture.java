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
			if (vkCreateImage(device.handle(), info, null, pImage) != VK_SUCCESS)
			{
				throw new RuntimeException("vkCreateImage failed");
			}
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
			if (vkCreateImageView(device.handle(), viewInfo, null, pView) != VK_SUCCESS)
			{
				throw new RuntimeException("vkCreateImageView failed");
			}
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
			if (vkCreateSampler(device.handle(), sampInfo, null, pSamp) != VK_SUCCESS)
			{
				throw new RuntimeException("vkCreateSampler failed");
			}
			sampler = pSamp.get(0);
		}
	}

	long image() { return image; }
	long view() { return view; }
	long sampler() { return sampler; }
	int width() { return width; }
	int height() { return height; }

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
				throw new IllegalStateException("Unsupported transition: " + currentLayout + " -> " + newLayout);
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

	@Override
	public void close()
	{
		vkDestroySampler(device.handle(), sampler, null);
		vkDestroyImageView(device.handle(), view, null);
		vkDestroyImage(device.handle(), image, null);
		vkFreeMemory(device.handle(), memory, null);
	}
}
