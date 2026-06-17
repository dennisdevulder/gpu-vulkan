/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved. See VulkanEncoder.java for full license text.
 */
package com.gpuvulkan.encoding;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.lwjgl.vulkan.video.StdVideoEncodeH264PictureInfo;
import org.lwjgl.vulkan.video.StdVideoEncodeH264PictureInfoFlags;
import org.lwjgl.vulkan.video.StdVideoEncodeH264ReferenceInfo;
import org.lwjgl.vulkan.video.StdVideoEncodeH264ReferenceListsInfo;
import org.lwjgl.vulkan.video.StdVideoEncodeH264SliceHeader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.vulkan.KHRVideoEncodeH264.*;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.*;
import static org.lwjgl.vulkan.KHRVideoQueue.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.video.STDVulkanVideoCodecH264.*;

/**
 * Ring-pipelined H.264 frame encoder. BGRA->NV12 conversion, input upload, and
 * encode are all recorded on device queues via a {@link EncodeResources#RING_DEPTH}
 * slot ring. {@link #encodeFrameBgraBuffer} submits into slot
 * {@code frameIndex % RING_DEPTH} and returns the slot's previous tenant, or
 * {@code null} until the ring fills. Caller must {@link #drainRemaining} after
 * the last submit.
 *
 * DPB slot rotation (mod 2) is independent of ring slot rotation; the index
 * collision is coincidence. DPB tracks reconstructed pictures for inter-
 * prediction, the ring tracks per-frame scratch.
 *
 * One instance is valid for a single padded (w,h,fps). Dimension changes
 * rebuild this and {@link EncodeResources}.
 *
 * Not thread-safe. {@link #paddedPixels}, {@link #pendingFrame}, and the
 * Vulkan command pools (which command buffers borrow) all assume serial
 * access. Callers must externally synchronise concurrent invocations.
 */
@Slf4j
final class FrameEncoder
{
    private static final long FENCE_TIMEOUT_NS = 5_000_000_000L;

    // Default bitrates if the caller doesn't configure them. Peak 1.5x avg;
    // with max==avg, VBR degenerates to CBR.
    static final long DEFAULT_AVERAGE_BITRATE_BPS = 10_000_000L;
    static final long DEFAULT_PEAK_BITRATE_BPS    = 15_000_000L;

    private static final int  VBR_VIRTUAL_BUFFER_MS         = 2000;
    private static final int  VBR_INITIAL_VIRTUAL_BUFFER_MS = 1000;

    private final VulkanDevice vulkanDevice;
    private final VulkanCapabilities caps;
    private final H264SessionConfig sessionConfig;
    private final EncodeResources resources;
    private final VkCommandBuffer[] commandBuffers;
    private final VkCommandBuffer[] gfxCommandBuffers;
    private final long[] encodeFences;
    private final long[] uploadSemaphores;
    private final int encodedWidth;
    private final int encodedHeight;
    private final int fps;
    private final int idrPeriod;
    private final long averageBitrateBps;
    private final long peakBitrateBps;

    /** frameIndex in-flight per slot, or -1 if the slot is free. */
    private final int[] pendingFrame = new int[EncodeResources.RING_DEPTH];
    /** Set when the slot's last submit was an orphan drain (no encode cb).
     *  Tells drainSlot to skip the bitstream query; its result is stale. */
    private final boolean[] slotFailed = new boolean[EncodeResources.RING_DEPTH];
    private long convertDescriptorSetLayout = VK_NULL_HANDLE;
    private long convertDescriptorPool = VK_NULL_HANDLE;
    private final long[] convertDescriptorSets = new long[EncodeResources.RING_DEPTH];
    private long convertPipelineLayout = VK_NULL_HANDLE;
    private long convertPipeline = VK_NULL_HANDLE;
    private long convertShaderModule = VK_NULL_HANDLE;

    FrameEncoder(VulkanDevice vulkanDevice, VulkanCapabilities caps,
                 H264SessionConfig sessionConfig, EncodeResources resources,
                 VkCommandBuffer[] commandBuffers, VkCommandBuffer[] gfxCommandBuffers,
                 long[] encodeFences, long[] uploadSemaphores,
                 int encodedWidth, int encodedHeight, int fps, int idrPeriod,
                 long averageBitrateBps, long peakBitrateBps)
    {
        if (idrPeriod <= 0) throw new IllegalArgumentException("idrPeriod must be positive");
        if (averageBitrateBps <= 0 || peakBitrateBps < averageBitrateBps)
        {
            throw new IllegalArgumentException(
                "invalid bitrate: avg=" + averageBitrateBps + " peak=" + peakBitrateBps);
        }
        this.vulkanDevice = vulkanDevice;
        this.caps = caps;
        this.sessionConfig = sessionConfig;
        this.resources = resources;
        this.commandBuffers = commandBuffers;
        this.gfxCommandBuffers = gfxCommandBuffers;
        this.encodeFences = encodeFences;
        this.uploadSemaphores = uploadSemaphores;
        this.encodedWidth = encodedWidth;
        this.encodedHeight = encodedHeight;
        this.fps = fps;
        this.idrPeriod = idrPeriod;
        this.averageBitrateBps = averageBitrateBps;
        this.peakBitrateBps = peakBitrateBps;
        Arrays.fill(pendingFrame, -1);
        createConvertPipeline();
    }

    /**
     * Submits frame {@code frameIndex} into its ring slot; returns the
     * previously-occupying frame's bitstream, or {@code null} for the first
     * {@link EncodeResources#RING_DEPTH} calls. Call {@link #drainRemaining}
     * after the last frame to collect the tail.
     */
    /**
     * Streaming-encode entry point: the source is a GPU-owned tightly packed
     * BGRA buffer produced from the composited frame. Conversion to NV12,
     * upload to the encode input image, and H.264 encode all remain on-device.
     */
    byte[] encodeFrameBgraBuffer(long bgraBuffer, int width, int height,
                                 boolean isIdr, int frameIndex)
    {
        int ringSlot = frameIndex % EncodeResources.RING_DEPTH;
        byte[] prev = drainIfPending(ringSlot);

        int yBytes = encodedWidth * encodedHeight;
        return submitBgraBuffer(bgraBuffer, width, height, isIdr, frameIndex, ringSlot, yBytes, prev);
    }

