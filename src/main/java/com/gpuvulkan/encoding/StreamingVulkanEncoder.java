/*
 * Copyright (c) 2025, Dennis De Vulder
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
package com.gpuvulkan.encoding;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Per-frame H.264 encoder that stores Annex-B NALs in a ring, keeping the
 * encode session alive for the life of the recording.
 *
 * Resize handling: any source-size change tears down the session and opens a
 * new segment. Finalize then picks the latest segment overlapping the requested
 * window. Frames spanning a resize are dropped from the clip — the alternative
 * (multi-segment MP4) is poorly supported by common players.
 *
 * Thread-safety: public methods are {@code synchronized} on {@code this}.
 * Concurrent {@code submitFrameBgraBuffer} calls from the writer pool serialise.
 */
@Slf4j
public final class StreamingVulkanEncoder implements VideoEncoder, AutoCloseable
{
    private static final long FENCE_TIMEOUT_NS = 5_000_000_000L;

    private final VulkanDevice vulkanDevice;
    private final VulkanCapabilities caps;

    private NalRing nalRing = new NalRing();
    private final List<EncodeSegment> segments = new ArrayList<>();
    private final Deque<PendingMeta> inFlight = new ArrayDeque<>();

    private final Disposables persistent = new Disposables();
    private long commandPool = VK_NULL_HANDLE;
    private long gfxCommandPool = VK_NULL_HANDLE;
    private final VkCommandBuffer[] commandBuffers = new VkCommandBuffer[EncodeResources.RING_DEPTH];
    private final VkCommandBuffer[] gfxCommandBuffers = new VkCommandBuffer[EncodeResources.RING_DEPTH];
    private final long[] encodeFences = new long[EncodeResources.RING_DEPTH];
    private final long[] uploadSemaphores = new long[EncodeResources.RING_DEPTH];

    private H264SessionConfig sessionConfig;
    private EncodeResources resources;
    private FrameEncoder frameEncoder;
    private EncodeSegment currentSegment;

    private int sourceFps = -1;
    private int idrPeriod = -1;
    private long averageBitrateBps = FrameEncoder.DEFAULT_AVERAGE_BITRATE_BPS;
    private long peakBitrateBps = FrameEncoder.DEFAULT_PEAK_BITRATE_BPS;
    private int nextFrameIndex;
    private int nextSegmentId;
    private long callCount;
    private boolean vulkanInitialized;
    private boolean closed;

    public StreamingVulkanEncoder(VulkanDevice vulkanDevice, VulkanCapabilities caps)
    {
        this.vulkanDevice = vulkanDevice;
        this.caps = caps;
    }

    public synchronized void setMaxBufferedFrames(int maxFrames)
    {
        int clamped = Math.max(1, Math.min(3600, maxFrames));
        if (nalRing.capacity() == clamped)
        {
            return;
        }
        drainPending();
        inFlight.clear();
        nalRing = new NalRing(clamped);
    }

    /** 5-second IDR cadence: balances trim-to-keyframe granularity against
     *  bitrate spent on keyframes. With ~10s of pre-roll buffered, the worst-
     *  case "first frame after the chosen IDR" latency is one IDR period. */
    private static final int IDR_PERIOD_SECONDS = 5;

    @Override
    public synchronized void start(int fps)
    {
        if (fps <= 0) throw new IllegalArgumentException("fps must be positive, got " + fps);
        this.sourceFps = fps;
        this.idrPeriod = fps * IDR_PERIOD_SECONDS;
        this.nextFrameIndex = 0;
        this.callCount = 0;
        nalRing.reset();
        segments.clear();
        inFlight.clear();
    }

    @Override
    public synchronized void configureBitrate(int averageBps, int peakBps)
    {
        if (averageBps <= 0 || peakBps < averageBps)
        {
            log.warn("ignoring invalid bitrate config: avg={} peak={}", averageBps, peakBps);
            return;
        }
        this.averageBitrateBps = averageBps;
        this.peakBitrateBps = peakBps;
    }

    /**
     * Tears down per-recording state but keeps the device, command pools, and
     * sync primitives alive so the next {@link #start} can reuse them. Use
     * {@link #close} for terminal teardown.
     */
    @Override
    public synchronized void stop()
    {
        drainPending();
        nalRing.reset();
        segments.clear();
        inFlight.clear();
        destroySession();
        callCount = 0;
        nextFrameIndex = 0;
    }

