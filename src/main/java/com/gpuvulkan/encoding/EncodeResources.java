/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved. See VulkanEncoder.java for full license text.
 */
package com.gpuvulkan.encoding;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.*;
import static org.lwjgl.vulkan.KHRVideoQueue.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Per-clip GPU resources: the NV12 input image, layered DPB image,
 * bitstream and device-local NV12 staging buffers, and the
 * encode-feedback query pool. Constructor allocates everything up
 * front; {@link #close()} tears down in Vulkan teardown order (views
 * before images, buffers before their memory).
 *
 * Handles are package-visible fields because the encode recorder reads
 * them every frame. Boxing them behind getters would add noise without
 * hiding anything, since everything in this package is one logical unit.
 */
final class EncodeResources implements AutoCloseable
{
    private static final int BITSTREAM_FLOOR_BYTES = 4 * 1024 * 1024;
    /** Depth of the encode ring. Frame N+RING_DEPTH reuses slot N's resources. */
    static final int RING_DEPTH = 2;

    /** Per-slot bitstream buffer size, sized to comfortably hold one IDR at
     *  the session's resolution. Uncompressed NV12 is a generous upper bound
     *  for any single frame's H.264 NAL output. */
    final int bitstreamBufferSize;

    private final VulkanDevice vulkanDevice;
    private final VulkanCapabilities caps;
    private final Disposables disposables = new Disposables();

    final long[] encodeInputImage = new long[RING_DEPTH];
    final long[] encodeInputImageView = new long[RING_DEPTH];
    final long[] encodeInputMemory = new long[RING_DEPTH];

    long dpbImage = VK_NULL_HANDLE;
    /** Both entries alias the same layered 2D_ARRAY view; per-slot selection is done via baseArrayLayer. */
    final long[] dpbImageViews = new long[2];
    long dpbMemory = VK_NULL_HANDLE;

    final long[] bitstreamBuffer = new long[RING_DEPTH];
    final long[] bitstreamMemory = new long[RING_DEPTH];
    final long[] bitstreamMappedPtr = new long[RING_DEPTH];

    final long[] stagingBuffer = new long[RING_DEPTH];
    final long[] stagingMemory = new long[RING_DEPTH];

    /** Single pool with {@link #RING_DEPTH} queries, one per ring slot. */
    long feedbackQueryPool = VK_NULL_HANDLE;

    EncodeResources(VulkanDevice vulkanDevice, VulkanCapabilities caps,
                    int paddedWidth, int paddedHeight)
    {
        this.vulkanDevice = vulkanDevice;
        this.caps = caps;
        long nv12Bytes = (long) paddedWidth * paddedHeight * 3 / 2;
        this.bitstreamBufferSize = (int) Math.max(BITSTREAM_FLOOR_BYTES, nv12Bytes);
        try
        {
            for (int i = 0; i < RING_DEPTH; i++)
            {
                createEncodeInputImage(i, paddedWidth, paddedHeight);
                createBitstreamBuffer(i);
                createStagingBuffer(i, paddedWidth, paddedHeight);
            }
            createDpbImages(paddedWidth, paddedHeight);
            createFeedbackQueryPool();
        }
        catch (Throwable t)
        {
            disposables.close();
            throw t;
        }
    }

    @Override
    public void close()
    {
        disposables.close();
    }

    private void createEncodeInputImage(int slot, int width, int height)
    {
        VkDevice device = vulkanDevice.getDevice();

        try (MemoryStack stack = stackPush())
        {
            VkVideoProfileListInfoKHR profileList = VkVideoProfileListInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_PROFILE_LIST_INFO_KHR)
                .pProfiles(VkVideoProfileInfoKHR.calloc(1, stack).put(0, caps.buildVideoProfile(stack)));

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(profileList.address())
                .imageType(VK_IMAGE_TYPE_2D)
                .format(caps.getPictureFormat())
                .extent(e -> e.set(width, height, 1))
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_VIDEO_ENCODE_SRC_BIT_KHR | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            int result = vkCreateImage(device, imageInfo, null, pImage);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateImage (encode input slot " + slot + ") failed: " + result);
            }
            encodeInputImage[slot] = pImage.get(0);
            final int slotF = slot;