    private byte[] drainIfPending(int ringSlot)
    {
        return pendingFrame[ringSlot] != -1 ? drainSlot(ringSlot) : null;
    }

    private byte[] submitBgraBuffer(long bgraBuffer, int width, int height, boolean isIdr,
                                    int frameIndex, int ringSlot, int yBytes, byte[] prev)
    {
        VkDevice device = vulkanDevice.getDevice();
        try (MemoryStack stack = stackPush())
        {
            if (!resetSlotSync(device, ringSlot)) return prev;
            if (!recordAndSubmitUpload(stack, bgraBuffer, width, height, yBytes, ringSlot, frameIndex)) return prev;

            int dpbSlot = frameIndex % 2;
            int refSlot = (frameIndex + 1) % 2;
            if (!recordAndSubmitEncode(stack, isIdr, dpbSlot, refSlot, frameIndex, ringSlot))
            {
                orphanDrainSlot(stack, ringSlot, frameIndex);
                return prev;
            }
            pendingFrame[ringSlot] = frameIndex;
        }
        return prev;
    }

    /**
     * Consumes a stranded {@code uploadSemaphores[slot]} signal when encode
     * submit fails after the graphics upload is already queued. Without this,
     * the next reuse would signal-an-already-signaled semaphore (spec UB).
     */
    private void orphanDrainSlot(MemoryStack stack, int ringSlot, int frameIndex)
    {
        log.warn("encode submit failed for frame {}, draining slot {}", frameIndex, ringSlot);
        VkSemaphoreSubmitInfo.Buffer waitInfo = VkSemaphoreSubmitInfo.calloc(1, stack)
            .sType$Default()
            .semaphore(uploadSemaphores[ringSlot])
            .stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
        VkSubmitInfo2.Buffer drain = VkSubmitInfo2.calloc(1, stack)
            .sType$Default()
            .pWaitSemaphoreInfos(waitInfo);
        int r = vkQueueSubmit2(vulkanDevice.getVideoEncodeQueue(), drain, encodeFences[ringSlot]);
        if (r != VK_SUCCESS)
        {
            // Likely device-lost. Clear state; burstEncode's catch falls back to MJPEG.
            log.error("orphan drain submit slot {} failed: {}; slot left empty", ringSlot, r);
            slotFailed[ringSlot] = false;
            pendingFrame[ringSlot] = -1;
            return;
        }
        slotFailed[ringSlot] = true;
        pendingFrame[ringSlot] = frameIndex;
    }