    public synchronized void submitFrameBgraBuffer(long bgraBuffer, int width, int height,
                                                   long timestamp, boolean needsBlur)
    {
        if (sourceFps <= 0 || bgraBuffer == VK_NULL_HANDLE || width <= 0 || height <= 0) return;

        String stage = "segment-setup";
        try
        {
            if (currentSegment == null)
            {
                initSegment(width, height, timestamp);
            }
            else if (width != currentSegment.sourceWidth || height != currentSegment.sourceHeight)
			{
				rotateSegment(width, height, timestamp);
			}

            stage = "encode";
            int frameIndex = nextFrameIndex++;
            boolean isIdr = (frameIndex % idrPeriod) == 0;
            PendingMeta meta = new PendingMeta(currentSegment.id, timestamp, isIdr, frameIndex, needsBlur);

            byte[] prev = frameEncoder.encodeFrameBgraBuffer(bgraBuffer, width, height, isIdr, frameIndex);

            stage = "ring-put";
            if (callCount >= EncodeResources.RING_DEPTH)
            {
                PendingMeta drained = inFlight.poll();
                if (drained != null && prev != null)
                {
                    nalRing.put(toSlot(drained, prev));
                }
            }
            inFlight.add(meta);
            callCount++;
        }
        catch (Exception e)
        {
            log.error("streaming encode failed during {} at ts={}", stage, timestamp, e);
        }
    }

    private void rotateSegment(int width, int height, long timestamp)
    {
        log.debug("streaming encoder: rotate segment {}x{} -> {}x{}",
            currentSegment.sourceWidth, currentSegment.sourceHeight, width, height);
        drainPending();
        destroySession();
        callCount = 0;
        nextFrameIndex = 0;
        initSegment(width, height, timestamp);
    }

    @Override
    public synchronized ClipData finalizeClip(long startTime, long endTime)
    {
        drainPending();

        List<NalRing.Slot> all = nalRing.snapshot();
        if (all.isEmpty()) return null;

        int targetSegment = pickLatestSegment(all, startTime, endTime);
        if (targetSegment < 0) return null;

        int idrIdx = findIdrIndex(all, targetSegment, startTime, endTime);
        if (idrIdx < 0)
        {
            log.warn("streaming finalize: no IDR in [{}, {}] for segment {}", startTime, endTime, targetSegment);
            return null;
        }

        List<NalRing.Slot> chosen = collectFromIdr(all, idrIdx, targetSegment, endTime);
        if (chosen.isEmpty()) return null;

        EncodeSegment seg = findSegment(targetSegment);
        if (seg == null) return null;

        return assembleMp4(chosen, seg);
    }

    /**
     * Picks the latest {@code segmentId} that has at least one frame whose
     * timestamp falls inside {@code [startTime, endTime]}. Returns {@code -1}
     * if no segment is in window.
     */
    static int pickLatestSegment(List<NalRing.Slot> slots, long startTime, long endTime)
    {
        int target = -1;
        for (NalRing.Slot s : slots)
        {
            if (s.timestampMs >= startTime && s.timestampMs <= endTime && s.segmentId > target)
            {
                target = s.segmentId;
            }
        }
        return target;
    }

    /**
     * Finds the index of the IDR to start the clip from. Prefers the latest
     * IDR at or before {@code startTime} (so the clip captures the requested
     * pre-roll); falls back to the first IDR after {@code startTime} if none
     * preceded it. Returns {@code -1} if the segment has no IDR in the
     * window.
     */
    static int findIdrIndex(List<NalRing.Slot> slots, int segmentId, long startTime, long endTime)
    {
        int idrIdx = -1;
        for (int i = 0; i < slots.size(); i++)
        {
            NalRing.Slot s = slots.get(i);
            if (s.segmentId != segmentId || !s.isIdr) continue;
            if (s.timestampMs > endTime) break;
            if (s.timestampMs <= startTime)
            {
                idrIdx = i;
            }
            else if (idrIdx < 0)
            {
                idrIdx = i;
                break;
            }
            else
            {
                break;
            }
        }
        return idrIdx;
    }

    /**
     * Collects slots starting at {@code startIdx} until either the segment
     * changes or a slot's timestamp exceeds {@code endTime}.
     */
    static List<NalRing.Slot> collectFromIdr(List<NalRing.Slot> slots, int startIdx,
                                             int segmentId, long endTime)
    {
        List<NalRing.Slot> chosen = new ArrayList<>();
        for (int i = startIdx; i < slots.size(); i++)
        {
            NalRing.Slot s = slots.get(i);
            if (s.segmentId != segmentId || s.timestampMs > endTime) break;
            chosen.add(s);
        }
        return chosen;
    }

