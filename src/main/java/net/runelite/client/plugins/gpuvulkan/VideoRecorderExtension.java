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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Replay buffer on the GPU: grabs the composited frame in
 * {@link #recordAfterComposite}, converts it to NV12 with a compute pass on
 * the graphics queue, encodes it on the video encode queue, and keeps the
 * last {@code clipSeconds} of encoded frames in memory. {@link #requestClip()}
 * (wired to a hotkey by the plugin) writes the buffered frames out as an
 * Annex-B {@code .h264} clip — nothing touches disk until then.
 *
 * <p>Every frame is an IDR picture, so the ring can be cut at any frame
 * boundary at the cost of memory; the capture FPS cap keeps that bounded.
 *
 * <p>This extension exists to validate the backend's encode plumbing
 * (encode queue, frame timeline, post-composite hook); a production
 * recorder would live in its own plugin against the same surface.
 */
@Slf4j
final class VideoRecorderExtension implements VulkanRenderExtension
{
	private static final int SLOTS = 3;
	private static final int PUSH_SIZE = 20;
	/** Hard cap on buffered encoded bytes, on top of the time window. */
	private static final long MAX_BUFFER_BYTES = 256L << 20;

	private VulkanRenderContext context;
	private VulkanDevice device;
	private GpuVulkanPluginConfig config;

	private long shaderModule;
	private long descriptorSetLayout;
	private long pipelineLayout;
	private long pipeline;
	private long descriptorPool;

	private H264EncodeSession session;
	private Buffer[] rgbaBuffers = new Buffer[0];
	private Buffer[] lumaBuffers = new Buffer[0];
	private Buffer[] chromaBuffers = new Buffer[0];
	private long[] descriptorSets = new long[0];
	private final AtomicBoolean[] slotBusy = new AtomicBoolean[SLOTS];
	private int sessionWidth;
	private int sessionHeight;
	private long lastCaptureNanos;

	private final BlockingQueue<EncodeJob> jobs = new ArrayBlockingQueue<>(SLOTS);
	private Thread worker;
	private volatile boolean workerRunning;
	private boolean unavailableLogged;
	private boolean memoryCapLogged;

	/** Ring of encoded frames plus the SPS/PPS header that matches them.
	 *  Guarded by {@code ringLock}; touched by the encode worker, the clip
	 *  hotkey (AWT thread) and start/stop (client thread). */
	private final Object ringLock = new Object();
	private final ArrayDeque<EncodedFrame> ring = new ArrayDeque<>();
	private long ringBytes;
	private byte[] ringHeader;
	private int ringWidth;
	private int ringHeight;

	private static final class EncodedFrame
	{
		final byte[] data;
		final long captureNanos;

		EncodedFrame(byte[] data, long captureNanos)
		{
			this.data = data;
			this.captureNanos = captureNanos;
		}
	}

	private static final class EncodeJob
	{
		final int slot;
		final long timelineSemaphore;
		final long timelineValue;
		final long captureNanos;

		EncodeJob(int slot, long timelineSemaphore, long timelineValue, long captureNanos)
		{
			this.slot = slot;
			this.timelineSemaphore = timelineSemaphore;
			this.timelineValue = timelineValue;
			this.captureNanos = captureNanos;
		}
	}

	@Override
	public void onRegistered(VulkanRenderContext context)
	{
		this.context = context;
		this.config = context.config();
		this.device = ((DefaultVulkanRenderContext) context).device();
		for (int i = 0; i < SLOTS; i++)
		{
			slotBusy[i] = new AtomicBoolean(false);
		}
	}

	@Override
	public void recordAfterComposite(VulkanPostFrameContext frame)
	{
		if (!config.replayBuffer())
		{
			if (session != null)
			{
				stopBuffer();
			}
			return;
		}
		if (!context.encode().isAvailable() || frame.frameTimelineSemaphore() == 0L)
		{
			if (!unavailableLogged)
			{
				unavailableLogged = true;
				log.warn("Replay buffer unavailable: {}", context.encode().unavailableReason());
			}
			return;
		}
		if (session != null && (frame.width() != sessionWidth || frame.height() != sessionHeight))
		{
			// Canvas resized: buffered frames have the old SPS dimensions and
			// can't share a stream with new ones. Start over.
			log.info("Replay buffer reset by resize to {}x{}", frame.width(), frame.height());
			stopBuffer();
		}
		if (session == null)
		{
			try
			{
				startBuffer(frame.width(), frame.height());
			}
			catch (RuntimeException e)
			{
				log.warn("Failed to start replay buffer", e);
				unavailableLogged = true;
				return;
			}
		}

		long now = System.nanoTime();
		long minInterval = 1_000_000_000L / Math.max(1, config.replayFps());
		if (now - lastCaptureNanos < minInterval)
		{
			return;
		}

		int slot = acquireSlot();
		if (slot < 0)
		{
			return; // encode queue behind; drop the frame
		}
		recordConversion(frame, slot);
		if (jobs.offer(new EncodeJob(slot, frame.frameTimelineSemaphore(), frame.frameTimelineValue(), now)))
		{
			lastCaptureNanos = now;
		}
		else
		{
			slotBusy[slot].set(false);
		}
	}

	/**
	 * Saves the current ring as {@code clip-<timestamp>.h264}. Safe to call
	 * from any thread (the plugin calls it from the hotkey listener); the
	 * file write happens on a one-shot background thread.
	 */
	void requestClip()
	{
		byte[] header;
		List<EncodedFrame> frames;
		int width;
		int height;
		synchronized (ringLock)
		{
			if (ring.isEmpty() || ringHeader == null)
			{
				log.info("Clip requested but the replay buffer is empty");
				return;
			}
			header = ringHeader;
			frames = new ArrayList<>(ring);
			width = ringWidth;
			height = ringHeight;
		}
		Thread writer = new Thread(() -> writeClip(header, frames, width, height), "Vulkan-Clip-Writer");
		writer.setDaemon(true);
		writer.start();
	}

	private void writeClip(byte[] header, List<EncodedFrame> frames, int width, int height)
	{
		File dir = new File(net.runelite.client.RuneLite.RUNELITE_DIR, "vulkan-recordings");
		if (!dir.exists() && !dir.mkdirs())
		{
			log.warn("Cannot create {}", dir);
			return;
		}
		File file = new File(dir, "clip-" + System.currentTimeMillis() + ".mp4");
		List<Mp4Writer.Sample> samples = new ArrayList<>(frames.size());
		for (EncodedFrame frame : frames)
		{
			samples.add(new Mp4Writer.Sample(frame.data, frame.captureNanos));
		}
		try
		{
			Mp4Writer.write(file, header, samples, width, height);
		}
		catch (IOException e)
		{
			log.warn("Failed to write clip", e);
			return;
		}
		double seconds = frames.size() <= 1 ? 0
			: (frames.get(frames.size() - 1).captureNanos - frames.get(0).captureNanos) / 1e9;
		log.info("Clipped {} frames (~{}s, {} KiB) -> {}",
			frames.size(), String.format("%.1f", seconds), file.length() / 1024, file);
	}

	// ---- lifecycle ---------------------------------------------------------

	private void startBuffer(int width, int height)
	{
		if (pipeline == VK_NULL_HANDLE)
		{
			createComputeResources();
		}
		session = new H264EncodeSession(device, width, height, SLOTS, config.recordQp());
		sessionWidth = width;
		sessionHeight = height;
		lastCaptureNanos = 0;
		createConversionBuffers();
		synchronized (ringLock)
		{
			ring.clear();
			ringBytes = 0;
			ringHeader = session.parameterHeader();
			ringWidth = width;
			ringHeight = height;
		}

		workerRunning = true;
		worker = new Thread(this::encodeLoop, "Vulkan-Recorder");
		worker.setDaemon(true);
		worker.start();
		log.info("Replay buffer started: {}x{}, {}s window, {} fps cap",
			width, height, config.clipSeconds(), config.replayFps());
	}

	private void stopBuffer()
	{
		workerRunning = false;
		if (worker != null)
		{
			try
			{
				worker.join(10_000);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			worker = null;
		}
		jobs.clear();
		synchronized (ringLock)
		{
			ring.clear();
			ringBytes = 0;
			ringHeader = null;
		}
		if (session != null)
		{
			// In-flight graphics command buffers may still reference the
			// conversion buffers and NV12 images. Stop/resize is rare enough
			// that a full idle is acceptable.
			Vk.check("vkDeviceWaitIdle (recorder stop)", vkDeviceWaitIdle(device.handle()));
			destroyConversionBuffers();
			session.close();
			session = null;
		}
		for (AtomicBoolean busy : slotBusy)
		{
			busy.set(false);
		}
	}

	private void encodeLoop()
	{
		while (workerRunning || !jobs.isEmpty())
		{
			EncodeJob job;
			try
			{
				job = jobs.poll(100, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return;
			}
			if (job == null)
			{
				continue;
			}
			try
			{
				if (!waitTimeline(job))
				{
					continue; // frame never submitted; drop
				}
				byte[] encoded = session.encode(job.slot);
				appendToRing(new EncodedFrame(encoded, job.captureNanos));
			}
			catch (RuntimeException e)
			{
				log.warn("GPU encode failed; stopping replay buffer", e);
				workerRunning = false;
			}
			finally
			{
				slotBusy[job.slot].set(false);
			}
		}
	}

	private void appendToRing(EncodedFrame frame)
	{
		long windowNanos = config.clipSeconds() * 1_000_000_000L;
		synchronized (ringLock)
		{
			ring.addLast(frame);
			ringBytes += frame.data.length;
			while (!ring.isEmpty()
				&& frame.captureNanos - ring.peekFirst().captureNanos > windowNanos)
			{
				ringBytes -= ring.removeFirst().data.length;
			}
			while (ringBytes > MAX_BUFFER_BYTES && ring.size() > 1)
			{
				ringBytes -= ring.removeFirst().data.length;
				if (!memoryCapLogged)
				{
					memoryCapLogged = true;
					log.warn("Replay buffer hit the {} MiB memory cap before the {}s window; "
							+ "raise QP or lower capture FPS for the full window",
						MAX_BUFFER_BYTES >> 20, config.clipSeconds());
				}
			}
		}
	}

	private boolean waitTimeline(EncodeJob job)
	{
		try (MemoryStack stack = stackPush())
		{
			VkSemaphoreWaitInfo wait = VkSemaphoreWaitInfo.calloc(stack)
				.sType$Default()
				.semaphoreCount(1)
				.pSemaphores(stack.longs(job.timelineSemaphore))
				.pValues(stack.longs(job.timelineValue));
			return vkWaitSemaphores(device.handle(), wait, 2_000_000_000L) == VK_SUCCESS;
		}
	}

	// ---- per-frame conversion (graphics queue, client thread) --------------

	private int acquireSlot()
	{
		for (int i = 0; i < SLOTS; i++)
		{
			if (slotBusy[i].compareAndSet(false, true))
			{
				return i;
			}
		}
		return -1;
	}

	private void recordConversion(VulkanPostFrameContext frame, int slot)
	{
		VkCommandBuffer cmd = frame.commandBuffer();
		int width = frame.width();
		int height = frame.height();
		int paddedWidth = session.paddedWidth();
		int paddedHeight = session.paddedHeight();

		try (MemoryStack stack = stackPush())
		{
			imageBarrier(stack, cmd, frame.colorImage(),
				frame.imageLayout(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
				0, VK_ACCESS_TRANSFER_READ_BIT,
				VK_IMAGE_ASPECT_COLOR_BIT);

			VkBufferImageCopy.Buffer toBuffer = VkBufferImageCopy.calloc(1, stack);
			toBuffer.get(0)
				.imageExtent(e -> e.set(width, height, 1));
			toBuffer.get(0).imageSubresource()
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.layerCount(1);
			vkCmdCopyImageToBuffer(cmd, frame.colorImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				rgbaBuffers[slot].handle(), toBuffer);

			imageBarrier(stack, cmd, frame.colorImage(),
				VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, frame.imageLayout(),
				VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
				VK_ACCESS_TRANSFER_READ_BIT, 0,
				VK_IMAGE_ASPECT_COLOR_BIT);

			bufferBarrier(stack, cmd, rgbaBuffers[slot],
				VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
				VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
			vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
				0, stack.longs(descriptorSets[slot]), null);
			ByteBuffer push = stack.malloc(PUSH_SIZE);
			push.putInt(width).putInt(height).putInt(paddedWidth).putInt(paddedHeight)
				.putInt(isBgra(frame.imageFormat()) ? 1 : 0);
			push.flip();
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
			vkCmdDispatch(cmd, (paddedWidth / 4 + 7) / 8, (paddedHeight / 2 + 7) / 8, 1);

			bufferBarrier(stack, cmd, lumaBuffers[slot],
				VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
				VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
			bufferBarrier(stack, cmd, chromaBuffers[slot],
				VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
				VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

			// Discard previous contents; the encode thread transitions
			// TRANSFER_DST -> VIDEO_ENCODE_SRC on the encode queue. Barriers
			// on non-disjoint multi-planar images must use the COLOR aspect
			// (per-plane aspects are only for copies).
			imageBarrier(stack, cmd, session.srcImage(slot),
				VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
				0, VK_ACCESS_TRANSFER_WRITE_BIT,
				VK_IMAGE_ASPECT_COLOR_BIT);

			VkBufferImageCopy.Buffer lumaCopy = VkBufferImageCopy.calloc(1, stack);
			lumaCopy.get(0)
				.bufferRowLength(paddedWidth)
				.imageExtent(e -> e.set(paddedWidth, paddedHeight, 1));
			lumaCopy.get(0).imageSubresource()
				.aspectMask(VK_IMAGE_ASPECT_PLANE_0_BIT)
				.layerCount(1);
			vkCmdCopyBufferToImage(cmd, lumaBuffers[slot].handle(), session.srcImage(slot),
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, lumaCopy);

			VkBufferImageCopy.Buffer chromaCopy = VkBufferImageCopy.calloc(1, stack);
			chromaCopy.get(0)
				.bufferRowLength(paddedWidth / 2)
				.imageExtent(e -> e.set(paddedWidth / 2, paddedHeight / 2, 1));
			chromaCopy.get(0).imageSubresource()
				.aspectMask(VK_IMAGE_ASPECT_PLANE_1_BIT)
				.layerCount(1);
			vkCmdCopyBufferToImage(cmd, chromaBuffers[slot].handle(), session.srcImage(slot),
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, chromaCopy);
		}
	}

	private static boolean isBgra(int format)
	{
		return format == VK_FORMAT_B8G8R8A8_UNORM || format == VK_FORMAT_B8G8R8A8_SRGB;
	}

	private void imageBarrier(MemoryStack stack, VkCommandBuffer cmd, long image,
		int oldLayout, int newLayout, int srcStage, int dstStage,
		int srcAccess, int dstAccess, int aspect)
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
				.aspectMask(aspect)
				.baseMipLevel(0).levelCount(1)
				.baseArrayLayer(0).layerCount(1));
		vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
	}

	private void bufferBarrier(MemoryStack stack, VkCommandBuffer cmd, Buffer buffer,
		int srcAccess, int dstAccess, int srcStage, int dstStage)
	{
		VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack);
		barrier.get(0)
			.sType$Default()
			.srcAccessMask(srcAccess)
			.dstAccessMask(dstAccess)
			.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
			.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
			.buffer(buffer.handle())
			.offset(0)
			.size(VK_WHOLE_SIZE);
		vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, barrier, null);
	}

	// ---- resource setup -----------------------------------------------------

	private void createComputeResources()
	{
		try (MemoryStack stack = stackPush())
		{
			byte[] spirv = loadResource("nv12.comp.spv");
			ByteBuffer code = stack.malloc(spirv.length);
			code.put(spirv).flip();
			VkShaderModuleCreateInfo shaderInfo = VkShaderModuleCreateInfo.calloc(stack)
				.sType$Default()
				.pCode(code);
			LongBuffer pHandle = stack.mallocLong(1);
			Vk.check("vkCreateShaderModule (nv12)",
				vkCreateShaderModule(device.handle(), shaderInfo, null, pHandle));
			shaderModule = pHandle.get(0);

			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
			for (int i = 0; i < 3; i++)
			{
				bindings.get(i)
					.binding(i)
					.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
					.descriptorCount(1)
					.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			}
			VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pBindings(bindings);
			Vk.check("vkCreateDescriptorSetLayout (nv12)",
				vkCreateDescriptorSetLayout(device.handle(), layoutInfo, null, pHandle));
			descriptorSetLayout = pHandle.get(0);

			VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
			pushRange.get(0)
				.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
				.offset(0)
				.size(PUSH_SIZE);
			VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pSetLayouts(stack.longs(descriptorSetLayout))
				.pPushConstantRanges(pushRange);
			Vk.check("vkCreatePipelineLayout (nv12)",
				vkCreatePipelineLayout(device.handle(), pipelineLayoutInfo, null, pHandle));
			pipelineLayout = pHandle.get(0);

			VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
			pipelineInfo.get(0)
				.sType$Default()
				.layout(pipelineLayout);
			pipelineInfo.get(0).stage()
				.sType$Default()
				.stage(VK_SHADER_STAGE_COMPUTE_BIT)
				.module(shaderModule)
				.pName(stack.UTF8("main"));
			Vk.check("vkCreateComputePipelines (nv12)",
				vkCreateComputePipelines(device.handle(), VK_NULL_HANDLE, pipelineInfo, null, pHandle));
			pipeline = pHandle.get(0);

			VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
			poolSizes.get(0)
				.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
				.descriptorCount(3 * SLOTS);
			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
				.sType$Default()
				.flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
				.maxSets(SLOTS)
				.pPoolSizes(poolSizes);
			Vk.check("vkCreateDescriptorPool (nv12)",
				vkCreateDescriptorPool(device.handle(), poolInfo, null, pHandle));
			descriptorPool = pHandle.get(0);
		}
	}

	private void createConversionBuffers()
	{
		int width = sessionWidth;
		int height = sessionHeight;
		int paddedWidth = session.paddedWidth();
		int paddedHeight = session.paddedHeight();
		rgbaBuffers = new Buffer[SLOTS];
		lumaBuffers = new Buffer[SLOTS];
		chromaBuffers = new Buffer[SLOTS];
		descriptorSets = new long[SLOTS];
		try (MemoryStack stack = stackPush())
		{
			for (int i = 0; i < SLOTS; i++)
			{
				rgbaBuffers[i] = new Buffer(device, (long) width * height * 4,
					VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
					VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
				lumaBuffers[i] = new Buffer(device, (long) paddedWidth * paddedHeight,
					VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
					VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
				chromaBuffers[i] = new Buffer(device, (long) paddedWidth * paddedHeight / 2,
					VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
					VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

				VkDescriptorSetAllocateInfo alloc = VkDescriptorSetAllocateInfo.calloc(stack)
					.sType$Default()
					.descriptorPool(descriptorPool)
					.pSetLayouts(stack.longs(descriptorSetLayout));
				LongBuffer pSet = stack.mallocLong(1);
				Vk.check("vkAllocateDescriptorSets (nv12)",
					vkAllocateDescriptorSets(device.handle(), alloc, pSet));
				descriptorSets[i] = pSet.get(0);

				Buffer[] buffers = {rgbaBuffers[i], lumaBuffers[i], chromaBuffers[i]};
				VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
				for (int b = 0; b < 3; b++)
				{
					VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
					info.get(0)
						.buffer(buffers[b].handle())
						.offset(0)
						.range(VK_WHOLE_SIZE);
					writes.get(b)
						.sType$Default()
						.dstSet(descriptorSets[i])
						.dstBinding(b)
						.descriptorCount(1)
						.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
						.pBufferInfo(info);
				}
				vkUpdateDescriptorSets(device.handle(), writes, null);
			}
		}
	}

	private void destroyConversionBuffers()
	{
		try (MemoryStack stack = stackPush())
		{
			for (long set : descriptorSets)
			{
				if (set != VK_NULL_HANDLE)
				{
					vkFreeDescriptorSets(device.handle(), descriptorPool, stack.longs(set));
				}
			}
		}
		descriptorSets = new long[0];
		for (Buffer[] buffers : new Buffer[][]{rgbaBuffers, lumaBuffers, chromaBuffers})
		{
			for (Buffer buffer : buffers)
			{
				if (buffer != null)
				{
					buffer.close();
				}
			}
		}
		rgbaBuffers = lumaBuffers = chromaBuffers = new Buffer[0];
	}

	private static byte[] loadResource(String resource)
	{
		try (InputStream in = VideoRecorderExtension.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				throw new RuntimeException("missing resource: " + resource);
			}
			return in.readAllBytes();
		}
		catch (IOException e)
		{
			throw new RuntimeException("failed to read " + resource, e);
		}
	}

	@Override
	public void close()
	{
		stopBuffer();
		if (descriptorPool != VK_NULL_HANDLE)
		{
			vkDestroyDescriptorPool(device.handle(), descriptorPool, null);
			descriptorPool = VK_NULL_HANDLE;
		}
		if (pipeline != VK_NULL_HANDLE)
		{
			vkDestroyPipeline(device.handle(), pipeline, null);
			pipeline = VK_NULL_HANDLE;
		}
		if (pipelineLayout != VK_NULL_HANDLE)
		{
			vkDestroyPipelineLayout(device.handle(), pipelineLayout, null);
			pipelineLayout = VK_NULL_HANDLE;
		}
		if (descriptorSetLayout != VK_NULL_HANDLE)
		{
			vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
			descriptorSetLayout = VK_NULL_HANDLE;
		}
		if (shaderModule != VK_NULL_HANDLE)
		{
			vkDestroyShaderModule(device.handle(), shaderModule, null);
			shaderModule = VK_NULL_HANDLE;
		}
	}
}
