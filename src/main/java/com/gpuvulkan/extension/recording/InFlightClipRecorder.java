/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import com.gpuvulkan.encoding.StreamingVulkanEncoder;
import com.gpuvulkan.encoding.VideoEncoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

@Slf4j
final class InFlightClipRecorder implements VulkanRenderExtension
{
	private static final int MAX_TOTAL_SECONDS = 60;

	private final GpuVulkanPluginConfig config;
	private final VulkanDevice renderDevice;
	private final Buffer[] buffers = new Buffer[FrameSync.FRAMES_IN_FLIGHT];
	private final boolean[] slotReady = new boolean[FrameSync.FRAMES_IN_FLIGHT];
	private final int[] slotWidths = new int[FrameSync.FRAMES_IN_FLIGHT];
	private final int[] slotHeights = new int[FrameSync.FRAMES_IN_FLIGHT];
	private final ExecutorService frameExecutor = Executors.newSingleThreadExecutor(r ->
		new Thread(r, "vkgpu-clip-frames"));
	private final ScheduledExecutorService clipExecutor = Executors.newSingleThreadScheduledExecutor(r ->
		new Thread(r, "vkgpu-clip-writer"));
	private final AtomicInteger pendingEncodeFrames = new AtomicInteger();

	private StreamingVulkanEncoder encoder;
	private boolean probeAttempted;
	private String unavailableReason = "encoding disabled";
	private long nextCaptureNanos;
	private int width;
	private int height;

	InFlightClipRecorder(GpuVulkanPluginConfig config, VulkanDevice renderDevice)
	{
		this.config = config;
		this.renderDevice = renderDevice;
	}

	@Override
	public synchronized void onRegistered(VulkanRenderContext context)
	{
		configureEncoder();
	}

	@Override
	public synchronized void onConfigChanged(net.runelite.client.events.ConfigChanged event)
	{
		String key = event.getKey();
		if ("inFlightEncodingEnabled".equals(key)
			|| "inFlightEncodingType".equals(key)
			|| "inFlightEncodingFps".equals(key)
			|| "inFlightEncodingQuality".equals(key))
		{
			resetEncoder();
			probeAttempted = false;
			configureEncoder();
		}
		else if ("inFlightEncodingBufferSeconds".equals(key) && encoder != null)
		{
			updateBufferedFrameCount(encoder);
		}
	}

	@Override
	public void recordAfterComposite(VulkanPostFrameContext frame)
	{
		StreamingVulkanEncoder active;
		synchronized (this)
		{
			if (!config.inFlightEncodingEnabled())
			{
				return;
			}
			if (encoder == null && !probeAttempted)
			{
				configureEncoder();
			}
			active = encoder;
		}
		if (active == null)
		{
			return;
		}

		long now = System.nanoTime();
		if (now < nextCaptureNanos)
		{
			return;
		}
		nextCaptureNanos = now + (1_000_000_000L / captureFps());

		int slot = frame.frameIndex();
		submitCompletedSlot(slot, active);
		recordCopy(frame.commandBuffer(), frame.colorImage(), frame.width(), frame.height(),
			frame.imageLayout(), slot);
	}

	CompletableFuture<Path> saveClip()
	{
		return saveClip(config.inFlightEncodingPostWaitSeconds());
	}

	CompletableFuture<Path> saveClip(int requestedPostSeconds)
	{
		StreamingVulkanEncoder active;
		long triggerTime = System.currentTimeMillis();
		int preSeconds = clamp(config.inFlightEncodingBufferSeconds(), 1, MAX_TOTAL_SECONDS);
		int postSeconds = clamp(requestedPostSeconds, 0, MAX_TOTAL_SECONDS - preSeconds);
		synchronized (this)
		{
			if (!config.inFlightEncodingEnabled())
			{
				return CompletableFuture.failedFuture(new IllegalStateException("in-flight encoding is disabled"));
			}
			if (encoder == null && !probeAttempted)
			{
				configureEncoder();
			}
			active = encoder;
			if (active == null)
			{
				return CompletableFuture.failedFuture(new IllegalStateException(
					"in-flight encoding unavailable: " + unavailableReason));
			}
			updateBufferedFrameCount(active, preSeconds, postSeconds);
		}

		CompletableFuture<Path> future = new CompletableFuture<>();
		clipExecutor.schedule(() ->
		{
			try
			{
				long start = triggerTime - TimeUnit.SECONDS.toMillis(preSeconds);
				long end = triggerTime + TimeUnit.SECONDS.toMillis(postSeconds);
				VideoEncoder.ClipData clip = active.finalizeClip(start, end);
				if (clip == null || clip.getFrames().isEmpty())
				{
					throw new IOException("no encoded frames were available for the requested window");
				}
				if (!"video/mp4".equals(clip.getContentType()))
				{
					throw new IOException("encoder produced unsupported content type " + clip.getContentType());
				}
				Path out = writeClip(clip.getFrames().get(0), triggerTime);
				log.info("Saved Vulkan clip: {}", out);
				future.complete(out);
			}
			catch (Throwable t)
			{
				log.warn("Failed to save Vulkan clip", t);
				future.completeExceptionally(t);
			}
		}, postSeconds, TimeUnit.SECONDS);
		return future;
	}