    private ClipData assembleMp4(List<NalRing.Slot> chosen, EncodeSegment seg)
    {
        ByteArrayOutputStream bs = new ByteArrayOutputStream(chosen.size() * 50000);
        long[] timestamps = new long[chosen.size()];
        for (int i = 0; i < chosen.size(); i++)
        {
            NalRing.Slot s = chosen.get(i);
            try
            {
                bs.write(s.nalUnits);
            }
            catch (IOException e)
            {
                log.error("failed to assemble bitstream", e);
                return null;
            }
            timestamps[i] = s.timestampMs;
        }
        byte[] mp4 = LocalMp4Writer.toBytes(bs.toByteArray(), seg.spsPps,
            seg.sourceWidth, seg.sourceHeight, seg.fps, timestamps);
        return new ClipData(Collections.singletonList(mp4), "video/mp4", mp4.length);
    }

    @Override
    public synchronized void reset()
    {
        drainPending();
        nalRing.reset();
        inFlight.clear();
        callCount = 0;
        nextFrameIndex = 0;
    }

    @Override
    public String encoderName()
    {
        return "vulkan-h264-streaming";
    }

    @Override
    public synchronized void close()
    {
        if (closed) return;
        closed = true;
        drainPending();
        nalRing.reset();
        segments.clear();
        inFlight.clear();
        destroySession();
        destroyPersistent();
        vulkanDevice.close();
    }

    private void initSegment(int sourceWidth, int sourceHeight, long firstTimestamp)
    {
        int codedWidth = (sourceWidth + 15) & ~15;
        int codedHeight = (sourceHeight + 15) & ~15;

        if (!vulkanInitialized)
        {
            createCommandPool();
            createSyncPrimitives();
            vulkanInitialized = true;
        }

        try
        {
            sessionConfig = new H264SessionConfig(vulkanDevice, caps, codedWidth, codedHeight, sourceFps);
            sessionConfig.initialize();

            resources = new EncodeResources(vulkanDevice, caps, codedWidth, codedHeight);
            allocateCommandBuffers();

            frameEncoder = new FrameEncoder(
                vulkanDevice, caps, sessionConfig, resources,
                commandBuffers, gfxCommandBuffers,
                encodeFences, uploadSemaphores,
                codedWidth, codedHeight, sourceFps, idrPeriod,
                averageBitrateBps, peakBitrateBps);

            byte[] spsPps = sessionConfig.fetchEncodedSpsPps(0, 0, true, true);
            currentSegment = new EncodeSegment(nextSegmentId++,
                sourceWidth, sourceHeight, codedWidth, codedHeight, sourceFps,
                spsPps, firstTimestamp);
            segments.add(currentSegment);
        }
        catch (RuntimeException e)
        {
            // Roll back any partial init so the next frame retries from a
            // clean state instead of leaking the half-built session.
            destroySession();
            throw e;
        }
    }

    private void drainPending()
    {
        if (frameEncoder == null) return;
        List<byte[]> tails = frameEncoder.drainRemaining();
        for (byte[] nal : tails)
        {
            PendingMeta m = inFlight.poll();
            if (m != null && nal != null)
            {
                nalRing.put(toSlot(m, nal));
            }
        }
        inFlight.clear();
    }

    private NalRing.Slot toSlot(PendingMeta m, byte[] nal)
    {
        return new NalRing.Slot(m.segmentId, m.timestampMs, m.isIdr, m.frameNum, m.needsBlur, nal);
    }

    private EncodeSegment findSegment(int id)
    {
        for (EncodeSegment s : segments)
        {
            if (s.id == id) return s;
        }
        return null;
    }