    /**
     * Waits all in-flight slots and returns their bitstreams in submission
     * order. Failed drains appear as {@code null} entries so callers that
     * pair NALs with per-frame metadata (streaming encode) can keep alignment.
     */
    List<byte[]> drainRemaining()
    {
        int n = 0;
        int[] slots = new int[EncodeResources.RING_DEPTH];
        for (int s = 0; s < EncodeResources.RING_DEPTH; s++)
        {
            if (pendingFrame[s] != -1) slots[n++] = s;
        }
        for (int i = 1; i < n; i++)
        {
            int x = slots[i];
            int j = i;
            while (j > 0 && pendingFrame[slots[j - 1]] > pendingFrame[x])
            {
                slots[j] = slots[j - 1];
                j--;
            }
            slots[j] = x;
        }
        List<byte[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
        {
            out.add(drainSlot(slots[i]));
        }
        return out;
    }

    private byte[] drainSlot(int ringSlot)
    {
        VkDevice device = vulkanDevice.getDevice();
        int r = vkWaitForFences(device, encodeFences[ringSlot], true, FENCE_TIMEOUT_NS);
        if (r != VK_SUCCESS)
        {
            log.error("vkWaitForFences slot {} failed: {}", ringSlot, r);
            pendingFrame[ringSlot] = -1;
            slotFailed[ringSlot] = false;
            return null;
        }
        pendingFrame[ringSlot] = -1;
        if (slotFailed[ringSlot])
        {
            slotFailed[ringSlot] = false;
            return null;
        }
        try (MemoryStack stack = stackPush())
        {
            return readBitstream(stack, device, ringSlot);
        }
    }

    private boolean resetSlotSync(VkDevice device, int ringSlot)
    {
        int r;
        if ((r = vkResetFences(device, encodeFences[ringSlot])) != VK_SUCCESS)
        {
            log.error("vkResetFences slot {} failed: {}", ringSlot, r);
            return false;
        }
        if ((r = vkResetCommandBuffer(commandBuffers[ringSlot], 0)) != VK_SUCCESS)
        {
            log.error("vkResetCommandBuffer(encode) slot {} failed: {}", ringSlot, r);
            return false;
        }
        if ((r = vkResetCommandBuffer(gfxCommandBuffers[ringSlot], 0)) != VK_SUCCESS)
        {
            log.error("vkResetCommandBuffer(gfx) slot {} failed: {}", ringSlot, r);
            return false;
        }
        return true;
    }

    private boolean recordAndSubmitUpload(MemoryStack stack, long bgraBuffer, int width, int height,
                                          int yBytes, int ringSlot, int frameIndex)
    {
        int encFamily = vulkanDevice.getVideoEncodeQueueFamily();
        int gfxFamily = vulkanDevice.getGraphicsQueueFamily();
        VkCommandBuffer gfxCb = gfxCommandBuffers[ringSlot];

        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        int r;
        if ((r = vkBeginCommandBuffer(gfxCb, beginInfo)) != VK_SUCCESS)
        {
            log.error("vkBeginCommandBuffer(gfx) frame {} slot {} failed: {}", frameIndex, ringSlot, r);
            return false;
        }

        recordBgraToNv12(stack, gfxCb, bgraBuffer, width, height, ringSlot);

        // UNDEFINED -> TRANSFER_DST: previous contents discarded on re-entry.
        VulkanBarriers.image(stack, gfxCb, resources.encodeInputImage[ringSlot],
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            0, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);

        // Y plane: offset 0, aspect PLANE_0. UV plane: offset yBytes, PLANE_1.
        // VUID-vkCmdCopyBufferToImage-dstImage-07981 requires one plane per region.
        VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(2, stack);
        regions.get(0)
            .bufferOffset(0)
            .bufferRowLength(0)
            .bufferImageHeight(0)
            .imageSubresource(s -> s
                .aspectMask(VK_IMAGE_ASPECT_PLANE_0_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1))
            .imageOffset(o -> o.set(0, 0, 0))
            .imageExtent(e -> e.set(encodedWidth, encodedHeight, 1));
        regions.get(1)
            .bufferOffset(yBytes)
            .bufferRowLength(0)
            .bufferImageHeight(0)
            .imageSubresource(s -> s
                .aspectMask(VK_IMAGE_ASPECT_PLANE_1_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1))
            .imageOffset(o -> o.set(0, 0, 0))
            .imageExtent(e -> e.set(encodedWidth / 2, encodedHeight / 2, 1));

        vkCmdCopyBufferToImage(gfxCb, resources.stagingBuffer[ringSlot], resources.encodeInputImage[ringSlot],
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);

        // QFOT release: graphics to encode family. Dst stages/access = 0 (spec).
        VulkanBarriers.image(stack, gfxCb, resources.encodeInputImage[ringSlot],
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_VIDEO_ENCODE_SRC_KHR,
            VK_ACCESS_TRANSFER_WRITE_BIT, 0,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0,
            gfxFamily, encFamily);

        if ((r = vkEndCommandBuffer(gfxCb)) != VK_SUCCESS)
        {
            log.error("vkEndCommandBuffer(gfx) frame {} slot {} failed: {}", frameIndex, ringSlot, r);
            return false;
        }

        VkCommandBufferSubmitInfo.Buffer cbInfo = VkCommandBufferSubmitInfo.calloc(1, stack)
            .sType$Default()
            .commandBuffer(gfxCb);
        VkSemaphoreSubmitInfo.Buffer signalInfo = VkSemaphoreSubmitInfo.calloc(1, stack)
            .sType$Default()
            .semaphore(uploadSemaphores[ringSlot])
            .stageMask(VK_PIPELINE_STAGE_2_COPY_BIT);
        VkSubmitInfo2.Buffer gfxSubmit = VkSubmitInfo2.calloc(1, stack)
            .sType$Default()
            .pCommandBufferInfos(cbInfo)
            .pSignalSemaphoreInfos(signalInfo);
        if ((r = vkQueueSubmit2(vulkanDevice.getGraphicsQueue(), gfxSubmit, VK_NULL_HANDLE)) != VK_SUCCESS)
        {
            log.error("graphics vkQueueSubmit2 frame {} slot {} failed: {}", frameIndex, ringSlot, r);
            return false;
        }
        return true;
    }

    private void recordBgraToNv12(MemoryStack stack, VkCommandBuffer cb, long bgraBuffer,
                                  int width, int height, int ringSlot)
    {
        updateConvertDescriptors(stack, bgraBuffer, ringSlot);

        VkBufferMemoryBarrier2.Buffer before = VkBufferMemoryBarrier2.calloc(1, stack)
            .sType$Default()
            .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
            .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
            .dstAccessMask(VK_ACCESS_2_SHADER_STORAGE_READ_BIT)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .buffer(bgraBuffer)
            .offset(0)
            .size(VK_WHOLE_SIZE);
        VkDependencyInfo depBefore = VkDependencyInfo.calloc(stack)
            .sType$Default()
            .pBufferMemoryBarriers(before);
        vkCmdPipelineBarrier2(cb, depBefore);

        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, convertPipeline);
        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, convertPipelineLayout,
            0, stack.longs(convertDescriptorSets[ringSlot]), null);
        ByteBuffer push = stack.malloc(16).order(ByteOrder.nativeOrder());
        push.putInt(width)
            .putInt(height)
            .putInt(encodedWidth)
            .putInt(encodedHeight)
            .flip();
        vkCmdPushConstants(cb, convertPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        vkCmdDispatch(cb, (encodedWidth + 31) / 32, (encodedHeight + 15) / 16, 1);

        VkBufferMemoryBarrier2.Buffer after = VkBufferMemoryBarrier2.calloc(1, stack)
            .sType$Default()
            .srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
            .srcAccessMask(VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
            .dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .buffer(resources.stagingBuffer[ringSlot])
            .offset(0)
            .size(VK_WHOLE_SIZE);
        VkDependencyInfo depAfter = VkDependencyInfo.calloc(stack)
            .sType$Default()
            .pBufferMemoryBarriers(after);
        vkCmdPipelineBarrier2(cb, depAfter);
    }

    private void updateConvertDescriptors(MemoryStack stack, long bgraBuffer, int ringSlot)
    {
        VkDescriptorBufferInfo.Buffer src = VkDescriptorBufferInfo.calloc(1, stack);
        src.get(0)
            .buffer(bgraBuffer)
            .offset(0)
            .range(VK_WHOLE_SIZE);
        VkDescriptorBufferInfo.Buffer dst = VkDescriptorBufferInfo.calloc(1, stack);
        dst.get(0)
            .buffer(resources.stagingBuffer[ringSlot])
            .offset(0)
            .range(VK_WHOLE_SIZE);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
        writes.get(0)
            .sType$Default()
            .dstSet(convertDescriptorSets[ringSlot])
            .dstBinding(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1)
            .pBufferInfo(src);
        writes.get(1)
            .sType$Default()
            .dstSet(convertDescriptorSets[ringSlot])
            .dstBinding(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1)
            .pBufferInfo(dst);
        vkUpdateDescriptorSets(vulkanDevice.getDevice(), writes, null);
    }

    private void createConvertPipeline()
    {
        VkDevice device = vulkanDevice.getDevice();
        try (MemoryStack stack = stackPush())
        {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0)
                .binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1)
                .binding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
            LongBuffer p = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(device, layoutInfo, null, p);
            if (r != VK_SUCCESS) throw new RuntimeException("vkCreateDescriptorSetLayout (bgra->nv12) failed: " + r);
            convertDescriptorSetLayout = p.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0)
                .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(EncodeResources.RING_DEPTH * 2);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default()
                .maxSets(EncodeResources.RING_DEPTH)
                .pPoolSizes(poolSizes);
            r = vkCreateDescriptorPool(device, poolInfo, null, p);
            if (r != VK_SUCCESS) throw new RuntimeException("vkCreateDescriptorPool (bgra->nv12) failed: " + r);
            convertDescriptorPool = p.get(0);