	synchronized String unavailableReason()
	{
		return unavailableReason;
	}

	private synchronized void configureEncoder()
	{
		if (!config.inFlightEncodingEnabled())
		{
			unavailableReason = "encoding disabled";
			resetEncoder();
			return;
		}
		if (config.inFlightEncodingType() != GpuVulkanPluginConfig.EncodingType.MP4)
		{
			unavailableReason = "only MP4 is implemented";
			resetEncoder();
			return;
		}
		if (encoder != null)
		{
			updateBufferedFrameCount(encoder);
			return;
		}
		probeAttempted = true;
		if (!renderDevice.supportsVideoEncode())
		{
			unavailableReason = "Vulkan H.264 encode unavailable on the render device";
			log.info("In-flight encoding unavailable: {}", unavailableReason);
			return;
		}
		try
		{
			com.gpuvulkan.encoding.VulkanDevice encodeDevice = com.gpuvulkan.encoding.VulkanDevice.borrowed(
				renderDevice.physicalDevice(),
				renderDevice.handle(),
				renderDevice.videoEncodeQueue(),
				renderDevice.videoEncodeQueueFamily(),
				renderDevice.graphicsQueue(),
				renderDevice.graphicsQueueFamily(),
				renderDevice.deviceName(),
				renderDevice.h264EncodeExtensionName());
			com.gpuvulkan.encoding.VulkanCapabilities caps =
				new com.gpuvulkan.encoding.VulkanCapabilities(encodeDevice);
			if (!caps.probe())
			{
				unavailableReason = "render device failed Vulkan H.264 capability probe";
				log.info("In-flight encoding unavailable: {}", unavailableReason);
				return;
			}
			StreamingVulkanEncoder selected = new StreamingVulkanEncoder(encodeDevice, caps);
			applyEncoderSettings(selected);
			selected.start(captureFps());
			encoder = selected;
			unavailableReason = null;
			log.info("In-flight encoding enabled with {}", selected.encoderName());
		}
		catch (RuntimeException e)
		{
			unavailableReason = Objects.toString(e.getMessage(), "Vulkan H.264 encode init failed");
			log.info("In-flight encoding unavailable: {}", unavailableReason, e);
		}
	}

	private void updateBufferedFrameCount(StreamingVulkanEncoder selected)
	{
		int preSeconds = clamp(config.inFlightEncodingBufferSeconds(), 1, MAX_TOTAL_SECONDS);
		int postSeconds = clamp(config.inFlightEncodingPostWaitSeconds(), 0, MAX_TOTAL_SECONDS - preSeconds);
		updateBufferedFrameCount(selected, preSeconds, postSeconds);
	}

	private void updateBufferedFrameCount(StreamingVulkanEncoder selected, int preSeconds, int postSeconds)
	{
		selected.setMaxBufferedFrames((preSeconds + postSeconds) * captureFps());
	}

	private void applyEncoderSettings(StreamingVulkanEncoder selected)
	{
		configureBitrate(selected);
		updateBufferedFrameCount(selected);
	}

	private void configureBitrate(StreamingVulkanEncoder selected)
	{
		GpuVulkanPluginConfig.RecordingQuality quality = config.inFlightEncodingQuality();
		if (quality == null)
		{
			quality = GpuVulkanPluginConfig.RecordingQuality.STANDARD;
		}
		selected.configureBitrate(quality.averageBitrate(), quality.peakBitrate());
	}

	private int captureFps()
	{
		GpuVulkanPluginConfig.RecordingFps fps = config.inFlightEncodingFps();
		return fps == null ? 30 : fps.value();
	}