            encodeInputMemory[slot] = allocateAndBindImageMemory(device, encodeInputImage[slot],
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);
            // Register in Vulkan-teardown order (memory last, image, view top of stack)
            // so pop order is view -> image -> free-memory.
            disposables.add(() -> vkFreeMemory(device, encodeInputMemory[slotF], null));
            disposables.add(() -> vkDestroyImage(device, encodeInputImage[slotF], null));

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(encodeInputImage[slot])
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(caps.getPictureFormat())
                .subresourceRange(r -> r
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1));

            LongBuffer pView = stack.mallocLong(1);
            result = vkCreateImageView(device, viewInfo, null, pView);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateImageView (encode input slot " + slot + ") failed: " + result);
            }
            encodeInputImageView[slot] = pView.get(0);
            disposables.add(() -> vkDestroyImageView(device, encodeInputImageView[slotF], null));
        }
    }

    private void createDpbImages(int width, int height)
    {
        VkDevice device = vulkanDevice.getDevice();

        try (MemoryStack stack = stackPush())
        {
            VkVideoProfileListInfoKHR profileList = VkVideoProfileListInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_PROFILE_LIST_INFO_KHR)
                .pProfiles(VkVideoProfileInfoKHR.calloc(1, stack).put(0, caps.buildVideoProfile(stack)));

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(profileList.address())
                .imageType(VK_IMAGE_TYPE_2D)
                .format(caps.getPictureFormat())
                .extent(e -> e.set(width, height, 1))
                .mipLevels(1)
                .arrayLayers(2)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_VIDEO_ENCODE_DPB_BIT_KHR)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            int result = vkCreateImage(device, imageInfo, null, pImage);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateImage (DPB) failed: " + result);
            }
            dpbImage = pImage.get(0);

            dpbMemory = allocateAndBindImageMemory(device, dpbImage,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);
            disposables.add(() -> vkFreeMemory(device, dpbMemory, null));
            disposables.add(() -> vkDestroyImage(device, dpbImage, null));

            // A single layered 2D_ARRAY view covers both DPB slots; per-slot
            // selection happens via baseArrayLayer on the picture resource.
            // Separate per-slot views confuse VCN's cross-submission DPB
            // tracking and silently break inter-prediction.
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(dpbImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
                .format(caps.getPictureFormat())
                .subresourceRange(r -> r
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(2));

            LongBuffer pView = stack.mallocLong(1);
            result = vkCreateImageView(device, viewInfo, null, pView);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateImageView (DPB layered) failed: " + result);
            }
            dpbImageViews[0] = dpbImageViews[1] = pView.get(0);
            disposables.add(() -> vkDestroyImageView(device, dpbImageViews[0], null));
        }
    }

    private void createBitstreamBuffer(int slot)
    {
        VkDevice device = vulkanDevice.getDevice();
        final int slotF = slot;

        try (MemoryStack stack = stackPush())
        {
            VkVideoProfileListInfoKHR profileList = VkVideoProfileListInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_PROFILE_LIST_INFO_KHR)
                .pProfiles(VkVideoProfileInfoKHR.calloc(1, stack).put(0, caps.buildVideoProfile(stack)));

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .pNext(profileList.address())
                .size(bitstreamBufferSize)
                .usage(VK_BUFFER_USAGE_VIDEO_ENCODE_DST_BIT_KHR)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int result = vkCreateBuffer(device, bufferInfo, null, pBuffer);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateBuffer (bitstream slot " + slot + ") failed: " + result);
            }
            bitstreamBuffer[slot] = pBuffer.get(0);

            bitstreamMemory[slot] = allocateAndBindBufferMemory(device, bitstreamBuffer[slot],
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT, stack);
            disposables.add(() -> vkFreeMemory(device, bitstreamMemory[slotF], null));
            disposables.add(() -> vkDestroyBuffer(device, bitstreamBuffer[slotF], null));

            PointerBuffer pData = stack.mallocPointer(1);
            result = vkMapMemory(device, bitstreamMemory[slot], 0, bitstreamBufferSize, 0, pData);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkMapMemory (bitstream slot " + slot + ") failed: " + result);
            }
            bitstreamMappedPtr[slot] = pData.get(0);
            disposables.add(() -> vkUnmapMemory(device, bitstreamMemory[slotF]));
        }
    }

    private void createStagingBuffer(int slot, int width, int height)
    {
        VkDevice device = vulkanDevice.getDevice();
        final int slotF = slot;
        // NV12: Y plane (W*H bytes) + interleaved UV plane (W*H/2 bytes) = W*H*3/2.
        long bufferSize = (long) width * height * 3 / 2;

        try (MemoryStack stack = stackPush())
        {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(bufferSize)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int result = vkCreateBuffer(device, bufferInfo, null, pBuffer);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateBuffer (staging slot " + slot + ") failed: " + result);
            }
            stagingBuffer[slot] = pBuffer.get(0);

            stagingMemory[slot] = allocateAndBindBufferMemory(device, stagingBuffer[slot],
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);
            disposables.add(() -> vkFreeMemory(device, stagingMemory[slotF], null));
            disposables.add(() -> vkDestroyBuffer(device, stagingBuffer[slotF], null));
        }
    }

    /**
     * Profile chain on the pool matches the session's profile (VUID-07130 / VUID-07133).
     * Every encode wraps the commands with vkCmdBeginQuery/EndQuery; some VCN
     * driver paths need that wrapping to enable real rate-controlled emit mode.
     */
    private void createFeedbackQueryPool()
    {
        VkDevice device = vulkanDevice.getDevice();
        try (MemoryStack stack = stackPush())
        {
            VkVideoProfileInfoKHR profile = caps.buildVideoProfile(stack);

            VkQueryPoolVideoEncodeFeedbackCreateInfoKHR feedbackInfo =
                VkQueryPoolVideoEncodeFeedbackCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_QUERY_POOL_VIDEO_ENCODE_FEEDBACK_CREATE_INFO_KHR)
                    .pNext(profile.address())
                    .encodeFeedbackFlags(
                        VK_VIDEO_ENCODE_FEEDBACK_BITSTREAM_BUFFER_OFFSET_BIT_KHR
                            | VK_VIDEO_ENCODE_FEEDBACK_BITSTREAM_BYTES_WRITTEN_BIT_KHR);

            VkQueryPoolCreateInfo poolInfo = VkQueryPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                .pNext(feedbackInfo.address())
                .queryType(VK_QUERY_TYPE_VIDEO_ENCODE_FEEDBACK_KHR)
                .queryCount(RING_DEPTH);

            LongBuffer pPool = stack.mallocLong(1);
            int result = vkCreateQueryPool(device, poolInfo, null, pPool);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateQueryPool (encode feedback) failed: " + result);
            }
            feedbackQueryPool = pPool.get(0);
            disposables.add(() -> vkDestroyQueryPool(device, feedbackQueryPool, null));
        }
    }

    private long allocateAndBindImageMemory(VkDevice device, long image, int properties, MemoryStack stack)
    {
        VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
        vkGetImageMemoryRequirements(device, image, memReqs);

        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(memReqs.size())
            .memoryTypeIndex(findMemoryType(memReqs.memoryTypeBits(), properties, stack));

        LongBuffer pMemory = stack.mallocLong(1);
        int result = vkAllocateMemory(device, allocInfo, null, pMemory);
        if (result != VK_SUCCESS)
        {
            throw new RuntimeException("vkAllocateMemory failed: " + result);
        }
        long memory = pMemory.get(0);
        vkBindImageMemory(device, image, memory, 0);
        return memory;
    }

    private long allocateAndBindBufferMemory(VkDevice device, long buffer, int properties, MemoryStack stack)
    {
        VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(device, buffer, memReqs);

        int memTypeIdx;
        try
        {
            memTypeIdx = findMemoryType(memReqs.memoryTypeBits(), properties, stack);
        }
        catch (RuntimeException e)
        {
            memTypeIdx = findMemoryType(memReqs.memoryTypeBits(),
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, stack);
        }

        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(memReqs.size())
            .memoryTypeIndex(memTypeIdx);

        LongBuffer pMemory = stack.mallocLong(1);
        int result = vkAllocateMemory(device, allocInfo, null, pMemory);
        if (result != VK_SUCCESS)
        {
            throw new RuntimeException("vkAllocateMemory failed: " + result);
        }
        long memory = pMemory.get(0);
        vkBindBufferMemory(device, buffer, memory, 0);
        return memory;
    }

    private int findMemoryType(int typeFilter, int properties, MemoryStack stack)
    {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(vulkanDevice.getPhysicalDevice(), memProps);

        for (int i = 0; i < memProps.memoryTypeCount(); i++)
        {
            if ((typeFilter & (1 << i)) != 0 &&
                (memProps.memoryTypes(i).propertyFlags() & properties) == properties)
            {
                return i;
            }
        }

        for (int i = 0; i < memProps.memoryTypeCount(); i++)
        {
            if ((typeFilter & (1 << i)) != 0)
            {
                return i;
            }
        }

        throw new RuntimeException("Failed to find suitable memory type");
    }
}