            LongBuffer layouts = stack.mallocLong(EncodeResources.RING_DEPTH);
            for (int i = 0; i < EncodeResources.RING_DEPTH; i++)
            {
                layouts.put(i, convertDescriptorSetLayout);
            }
            VkDescriptorSetAllocateInfo alloc = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default()
                .descriptorPool(convertDescriptorPool)
                .pSetLayouts(layouts);
            LongBuffer sets = stack.mallocLong(EncodeResources.RING_DEPTH);
            r = vkAllocateDescriptorSets(device, alloc, sets);
            if (r != VK_SUCCESS) throw new RuntimeException("vkAllocateDescriptorSets (bgra->nv12) failed: " + r);
            for (int i = 0; i < EncodeResources.RING_DEPTH; i++)
            {
                convertDescriptorSets[i] = sets.get(i);
            }

            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                .offset(0)
                .size(16);
            VkPipelineLayoutCreateInfo pipeLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(convertDescriptorSetLayout))
                .pPushConstantRanges(push);
            r = vkCreatePipelineLayout(device, pipeLayoutInfo, null, p);
            if (r != VK_SUCCESS) throw new RuntimeException("vkCreatePipelineLayout (bgra->nv12) failed: " + r);
            convertPipelineLayout = p.get(0);

            ByteBuffer spirv = loadResource("bgra_to_nv12.comp.spv");
            try
            {
                convertShaderModule = createShaderModule(device, stack, spirv);
            }
            finally
            {
                MemoryUtil.memFree(spirv);
            }
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType$Default()
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(convertShaderModule)
                .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipeInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipeInfo.get(0)
                .sType$Default()
                .stage(stage)
                .layout(convertPipelineLayout);
            r = vkCreateComputePipelines(device, VK_NULL_HANDLE, pipeInfo, null, p);
            if (r != VK_SUCCESS) throw new RuntimeException("vkCreateComputePipelines (bgra->nv12) failed: " + r);
            convertPipeline = p.get(0);
        }
    }

    private static long createShaderModule(VkDevice device, MemoryStack stack, ByteBuffer spirv)
    {
        VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
            .sType$Default()
            .pCode(spirv);
        LongBuffer p = stack.mallocLong(1);
        int r = vkCreateShaderModule(device, info, null, p);
        if (r != VK_SUCCESS) throw new RuntimeException("vkCreateShaderModule (bgra->nv12) failed: " + r);
        return p.get(0);
    }

    private static ByteBuffer loadResource(String name)
    {
        try (InputStream in = FrameEncoder.class.getResourceAsStream(name))
        {
            if (in == null)
            {
                throw new IllegalStateException("Missing encoder shader resource " + name);
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer out = MemoryUtil.memAlloc(bytes.length);
            out.put(bytes).flip();
            return out;
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to load encoder shader " + name, e);
        }
    }

    void close()
    {
        VkDevice device = vulkanDevice.getDevice();
        if (convertPipeline != VK_NULL_HANDLE)
        {
            vkDestroyPipeline(device, convertPipeline, null);
            convertPipeline = VK_NULL_HANDLE;
        }
        if (convertShaderModule != VK_NULL_HANDLE)
        {
            vkDestroyShaderModule(device, convertShaderModule, null);
            convertShaderModule = VK_NULL_HANDLE;
        }
        if (convertPipelineLayout != VK_NULL_HANDLE)
        {
            vkDestroyPipelineLayout(device, convertPipelineLayout, null);
            convertPipelineLayout = VK_NULL_HANDLE;
        }
        if (convertDescriptorPool != VK_NULL_HANDLE)
        {
            vkDestroyDescriptorPool(device, convertDescriptorPool, null);
            convertDescriptorPool = VK_NULL_HANDLE;
        }
        if (convertDescriptorSetLayout != VK_NULL_HANDLE)
        {
            vkDestroyDescriptorSetLayout(device, convertDescriptorSetLayout, null);
            convertDescriptorSetLayout = VK_NULL_HANDLE;
        }
    }

    private boolean recordAndSubmitEncode(MemoryStack stack, boolean isIdr,
                                          int dpbSlot, int refSlot, int frameIndex, int ringSlot)
    {
        int encFamily = vulkanDevice.getVideoEncodeQueueFamily();
        int gfxFamily = vulkanDevice.getGraphicsQueueFamily();
        VkCommandBuffer cb = commandBuffers[ringSlot];

        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        int r;
        if ((r = vkBeginCommandBuffer(cb, beginInfo)) != VK_SUCCESS)
        {
            log.error("vkBeginCommandBuffer(encode) frame {} slot {} failed: {}", frameIndex, ringSlot, r);
            return false;
        }

        // QFOT acquire: matches the release issued on the graphics queue.
        VulkanBarriers.image(stack, cb, resources.encodeInputImage[ringSlot],
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_VIDEO_ENCODE_SRC_KHR,
            0, 0,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
            0,
            VK_ACCESS_2_VIDEO_ENCODE_READ_BIT_KHR,
            VK_PIPELINE_STAGE_2_VIDEO_ENCODE_BIT_KHR,
            gfxFamily, encFamily);

        // Rate-control must be explicitly configured on first frame; the
        // firmware's default (NONE) ignores our constantQp and bitrate target.
        if (frameIndex == 0)
        {
            recordRateControlScope(stack, cb);
        }

        // vkCmdResetQueryPool must happen OUTSIDE a video coding scope.
        vkCmdResetQueryPool(cb, resources.feedbackQueryPool, ringSlot, 1);

        recordEncodeCommand(stack, cb, isIdr, dpbSlot, refSlot, frameIndex, ringSlot);

        VulkanBarriers.buffer(stack, cb, resources.bitstreamBuffer[ringSlot], resources.bitstreamBufferSize,
            VK_ACCESS_2_VIDEO_ENCODE_WRITE_BIT_KHR, VK_ACCESS_HOST_READ_BIT,
            VK_PIPELINE_STAGE_2_VIDEO_ENCODE_BIT_KHR, VK_PIPELINE_STAGE_HOST_BIT);

        if ((r = vkEndCommandBuffer(cb)) != VK_SUCCESS)
        {
            log.error("vkEndCommandBuffer(encode) frame {} slot {} failed: {}", frameIndex, ringSlot, r);
            return false;
        }

        VkSemaphoreSubmitInfo.Buffer waitInfo = VkSemaphoreSubmitInfo.calloc(1, stack)
            .sType$Default()
            .semaphore(uploadSemaphores[ringSlot])
            .stageMask(VK_PIPELINE_STAGE_2_VIDEO_ENCODE_BIT_KHR);
        VkCommandBufferSubmitInfo.Buffer cbSubmit = VkCommandBufferSubmitInfo.calloc(1, stack)
            .sType$Default()
            .commandBuffer(cb);
        VkSubmitInfo2.Buffer encSubmit = VkSubmitInfo2.calloc(1, stack)
            .sType$Default()
            .pWaitSemaphoreInfos(waitInfo)
            .pCommandBufferInfos(cbSubmit);
        if ((r = vkQueueSubmit2(vulkanDevice.getVideoEncodeQueue(), encSubmit, encodeFences[ringSlot])) != VK_SUCCESS)
        {
            log.error("encode vkQueueSubmit2 frame {} slot {} failed: {}", frameIndex, ringSlot, r);
            return false;
        }
        return true;
    }

    private byte[] readBitstream(MemoryStack stack, VkDevice device, int ringSlot)
    {
        // Feedback result layout (flags ordered by bit value per spec):
        //   [0] = BITSTREAM_BUFFER_OFFSET (where the driver wrote, usually 0)
        //   [1] = BITSTREAM_BYTES_WRITTEN
        //   [2] = VkQueryResultStatusKHR (appended by WITH_STATUS_BIT)
        LongBuffer results = stack.mallocLong(3);
        int r = vkGetQueryPoolResults(device, resources.feedbackQueryPool, ringSlot, 1,
            results, 3 * Long.BYTES,
            VK_QUERY_RESULT_WAIT_BIT
                | VK_QUERY_RESULT_WITH_STATUS_BIT_KHR
                | VK_QUERY_RESULT_64_BIT);
        if (r != VK_SUCCESS)
        {
            log.error("vkGetQueryPoolResults(encode feedback) slot {} failed: {}", ringSlot, r);
            return null;
        }
        long status = results.get(2);
        if (status <= 0)
        {
            // 0 = not ready (shouldn't happen under WAIT_BIT), <0 = driver-reported failure.
            log.error("encode feedback slot {} status {}: bitstream undefined", ringSlot, status);
            return null;
        }

        long offset = results.get(0);
        long bytesWritten = results.get(1);
        if (bytesWritten <= 0 || offset < 0
            || offset + bytesWritten > resources.bitstreamBufferSize)
        {
            log.error("encode feedback slot {} out of range: offset={} bytes={}",
                ringSlot, offset, bytesWritten);
            return null;
        }

        VkMappedMemoryRange range = VkMappedMemoryRange.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE)
            .memory(resources.bitstreamMemory[ringSlot])
            .offset(0)
            .size(VK_WHOLE_SIZE);
        r = vkInvalidateMappedMemoryRanges(device, range);
        if (r != VK_SUCCESS)
        {
            log.error("vkInvalidateMappedMemoryRanges slot {} failed: {}", ringSlot, r);
            return null;
        }

        ByteBuffer bitstream = memByteBuffer(resources.bitstreamMappedPtr[ringSlot], resources.bitstreamBufferSize);
        byte[] naluData = new byte[(int) bytesWritten];
        bitstream.position((int) offset);
        bitstream.get(naluData, 0, (int) bytesWritten);
        return naluData;
    }

    private void recordEncodeCommand(MemoryStack stack, VkCommandBuffer cb,
                                     boolean isIdr, int dpbSlot, int refSlot,
                                     int frameIndex, int ringSlot)
    {
        if (frameIndex == 0)
        {
            transitionDpbForFirstFrame(stack, cb);
        }

        boolean hasReference = !isIdr;

        VkVideoPictureResourceInfoKHR srcPicture = VkVideoPictureResourceInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_PICTURE_RESOURCE_INFO_KHR)
            .codedOffset(o -> o.set(0, 0))
            .codedExtent(e -> e.set(encodedWidth, encodedHeight))
            .baseArrayLayer(0)
            .imageViewBinding(resources.encodeInputImageView[ringSlot]);

        VkVideoReferenceSlotInfoKHR setupSlot = buildSetupReferenceSlot(
            stack, isIdr, dpbSlot, frameIndex);

        VkVideoEncodeH264PictureInfoKHR picInfo = buildPictureInfo(
            stack, isIdr, hasReference, refSlot, frameIndex);

        VkVideoEncodeRateControlInfoKHR rcInfo = buildRateControlChain(stack);

        // GopRemainingFrameInfo hints the firmware about upcoming I/P counts;
        // required in practice on VCN even though caps reports it as optional.
        int frameInGop = frameIndex % idrPeriod;
        int remainingInGop = idrPeriod - frameInGop;
        int remainingI = isIdr ? 1 : 0;
        int remainingP = isIdr ? (remainingInGop - 1) : remainingInGop;
        VkVideoEncodeH264GopRemainingFrameInfoKHR gopRemaining =
            VkVideoEncodeH264GopRemainingFrameInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_GOP_REMAINING_FRAME_INFO_KHR)
                .pNext(rcInfo.address())
                .useGopRemainingFrames(true)
                .gopRemainingI(remainingI)
                .gopRemainingP(remainingP)
                .gopRemainingB(0);

        VkVideoBeginCodingInfoKHR beginCoding = VkVideoBeginCodingInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_BEGIN_CODING_INFO_KHR)
            .pNext(gopRemaining.address())
            .videoSession(sessionConfig.getVideoSession())
            .videoSessionParameters(sessionConfig.getSessionParameters())
            .pReferenceSlots(buildBeginCodingSlots(stack, hasReference, dpbSlot, refSlot, frameIndex, isIdr));

        vkCmdBeginVideoCodingKHR(cb, beginCoding);

        VkVideoEncodeInfoKHR encodeInfo = VkVideoEncodeInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_INFO_KHR)
            .pNext(picInfo.address())
            .dstBuffer(resources.bitstreamBuffer[ringSlot])
            .dstBufferOffset(0)
            .dstBufferRange(resources.bitstreamBufferSize)
            .srcPictureResource(srcPicture)
            .pSetupReferenceSlot(setupSlot)
            .pReferenceSlots(hasReference
                ? buildEncodeReferenceSlots(stack, refSlot, frameIndex)
                : null);

        vkCmdBeginQuery(cb, resources.feedbackQueryPool, ringSlot, 0);
        vkCmdEncodeVideoKHR(cb, encodeInfo);
        vkCmdEndQuery(cb, resources.feedbackQueryPool, ringSlot);

        VkVideoEndCodingInfoKHR endCoding = VkVideoEndCodingInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_END_CODING_INFO_KHR);
        vkCmdEndVideoCodingKHR(cb, endCoding);
    }

    /** frame_num within the current GOP, modulo MaxFrameNum (16; SPS
     *  log2_max_frame_num_minus4 = 0). Resets to 0 at each IDR. */
    private int frameNumInGop(int frameIndex)
    {
        return (frameIndex % idrPeriod) & 0xF;
    }

    /** POC LSB, modulo MaxPicOrderCntLsb (256). Must wrap independently
     *  of frame_num; sharing the mod-16 wrap causes mid-GOP display
     *  re-ordering glitches in the decoder. */
    private int pocInGop(int frameIndex)
    {
        return ((frameIndex % idrPeriod) * 2) & 0xFF;
    }

    /**
     * Transitions the DPB image into {@code VIDEO_ENCODE_DPB_KHR} on the
     * encode queue. Explicit src=dst=encodeFamily is required; IGNORED
     * leaves the image without stable ownership and subsequent DPB reads
     * see garbage on AMD.
     */
    private void transitionDpbForFirstFrame(MemoryStack stack, VkCommandBuffer cb)
    {
        int encFamily = vulkanDevice.getVideoEncodeQueueFamily();
        VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
            .srcStageMask(VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT)
            .srcAccessMask(0)
            .dstStageMask(VK_PIPELINE_STAGE_2_VIDEO_ENCODE_BIT_KHR)
            .dstAccessMask(VK_ACCESS_2_VIDEO_ENCODE_READ_BIT_KHR | VK_ACCESS_2_VIDEO_ENCODE_WRITE_BIT_KHR)
            .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            .newLayout(VK_IMAGE_LAYOUT_VIDEO_ENCODE_DPB_KHR)
            .srcQueueFamilyIndex(encFamily)
            .dstQueueFamilyIndex(encFamily)
            .image(resources.dpbImage)
            .subresourceRange(r -> r
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(2));

        VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
            .pImageMemoryBarriers(barrier);
        vkCmdPipelineBarrier2(cb, depInfo);
    }

    /**
     * The setup slot describes the picture being reconstructed into the
     * DPB for this frame. VUID-VkVideoEncodeH264DpbSlotInfoKHR-
     * pStdReferenceInfo-parameter requires the std reference info to be
     * non-null.
     */
    private VkVideoReferenceSlotInfoKHR buildSetupReferenceSlot(
        MemoryStack stack, boolean codeAsIdr, int dpbSlot, int frameIndex)
    {
        StdVideoEncodeH264ReferenceInfo stdRefInfo = StdVideoEncodeH264ReferenceInfo.calloc(stack)
            .primary_pic_type(codeAsIdr ? STD_VIDEO_H264_PICTURE_TYPE_IDR : STD_VIDEO_H264_PICTURE_TYPE_P)
            .FrameNum(codeAsIdr ? 0 : frameNumInGop(frameIndex))
            .PicOrderCnt(pocInGop(frameIndex));

        VkVideoEncodeH264DpbSlotInfoKHR h264DpbSlot = VkVideoEncodeH264DpbSlotInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_DPB_SLOT_INFO_KHR)
            .pStdReferenceInfo(stdRefInfo);

        // Single layered view over the DPB image; baseArrayLayer selects slot.
        VkVideoPictureResourceInfoKHR setupPicResource = VkVideoPictureResourceInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_PICTURE_RESOURCE_INFO_KHR)
            .codedOffset(o -> o.set(0, 0))
            .codedExtent(e -> e.set(encodedWidth, encodedHeight))
            .baseArrayLayer(dpbSlot)
            .imageViewBinding(resources.dpbImageViews[dpbSlot]);

        return VkVideoReferenceSlotInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_REFERENCE_SLOT_INFO_KHR)
            .pNext(h264DpbSlot.address())
            .slotIndex(dpbSlot)
            .pPictureResource(setupPicResource);
    }

    private VkVideoEncodeH264PictureInfoKHR buildPictureInfo(
        MemoryStack stack, boolean codeAsIdr, boolean hasReference,
        int refSlot, int frameIndex)
    {
        int picType = codeAsIdr ? STD_VIDEO_H264_PICTURE_TYPE_IDR : STD_VIDEO_H264_PICTURE_TYPE_P;
        int sliceType = codeAsIdr ? STD_VIDEO_H264_SLICE_TYPE_I : STD_VIDEO_H264_SLICE_TYPE_P;

        // pRefLists is required on every frame (IDRs included) so the DPB's
        // reconstructed picture is tracked as a reference. Both counters are 0:
        // 0xFF in num_ref_idx_lN_active_minus1 is read by some drivers as
        // "no refs at all" and disables inter-prediction.
        StdVideoEncodeH264ReferenceListsInfo refLists = StdVideoEncodeH264ReferenceListsInfo.calloc(stack);
        for (int i = 0; i < 32; i++)
        {
            refLists.RefPicList0(i, (byte) 0xFF);
            refLists.RefPicList1(i, (byte) 0xFF);
        }
        if (hasReference)
        {
            refLists.RefPicList0(0, (byte) refSlot);
        }

        StdVideoEncodeH264PictureInfoFlags picFlags = StdVideoEncodeH264PictureInfoFlags.calloc(stack)
            .IdrPicFlag(codeAsIdr)
            .is_reference(true);

        StdVideoEncodeH264SliceHeader sliceHeader = StdVideoEncodeH264SliceHeader.calloc(stack)
            .slice_type(sliceType);

        // VUID-08269: constantQp must be 0 when rate control is enabled.
        // 18/20 under DISABLED targets x264 CRF-18 visual quality.
        int constantQp = useVbr() ? 0 : (codeAsIdr ? 18 : 20);
        VkVideoEncodeH264NaluSliceInfoKHR.Buffer naluSlice = VkVideoEncodeH264NaluSliceInfoKHR.calloc(1, stack);
        naluSlice.get(0)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_NALU_SLICE_INFO_KHR)
            .constantQp(constantQp)
            .pStdSliceHeader(sliceHeader);

        StdVideoEncodeH264PictureInfo stdPicInfo = StdVideoEncodeH264PictureInfo.calloc(stack)
            .flags(picFlags)
            .idr_pic_id((short) frameIndex)
            .primary_pic_type(picType)
            .frame_num(codeAsIdr ? 0 : frameNumInGop(frameIndex))
            .PicOrderCnt(pocInGop(frameIndex))
            .pRefLists(refLists);

        return VkVideoEncodeH264PictureInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_PICTURE_INFO_KHR)
            .pNaluSliceEntries(naluSlice)
            .pStdPictureInfo(stdPicInfo)
            .generatePrefixNalu(false);
    }

    /**
     * Builds the reference slot array for BeginCoding. Every slot needs
     * its {@link org.lwjgl.vulkan.VkVideoEncodeH264DpbSlotInfoKHR} chained
     * in pNext so the firmware knows the codec-level identity of what lives
     * there; missing this makes the encoder silently drop to intra-only P
     * frames. Order: existing references first, then the setup slot last
     * with {@code slotIndex = -1}.
     */
    private VkVideoReferenceSlotInfoKHR.Buffer buildBeginCodingSlots(
        MemoryStack stack, boolean hasReference, int dpbSlot, int refSlot,
        int frameIndex, boolean codeAsIdr)
    {
        int setupFrameNum = codeAsIdr ? 0 : frameNumInGop(frameIndex);
        int setupPoc = pocInGop(frameIndex);
        int setupPicType = codeAsIdr
            ? STD_VIDEO_H264_PICTURE_TYPE_IDR
            : STD_VIDEO_H264_PICTURE_TYPE_P;
        StdVideoEncodeH264ReferenceInfo setupRefInfo = StdVideoEncodeH264ReferenceInfo.calloc(stack)
            .primary_pic_type(setupPicType)
            .FrameNum(setupFrameNum)
            .PicOrderCnt(setupPoc);
        VkVideoEncodeH264DpbSlotInfoKHR setupDpbSlotInfo = VkVideoEncodeH264DpbSlotInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_DPB_SLOT_INFO_KHR)
            .pStdReferenceInfo(setupRefInfo);

        VkVideoPictureResourceInfoKHR setupSlotPic = VkVideoPictureResourceInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_PICTURE_RESOURCE_INFO_KHR)
            .codedOffset(o -> o.set(0, 0))
            .codedExtent(e -> e.set(encodedWidth, encodedHeight))
            .baseArrayLayer(dpbSlot)
            .imageViewBinding(resources.dpbImageViews[dpbSlot]);

        if (!hasReference)
        {
            VkVideoReferenceSlotInfoKHR.Buffer slots = VkVideoReferenceSlotInfoKHR.calloc(1, stack);
            slots.get(0)
                .sType(VK_STRUCTURE_TYPE_VIDEO_REFERENCE_SLOT_INFO_KHR)
                .pNext(setupDpbSlotInfo.address())
                .slotIndex(-1)
                .pPictureResource(setupSlotPic);
            return slots;
        }

        // Reference slot: the previous frame. Its IDR status = whether the
        // prior frame was at the GOP boundary.
        boolean refIsIdr = (frameIndex - 1) % idrPeriod == 0;
        int refFrameNum = refIsIdr ? 0 : frameNumInGop(frameIndex - 1);
        int refPoc = pocInGop(frameIndex - 1);
        int refPicType = refIsIdr
            ? STD_VIDEO_H264_PICTURE_TYPE_IDR
            : STD_VIDEO_H264_PICTURE_TYPE_P;
        StdVideoEncodeH264ReferenceInfo refInfo = StdVideoEncodeH264ReferenceInfo.calloc(stack)
            .primary_pic_type(refPicType)
            .FrameNum(refFrameNum)
            .PicOrderCnt(refPoc);
        VkVideoEncodeH264DpbSlotInfoKHR refDpbSlotInfo = VkVideoEncodeH264DpbSlotInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_DPB_SLOT_INFO_KHR)
            .pStdReferenceInfo(refInfo);

        VkVideoPictureResourceInfoKHR refSlotPic = VkVideoPictureResourceInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_PICTURE_RESOURCE_INFO_KHR)
            .codedOffset(o -> o.set(0, 0))
            .codedExtent(e -> e.set(encodedWidth, encodedHeight))
            .baseArrayLayer(refSlot)
            .imageViewBinding(resources.dpbImageViews[refSlot]);

        VkVideoReferenceSlotInfoKHR.Buffer slots = VkVideoReferenceSlotInfoKHR.calloc(2, stack);
        slots.get(0)
            .sType(VK_STRUCTURE_TYPE_VIDEO_REFERENCE_SLOT_INFO_KHR)
            .pNext(refDpbSlotInfo.address())
            .slotIndex(refSlot)
            .pPictureResource(refSlotPic);
        slots.get(1)
            .sType(VK_STRUCTURE_TYPE_VIDEO_REFERENCE_SLOT_INFO_KHR)
            .pNext(setupDpbSlotInfo.address())
            .slotIndex(-1)
            .pPictureResource(setupSlotPic);
        return slots;
    }

    /**
     * Records a dedicated coding scope containing only the initial
     * {@code RESET | RATE_CONTROL | QUALITY_LEVEL} command. Issued as its own
     * scope (no encode command follows) so BeginCoding sees the session in
     * its DEFAULT rate-control state; chaining an rc_info there would violate
     * VUID-08254. The control command promotes the session to VBR before the
     * first encode scope opens.
     */
    private void recordRateControlScope(MemoryStack stack, VkCommandBuffer cb)
    {
        VkVideoEncodeRateControlInfoKHR rcInfo = buildRateControlChain(stack);

        VkVideoEncodeQualityLevelInfoKHR qlInfo = VkVideoEncodeQualityLevelInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_QUALITY_LEVEL_INFO_KHR)
            .pNext(rcInfo.address())
            .qualityLevel(2);

        VkVideoBeginCodingInfoKHR begin = VkVideoBeginCodingInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_BEGIN_CODING_INFO_KHR)
            .videoSession(sessionConfig.getVideoSession())
            .videoSessionParameters(sessionConfig.getSessionParameters());
        vkCmdBeginVideoCodingKHR(cb, begin);

        VkVideoCodingControlInfoKHR ctrl = VkVideoCodingControlInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_CODING_CONTROL_INFO_KHR)
            .pNext(qlInfo.address())
            .flags(VK_VIDEO_CODING_CONTROL_RESET_BIT_KHR
                 | VK_VIDEO_CODING_CONTROL_ENCODE_QUALITY_LEVEL_BIT_KHR
                 | VK_VIDEO_CODING_CONTROL_ENCODE_RATE_CONTROL_BIT_KHR);
        vkCmdControlVideoCodingKHR(cb, ctrl);

        VkVideoEndCodingInfoKHR end = VkVideoEndCodingInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_END_CODING_INFO_KHR);
        vkCmdEndVideoCodingKHR(cb, end);
    }

    /** True iff the driver advertised VBR support. CBR is another option but
     *  VBR adapts better to the mixed-complexity content this plugin captures
     *  (static UI + occasional motion bursts). */
    private boolean useVbr()
    {
        return (caps.getSupportedRateControlModes() & VK_VIDEO_ENCODE_RATE_CONTROL_MODE_VBR_BIT_KHR) != 0;
    }

    /**
     * Builds the rate-control pNext chain and returns the outer
     * {@link VkVideoEncodeRateControlInfoKHR}. Falls back to DISABLED/CQP
     * when the driver doesn't advertise VBR.
     *
     * Chain shape:
     *   VkVideoEncodeRateControlInfoKHR
     *     pLayers -> VkVideoEncodeRateControlLayerInfoKHR
     *                  pNext -> VkVideoEncodeH264RateControlLayerInfoKHR
     *     pNext   -> VkVideoEncodeH264RateControlInfoKHR
     */
    private VkVideoEncodeRateControlInfoKHR buildRateControlChain(MemoryStack stack)
    {
        if (!useVbr())
        {
            return VkVideoEncodeRateControlInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_RATE_CONTROL_INFO_KHR)
                .rateControlMode(VK_VIDEO_ENCODE_RATE_CONTROL_MODE_DISABLED_BIT_KHR);
        }

        VkVideoEncodeH264RateControlLayerInfoKHR h264LayerInfo =
            VkVideoEncodeH264RateControlLayerInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_RATE_CONTROL_LAYER_INFO_KHR);

        VkVideoEncodeRateControlLayerInfoKHR.Buffer encodeLayer =
            VkVideoEncodeRateControlLayerInfoKHR.calloc(1, stack);
        encodeLayer.get(0)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_RATE_CONTROL_LAYER_INFO_KHR)
            .pNext(h264LayerInfo.address())
            .averageBitrate(averageBitrateBps)
            .maxBitrate(peakBitrateBps)
            .frameRateNumerator(fps)
            .frameRateDenominator(1);

        // IPPP GOP, no B-frames.
        VkVideoEncodeH264RateControlInfoKHR h264RcInfo =
            VkVideoEncodeH264RateControlInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_RATE_CONTROL_INFO_KHR)
                .flags(VK_VIDEO_ENCODE_H264_RATE_CONTROL_REGULAR_GOP_BIT_KHR
                    | VK_VIDEO_ENCODE_H264_RATE_CONTROL_REFERENCE_PATTERN_FLAT_BIT_KHR)
                .gopFrameCount(idrPeriod)
                .idrPeriod(idrPeriod)
                .consecutiveBFrameCount(0)
                .temporalLayerCount(1);

        return VkVideoEncodeRateControlInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_RATE_CONTROL_INFO_KHR)
            .pNext(h264RcInfo.address())
            .rateControlMode(VK_VIDEO_ENCODE_RATE_CONTROL_MODE_VBR_BIT_KHR)
            .pLayers(encodeLayer)
            .virtualBufferSizeInMs(VBR_VIRTUAL_BUFFER_MS)
            .initialVirtualBufferSizeInMs(VBR_INITIAL_VIRTUAL_BUFFER_MS);
    }

    /**
     * Builds {@code VkVideoEncodeInfoKHR.pReferenceSlots} for a P-frame.
     * Each ref slot's std info describes the picture *already in* that
     * slot, not the one being encoded.
     */
    private VkVideoReferenceSlotInfoKHR.Buffer buildEncodeReferenceSlots(
        MemoryStack stack, int refSlot, int frameIndex)
    {
        boolean refIsIdr = (frameIndex - 1) % idrPeriod == 0;
        int refFrameNum = refIsIdr ? 0 : frameNumInGop(frameIndex - 1);
        int refPoc = pocInGop(frameIndex - 1);
        int refPicType = refIsIdr
            ? STD_VIDEO_H264_PICTURE_TYPE_IDR
            : STD_VIDEO_H264_PICTURE_TYPE_P;
        StdVideoEncodeH264ReferenceInfo prevRefInfo = StdVideoEncodeH264ReferenceInfo.calloc(stack)
            .primary_pic_type(refPicType)
            .FrameNum(refFrameNum)
            .PicOrderCnt(refPoc);
        VkVideoEncodeH264DpbSlotInfoKHR refDpbSlot = VkVideoEncodeH264DpbSlotInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_DPB_SLOT_INFO_KHR)
            .pStdReferenceInfo(prevRefInfo);

        VkVideoPictureResourceInfoKHR refPicResource = VkVideoPictureResourceInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_PICTURE_RESOURCE_INFO_KHR)
            .codedOffset(o -> o.set(0, 0))
            .codedExtent(e -> e.set(encodedWidth, encodedHeight))
            .baseArrayLayer(refSlot)
            .imageViewBinding(resources.dpbImageViews[refSlot]);

        return VkVideoReferenceSlotInfoKHR.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_REFERENCE_SLOT_INFO_KHR)
            .pNext(refDpbSlot.address())
            .slotIndex(refSlot)
            .pPictureResource(refPicResource);
    }

}