    private void createCommandPool()
    {
        VkDevice device = vulkanDevice.getDevice();
        try (MemoryStack stack = stackPush())
        {
            VkCommandPoolCreateInfo encPool = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT
                    | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(vulkanDevice.getVideoEncodeQueueFamily());
            LongBuffer pPool = stack.mallocLong(1);
            int r = vkCreateCommandPool(device, encPool, null, pPool);
            if (r != VK_SUCCESS) throw new RuntimeException("encode vkCreateCommandPool failed: " + r);
            commandPool = pPool.get(0);
            persistent.add(() -> vkDestroyCommandPool(device, commandPool, null));

            VkCommandPoolCreateInfo gfxPool = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT
                    | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(vulkanDevice.getGraphicsQueueFamily());
            r = vkCreateCommandPool(device, gfxPool, null, pPool);
            if (r != VK_SUCCESS) throw new RuntimeException("graphics vkCreateCommandPool failed: " + r);
            gfxCommandPool = pPool.get(0);
            persistent.add(() -> vkDestroyCommandPool(device, gfxCommandPool, null));
        }
    }

    private void allocateCommandBuffers()
    {
        VkDevice device = vulkanDevice.getDevice();
        try (MemoryStack stack = stackPush())
        {
            VkCommandBufferAllocateInfo encAlloc = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(EncodeResources.RING_DEPTH);
            PointerBuffer pCmdBuf = stack.mallocPointer(EncodeResources.RING_DEPTH);
            int r = vkAllocateCommandBuffers(device, encAlloc, pCmdBuf);
            if (r != VK_SUCCESS) throw new RuntimeException("encode vkAllocateCommandBuffers failed: " + r);
            for (int i = 0; i < EncodeResources.RING_DEPTH; i++)
            {
                commandBuffers[i] = new VkCommandBuffer(pCmdBuf.get(i), device);
            }

            VkCommandBufferAllocateInfo gfxAlloc = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(gfxCommandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(EncodeResources.RING_DEPTH);
            pCmdBuf.clear();
            r = vkAllocateCommandBuffers(device, gfxAlloc, pCmdBuf);
            if (r != VK_SUCCESS) throw new RuntimeException("graphics vkAllocateCommandBuffers failed: " + r);
            for (int i = 0; i < EncodeResources.RING_DEPTH; i++)
            {
                gfxCommandBuffers[i] = new VkCommandBuffer(pCmdBuf.get(i), device);
            }
        }
    }

    private void createSyncPrimitives()
    {
        VkDevice device = vulkanDevice.getDevice();
        try (MemoryStack stack = stackPush())
        {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < EncodeResources.RING_DEPTH; i++)
            {
                int r = vkCreateFence(device, fenceInfo, null, p);
                if (r != VK_SUCCESS) throw new RuntimeException("vkCreateFence slot " + i + " failed: " + r);
                encodeFences[i] = p.get(0);
                final int slot = i;
                persistent.add(() -> vkDestroyFence(device, encodeFences[slot], null));

                r = vkCreateSemaphore(device, semInfo, null, p);
                if (r != VK_SUCCESS) throw new RuntimeException("vkCreateSemaphore slot " + i + " failed: " + r);
                uploadSemaphores[i] = p.get(0);
                persistent.add(() -> vkDestroySemaphore(device, uploadSemaphores[slot], null));
            }
        }
    }

    private void destroySession()
    {
        VkDevice device = vulkanDevice.getDevice();
        for (long fence : encodeFences)
        {
            if (fence != VK_NULL_HANDLE)
            {
                vkWaitForFences(device, fence, true, FENCE_TIMEOUT_NS);
            }
        }
        if (frameEncoder != null)
        {
            frameEncoder.close();
            frameEncoder = null;
        }
        if (resources != null)
        {
            resources.close();
            resources = null;
        }
        if (sessionConfig != null)
        {
            sessionConfig.close();
            sessionConfig = null;
        }
        currentSegment = null;
    }

    private void destroyPersistent()
    {
        persistent.close();
        for (int i = 0; i < EncodeResources.RING_DEPTH; i++)
        {
            encodeFences[i] = VK_NULL_HANDLE;
            uploadSemaphores[i] = VK_NULL_HANDLE;
        }
        commandPool = VK_NULL_HANDLE;
        gfxCommandPool = VK_NULL_HANDLE;
        vulkanInitialized = false;
    }

    private static final class PendingMeta
    {
        final int segmentId;
        final long timestampMs;
        final boolean isIdr;
        final int frameNum;
        final boolean needsBlur;

        PendingMeta(int segmentId, long timestampMs, boolean isIdr, int frameNum, boolean needsBlur)
        {
            this.segmentId = segmentId;
            this.timestampMs = timestampMs;
            this.isIdr = isIdr;
            this.frameNum = frameNum;
            this.needsBlur = needsBlur;
        }
    }
}
