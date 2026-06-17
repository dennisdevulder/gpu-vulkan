/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved. See VulkanEncoder.java for full license text.
 */
package com.gpuvulkan.encoding;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Synchronization2 barrier helpers. All methods are stateless; the caller
 * supplies the command buffer to record into.
 *
 * For a queue-family ownership transfer, the release barrier issued on the
 * source queue and the acquire barrier issued on the target queue must match
 * on layout and on {@code srcQueueFamilyIndex} / {@code dstQueueFamilyIndex}.
 */
final class VulkanBarriers
{
    private VulkanBarriers() {}

    /** Single-layer image barrier, records into {@code cmd}. */
    static void image(MemoryStack stack, VkCommandBuffer cmd, long image,
                      int oldLayout, int newLayout,
                      int srcAccess, int dstAccess,
                      int srcStage, int dstStage,
                      int arrayLayer,
                      int srcQueueFamily, int dstQueueFamily)
    {
        image(stack, cmd, image, oldLayout, newLayout,
            srcAccess, dstAccess, srcStage, dstStage, arrayLayer,
            0L, 0L, srcQueueFamily, dstQueueFamily);
    }

    /**
     * Overload for access/stage masks that only fit in 64-bit (e.g.
     * {@code VK_ACCESS_2_VIDEO_ENCODE_READ_BIT_KHR}). Pass {@code 0L}
     * to fall back to the 32-bit {@code dstAccess} / {@code dstStage}
     * arguments.
     */
    static void image(MemoryStack stack, VkCommandBuffer cmd, long image,
                      int oldLayout, int newLayout,
                      int srcAccess, int dstAccess,
                      int srcStage, int dstStage,
                      int arrayLayer,
                      long dstAccess64, long dstStage64,
                      int srcQueueFamily, int dstQueueFamily)
    {
        long finalDstAccess = dstAccess64 != 0 ? dstAccess64 : dstAccess;
        long finalDstStage  = dstStage64 != 0 ? dstStage64
            : (dstStage != 0 ? dstStage : VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
        long finalSrcStage  = srcStage != 0 ? srcStage : VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

        VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
            .srcStageMask(finalSrcStage)
            .srcAccessMask(srcAccess)
            .dstStageMask(finalDstStage)
            .dstAccessMask(finalDstAccess)
            .oldLayout(oldLayout)
            .newLayout(newLayout)
            .srcQueueFamilyIndex(srcQueueFamily)
            .dstQueueFamilyIndex(dstQueueFamily)
            .image(image)
            .subresourceRange(r -> r
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(arrayLayer)
                .layerCount(1));

        VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
            .pImageMemoryBarriers(barrier);

        vkCmdPipelineBarrier2(cmd, depInfo);
    }

    /** Full-range buffer barrier, records into {@code cmd}. */
    static void buffer(MemoryStack stack, VkCommandBuffer cmd, long buffer, long size,
                       long srcAccess, long dstAccess,
                       long srcStage, long dstStage)
    {
        VkBufferMemoryBarrier2.Buffer barrier = VkBufferMemoryBarrier2.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
            .srcStageMask(srcStage)
            .srcAccessMask(srcAccess)
            .dstStageMask(dstStage)
            .dstAccessMask(dstAccess)
            .buffer(buffer)
            .offset(0)
            .size(size);

        VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
            .pBufferMemoryBarriers(barrier);

        vkCmdPipelineBarrier2(cmd, depInfo);
    }
}