	private void submitCompletedSlot(int slot, StreamingVulkanEncoder active)
	{
		if (!slotReady[slot] || buffers[slot] == null)
		{
			return;
		}
		Buffer buffer = buffers[slot];
		int frameWidth = slotWidths[slot];
		int frameHeight = slotHeights[slot];
		slotReady[slot] = false;
		if (pendingEncodeFrames.get() >= captureFps())
		{
			return;
		}
		long timestamp = System.currentTimeMillis();
		pendingEncodeFrames.incrementAndGet();
		try
		{
			frameExecutor.execute(() ->
			{
				try
				{
					active.submitFrameBgraBuffer(buffer.handle(), frameWidth, frameHeight, timestamp, false);
				}
				catch (RuntimeException e)
				{
					log.warn("Failed to submit frame to in-flight encoder", e);
				}
				finally
				{
					pendingEncodeFrames.decrementAndGet();
				}
			});
		}
		catch (RejectedExecutionException e)
		{
			pendingEncodeFrames.decrementAndGet();
		}
	}

	@Override
	public void beforeSwapchainRebuild()
	{
		StreamingVulkanEncoder active;
		synchronized (this)
		{
			active = encoder;
			for (int i = 0; i < slotReady.length; i++)
			{
				slotReady[i] = false;
			}
		}
		if (active == null || frameExecutor.isShutdown())
		{
			return;
		}
		try
		{
			Future<?> drained = frameExecutor.submit(() -> { });
			drained.get(5, TimeUnit.SECONDS);
			active.stop();
			applyEncoderSettings(active);
			active.start(captureFps());
		}
		catch (Exception e)
		{
			log.warn("Failed to drain in-flight encoder before swapchain rebuild", e);
		}
		finally
		{
			pendingEncodeFrames.set(0);
		}
	}

	private void recordCopy(VkCommandBuffer cmd, long image, int frameWidth, int frameHeight, int imageLayout, int slot)
	{
		ensureBuffer(frameWidth, frameHeight, slot);
		try (MemoryStack stack = stackPush())
		{
			transition(stack, cmd, image,
				imageLayout,
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
				.imageExtent(e -> e.width(frameWidth).height(frameHeight).depth(1));
			region.get(0).imageSubresource()
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.mipLevel(0)
				.baseArrayLayer(0).layerCount(1);
			vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				buffers[slot].handle(), region);

			transition(stack, cmd, image,
				VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				imageLayout,
				VK_PIPELINE_STAGE_TRANSFER_BIT,
				VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
				VK_ACCESS_TRANSFER_READ_BIT,
				0);
		}
		slotReady[slot] = true;
		slotWidths[slot] = frameWidth;
		slotHeights[slot] = frameHeight;
	}

	private void ensureBuffer(int frameWidth, int frameHeight, int slot)
	{
		if (buffers[slot] != null && width == frameWidth && height == frameHeight)
		{
			return;
		}
		if (width != frameWidth || height != frameHeight)
		{
			closeBuffers();
			width = frameWidth;
			height = frameHeight;
		}
		if (buffers[slot] != null)
		{
			buffers[slot].close();
		}
		buffers[slot] = new Buffer(renderDevice, (long) frameWidth * frameHeight * Integer.BYTES,
			VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
			VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		slotReady[slot] = false;
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

	private Path writeClip(byte[] bytes, long timestamp) throws IOException
	{
		Path root = Paths.get(System.getProperty("user.home"), ".runelite", "recordings");
		Files.createDirectories(root);
		String slug = slugify("vulkan_clip_" + timestamp);
		Path candidate = root.resolve(slug + ".mp4");
		for (int i = 2; Files.exists(candidate); i++)
		{
			candidate = root.resolve(slug + "_" + i + ".mp4");
		}
		Files.write(candidate, bytes);
		return candidate;
	}

	private static String slugify(String input)
	{
		if (input == null) return "";
		String cleaned = input.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
		cleaned = cleaned.replaceAll("^_+|_+$", "");
		return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
	}

	private synchronized void resetEncoder()
	{
		if (encoder != null)
		{
			closeEncoder(encoder);
			encoder = null;
		}
	}

	private void closeEncoder(StreamingVulkanEncoder selected)
	{
		if (selected instanceof AutoCloseable)
		{
			try
			{
				((AutoCloseable) selected).close();
			}
			catch (Exception e)
			{
				log.debug("Encoder close failed", e);
			}
			return;
		}
		try
		{
			selected.stop();
		}
		catch (RuntimeException e)
		{
			log.debug("Encoder stop failed", e);
		}
	}

	private int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}

	@Override
	public synchronized void close()
	{
		resetEncoder();
		closeBuffers();
		pendingEncodeFrames.set(0);
		frameExecutor.shutdownNow();
		clipExecutor.shutdownNow();
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
			slotReady[i] = false;
			slotWidths[i] = 0;
			slotHeights[i] = 0;
		}
		width = 0;
		height = 0;
	}
}
