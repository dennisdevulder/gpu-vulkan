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

import java.awt.Image;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Queue;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.plugins.gpuvulkan.gfx.Renderer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Per-frame command record + submit + present. M2 records a single clear-pass
 * (no draws) — visible result is the dark blue clear colour filling the canvas.
 *
 * <p>Driven by {@code GpuVulkanPlugin.draw(overlayColor)}, which runs on
 * RuneLite's client thread one call per frame. No separate render thread.
 */
@Slf4j
final class VulkanRenderer implements AutoCloseable
{
	private final VulkanDevice device;
	private final RenderPass renderPass;
	private final RenderExtensions renderExtensions;
	private final FrameSync sync;
	private final DrawCallbackStats stats;
	private final GpuVulkanPluginConfig config;
	private final Renderer gfx;
	private final boolean disableScreenshotReadback =
		Boolean.parseBoolean(System.getProperty("vkgpu.skipScreenshotReadback", "false"));

	private final Swapchain swapchain;
	private final DepthBuffer depthBuffer;
	private final MsaaColorBuffer msaaColor; // null when MSAA is disabled
	private final Framebuffers framebuffers;
	private final ScreenshotReadback screenshotReadback;
	private OffscreenSceneTarget customPresentTarget;
	private DrawManager drawManager;
	private Field drawManagerNextFrameField;
	private boolean drawManagerNextFrameFieldFailed;
	private boolean screenshotReadbackRequested;
	/** macOS-only — custom-present path. Null on Linux (we use {@link #swapchain}). */
	private final MetalDrawableSet metalDrawables;
	/** Convenience flag; mirrors {@code metalDrawables != null}. */
	private final boolean useCustomPresent;
	/** Width/height tracked for the custom-present path so we can detect a
	 *  resize and rebuild depth/MSAA buffers + invalidate the drawable cache. */
	private int customPresentWidth, customPresentHeight;
	private int pendingCustomPresentWidth, pendingCustomPresentHeight;
	private long customPresentResizeAfterNanos;
	private static final long CUSTOM_PRESENT_RESIZE_SETTLE_NANOS = 200_000_000L;
	private long nextCustomPresentNanos;
	private final long customPresentIntervalNanos =
		1_000_000_000L / Math.max(1L, Long.getLong("vkgpu.presentFps", 75L));

	private final long commandPool;
	private final VkCommandBuffer[] commandBuffers;
	private final VkCommandBuffer[] presentCommandBuffers;
	private final SwapchainRebuildGate swapchainRebuild = new SwapchainRebuildGate();

	private double cameraX, cameraY, cameraZ;
	private double cameraPitch, cameraYaw;
	private int viewportXOffset, viewportYOffset, viewportWidth = 1, viewportHeight = 1;
	private int canvasWidth = 1, canvasHeight = 1;
	private int scale = 1;
	private int skyboxColor = 0x000000;
	private float brightness = 0.7f;
	private float textureLightMode;
	private int colorBlindMode;
	private float colorBlindIntensity;
	private int drawDistanceTiles = 90;
	private int fogDepthTiles = 30;
	private int gameTick = 0;
	private float smoothBanding = 1f;
	private int overlayColor = 0;

	VulkanRenderer(VulkanDevice device, RenderPass renderPass,
				   RenderExtensions renderExtensions,
				   Swapchain swapchain, DepthBuffer depthBuffer,
				   MsaaColorBuffer msaaColor,
				   Framebuffers framebuffers, FrameSync sync,
				   DrawCallbackStats stats, GpuVulkanPluginConfig config,
				   Renderer gfx)
	{
		this.device = device;
		this.renderPass = renderPass;
		this.renderExtensions = renderExtensions;
		this.swapchain = swapchain;
		this.depthBuffer = depthBuffer;
		this.msaaColor = msaaColor;
		this.framebuffers = framebuffers;
		this.sync = sync;
		this.stats = stats;
		this.config = config;
		this.gfx = gfx;
		this.screenshotReadback = new ScreenshotReadback(device);
		// On macOS the swapchain is still created (we need its surface
		// capabilities for format selection and its initial extent), but
		// vkAcquireNextImageKHR / vkQueuePresentKHR are bypassed — we
		// acquire the CAMetalDrawable ourselves and present via Metal.
		this.useCustomPresent = device.supportsMetalObjects();
		this.metalDrawables = useCustomPresent ? new MetalDrawableSet(device) : null;
		this.customPresentWidth = swapchain.width();
		this.customPresentHeight = swapchain.height();

		try (MemoryStack stack = stackPush())
		{
			VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
				.sType$Default()
				.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
				.queueFamilyIndex(device.graphicsQueueFamily());
			LongBuffer pPool = stack.mallocLong(1);
			Vk.check("vkCreateCommandPool", vkCreateCommandPool(device.handle(), poolInfo, null, pPool));
			commandPool = pPool.get(0);

			VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
				.sType$Default()
				.commandPool(commandPool)
				.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
				.commandBufferCount(FrameSync.FRAMES_IN_FLIGHT * 2);
			PointerBuffer pBufs = stack.mallocPointer(FrameSync.FRAMES_IN_FLIGHT * 2);
			Vk.check("vkAllocateCommandBuffers", vkAllocateCommandBuffers(device.handle(), allocInfo, pBufs));
			commandBuffers = new VkCommandBuffer[FrameSync.FRAMES_IN_FLIGHT];
			presentCommandBuffers = new VkCommandBuffer[FrameSync.FRAMES_IN_FLIGHT];
			for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
			{
				commandBuffers[i] = new VkCommandBuffer(pBufs.get(i), device.handle());
				presentCommandBuffers[i] = new VkCommandBuffer(pBufs.get(i + FrameSync.FRAMES_IN_FLIGHT), device.handle());
			}
		}
	}

	void setDrawManager(DrawManager drawManager)
	{
		this.drawManager = drawManager;
	}

	void markSwapchainStale()
	{
		if (useCustomPresent)
		{
			return;
		}
		swapchainRebuild.markStale();
	}

	boolean usesCustomPresent()
	{
		return useCustomPresent;
	}

	void drawFrame(int desiredWidth, int desiredHeight,
				   int[] uiPixels, int uiWidth, int uiHeight,
				   double cameraX, double cameraY, double cameraZ,
				   double cameraPitch, double cameraYaw,
				   int viewportXOffset, int viewportYOffset,
				   int viewportWidth, int viewportHeight,
				   int canvasWidth, int canvasHeight, int scale,
				   int skyboxColor, float brightness, float textureLightMode,
				   int colorBlindMode, float colorBlindIntensity,
				   int drawDistanceTiles, int fogDepthTiles, int gameTick,
				   float smoothBanding, int overlayColor)
	{
		long frameStart = stats.startNanos();
		stats.frames.incrementAndGet();
		if (desiredWidth <= 0 || desiredHeight <= 0 || uiPixels == null || uiWidth <= 0 || uiHeight <= 0)
		{
			return;
		}
		if (!useCustomPresent && swapchainRebuild.isStale())
		{
			if (!swapchainRebuild.targetStable(desiredWidth, desiredHeight))
			{
				return;
			}
			rebuildSwapchain(desiredWidth, desiredHeight);
		}
		if (swapchain.width() == 0 || swapchain.height() == 0)
		{
			return; // window minimised — nothing to render
		}


		// Stash for the scene MVP we'll build at record time.
		this.cameraX = cameraX;
		this.cameraY = cameraY;
		this.cameraZ = cameraZ;
		this.cameraPitch = cameraPitch;
		this.cameraYaw = cameraYaw;
		this.viewportXOffset = viewportXOffset;
		this.viewportYOffset = viewportYOffset;
		this.viewportWidth = viewportWidth;
		this.viewportHeight = viewportHeight;
		this.canvasWidth = canvasWidth;
		this.canvasHeight = canvasHeight;
		this.scale = scale;
		this.skyboxColor = skyboxColor;
		this.brightness = brightness;
		this.textureLightMode = textureLightMode;
		this.colorBlindMode = colorBlindMode;
		this.colorBlindIntensity = colorBlindIntensity;
		this.drawDistanceTiles = drawDistanceTiles;
		this.fogDepthTiles = fogDepthTiles;
		this.gameTick = gameTick;
		this.smoothBanding = smoothBanding;
		this.overlayColor = overlayColor;
		this.screenshotReadbackRequested = !disableScreenshotReadback && hasPendingScreenshotRequest();

		try (MemoryStack stack = stackPush())
		{
			// Wait BEFORE writing this slot's UI staging buffer — otherwise the
			// CPU memcpy in uploadPixels can race the GPU's vkCmdCopyBufferToImage
			// from FRAMES_IN_FLIGHT frames ago, producing the half-old, half-new
			// UI texture that reads as the UI/scene "layer" intermittently
			// covering and uncovering.
			long start = stats.startNanos();
			if (vkGetFenceStatus(device.handle(), sync.inFlightFence()) == VK_NOT_READY)
			{
				Vk.check("vkWaitForFences",
					vkWaitForFences(device.handle(), sync.inFlightFence(), true, Long.MAX_VALUE));
				stats.addNanos(stats.fenceWaitNanos, start);
			}

			// CPU-side memcpy into the persistently-mapped staging buffer. Done
			// before recording the command buffer so the GPU read sees it.
			start = stats.startNanos();
			stats.uiUploadBytes.addAndGet((long) uiWidth * uiHeight * Integer.BYTES);
			renderExtensions.uploadUiPixels(uiPixels, uiWidth, uiHeight);
			stats.addNanos(stats.uiUploadNanos, start);

			if (useCustomPresent)
			{
				drawFrameCustomPresent(stack, desiredWidth, desiredHeight);
			}
			else
			{
				drawFrameSwapchain(stack, desiredWidth, desiredHeight);
			}

			sync.advance();
		}
		finally
		{
			stats.addNanos(stats.drawFrameNanos, frameStart);
		}
	}

	/**
	 * Linux path: standard {@code KHRSwapchain.vkAcquireNextImageKHR}
	 * + render + {@code vkQueuePresentKHR}.
	 */
	private void drawFrameSwapchain(MemoryStack stack, int desiredWidth, int desiredHeight)
	{
		IntBuffer pImageIdx = stack.mallocInt(1);
		long start = stats.startNanos();
		int acq = KHRSwapchain.vkAcquireNextImageKHR(device.handle(), swapchain.handle(),
			Long.MAX_VALUE, sync.imageAvailable(), VK_NULL_HANDLE, pImageIdx);
		stats.addNanos(stats.acquireNanos, start);

		if (acq == VK_ERROR_OUT_OF_DATE_KHR)
		{
			swapchainRebuild.markStale();
			return;
		}
		if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR)
		{
			throw new RuntimeException("vkAcquireNextImageKHR failed: " + acq);
		}
		if (acq == VK_SUBOPTIMAL_KHR)
		{
			swapchainRebuild.markStale();
		}
		int imageIdx = pImageIdx.get(0);

		VkCommandBuffer cmd = commandBuffers[sync.currentFrame()];
		Vk.check("vkResetCommandBuffer", vkResetCommandBuffer(cmd, 0));
		start = stats.startNanos();
		recordClearPass(stack, cmd, framebuffers.get(imageIdx),
			swapchain.width(), swapchain.height(), swapchain.image(imageIdx));
		stats.addNanos(stats.commandRecordNanos, start);

		// Reset only once recording succeeded — an unsignaled fence with no
		// pending submit deadlocks this slot's next wait.
		Vk.check("vkResetFences", vkResetFences(device.handle(), sync.inFlightFence()));
		start = stats.startNanos();
		submit(stack, cmd, imageIdx);
		stats.addNanos(stats.submitNanos, start);
		processDrawComplete();
		start = stats.startNanos();
		int present = present(stack, imageIdx);
		stats.addNanos(stats.presentNanos, start);
		if (present == VK_ERROR_OUT_OF_DATE_KHR || present == VK_SUBOPTIMAL_KHR)
		{
			swapchainRebuild.markStale();
		}
		else if (present != VK_SUCCESS)
		{
			throw new RuntimeException("vkQueuePresentKHR failed: " + present);
		}
	}

	/**
	 * macOS path: acquire {@code CAMetalDrawable} directly, import its
	 * {@code MTLTexture} as a {@code VkImage}, render, then present via a
	 * Metal command buffer we own (bypassing
	 * {@code vkQueuePresentKHR}).
	 *
	 * <p>Ordering: {@code vkQueueSubmit} writes render commands into the
	 * MTLCommandQueue MoltenVK manages for our VkQueue. The subsequent
	 * {@code [drawable present]} call in {@link MacOSMetalHelper#presentDrawable}
	 * runs on that SAME MTLCommandQueue (we extracted it via
	 * {@code vkExportMetalObjectsEXT}), so Metal's in-queue ordering
	 * guarantees the present is scheduled after the render. No cross-queue
	 * semaphore needed.
	 */
	private void drawFrameCustomPresent(MemoryStack stack, int desiredWidth, int desiredHeight)
	{
		long now = System.nanoTime();
		if (now < nextCustomPresentNanos)
		{
			// Paced out — nothing will be presented, skip the render.
			return;
		}

		ensureCustomPresentTarget(desiredWidth, desiredHeight);

		long drawable = 0L;
		MetalDrawableSet.Entry drawableEntry = null;
		int drawableWidth = 0;
		int drawableHeight = 0;
		{
			long start = stats.startNanos();
			long[] d = MacOSMetalHelper.nextDrawable();
			stats.addNanos(stats.customDrawableNanos, start);
			nextCustomPresentNanos = System.nanoTime() + customPresentIntervalNanos;
			if (d != null)
			{
				drawable = d[0];
				long mtlTexture = d[1];
				drawableWidth = (int) d[2];
				drawableHeight = (int) d[3];
				drawableEntry = metalDrawables.acquire(
					mtlTexture, drawableWidth, drawableHeight, renderPass, customPresentTarget.depth(), null);
			}
		}
		boolean presented = false;

		try
		{
			VkCommandBuffer cmd = commandBuffers[sync.currentFrame()];
			Vk.check("vkResetCommandBuffer", vkResetCommandBuffer(cmd, 0));
			long start = stats.startNanos();
			int renderWidth = customPresentTarget.width();
			int renderHeight = customPresentTarget.height();
			recordClearPass(stack, cmd, customPresentTarget.framebuffer(),
				renderWidth, renderHeight, customPresentTarget.colorImage());
			VkCommandBuffer presentCmd = null;
			if (drawableEntry != null)
			{
				presentCmd = presentCommandBuffers[sync.currentFrame()];
				Vk.check("vkResetCommandBuffer (present)", vkResetCommandBuffer(presentCmd, 0));
				recordCopyToDrawable(stack, presentCmd, customPresentTarget.colorImage(),
					renderWidth, renderHeight, drawableEntry, drawableWidth, drawableHeight);
			}
			stats.addNanos(stats.commandRecordNanos, start);

			Vk.check("vkResetFences", vkResetFences(device.handle(), sync.inFlightFence()));
			start = stats.startNanos();
			submitNoSemaphores(stack, cmd, presentCmd);
			stats.addNanos(stats.submitNanos, start);
			processDrawComplete();

			if (drawableEntry != null)
			{
				start = stats.startNanos();
				MacOSMetalHelper.presentDrawable(drawable, device.metalCommandQueue());
				presented = true;
				stats.addNanos(stats.presentNanos, start);
			}
		}
		finally
		{
			if (drawable != 0L && !presented)
			{
				MacOSMetalHelper.releaseObject(drawable);
			}
		}
	}

	private void ensureCustomPresentTarget(int width, int height)
	{
		width = Math.max(width, 1);
		height = Math.max(height, 1);
		long now = System.nanoTime();
		if (customPresentTarget == null)
		{
			customPresentTarget = new OffscreenSceneTarget(device, renderPass,
				width, height, swapchain.imageFormat(), renderPass.samples());
			customPresentWidth = width;
			customPresentHeight = height;
			pendingCustomPresentWidth = width;
			pendingCustomPresentHeight = height;
			return;
		}
		if (width == customPresentWidth && height == customPresentHeight)
		{
			pendingCustomPresentWidth = width;
			pendingCustomPresentHeight = height;
			return;
		}
		if (width != pendingCustomPresentWidth || height != pendingCustomPresentHeight)
		{
			pendingCustomPresentWidth = width;
			pendingCustomPresentHeight = height;
			customPresentResizeAfterNanos = now + CUSTOM_PRESENT_RESIZE_SETTLE_NANOS;
			return;
		}
		if (now < customPresentResizeAfterNanos)
		{
			return;
		}

		// Resize settled: recreate the target. Fence wait (not deviceWaitIdle)
		// drains frames referencing the old target and its drawable framebuffers.
		sync.waitAllInFlight();
		if (metalDrawables != null)
		{
			metalDrawables.flush();
		}
		customPresentTarget.close();
		customPresentTarget = new OffscreenSceneTarget(device, renderPass,
			width, height, swapchain.imageFormat(), renderPass.samples());
		customPresentWidth = width;
		customPresentHeight = height;
	}

	private void recordCopyToDrawable(MemoryStack stack, VkCommandBuffer cmd, long sourceImage,
									  int sourceWidth, int sourceHeight,
									  MetalDrawableSet.Entry drawableEntry,
									  int drawableWidth, int drawableHeight)
	{
		VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
			.sType$Default()
			.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
		Vk.check("vkBeginCommandBuffer (present copy)", vkBeginCommandBuffer(cmd, begin));

		transitionImage(stack, cmd, sourceImage,
			VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
			VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
			VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
			VK_PIPELINE_STAGE_TRANSFER_BIT,
			VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
			VK_ACCESS_TRANSFER_READ_BIT);
		transitionImage(stack, cmd, drawableEntry.image,
			drawableEntry.layout,
			VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
			drawableEntry.layout == VK_IMAGE_LAYOUT_UNDEFINED
				? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
				: VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
			VK_PIPELINE_STAGE_TRANSFER_BIT,
			0,
			VK_ACCESS_TRANSFER_WRITE_BIT);
		drawableEntry.layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

		VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
		blit.get(0).srcSubresource(r -> r
			.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
			.mipLevel(0)
			.baseArrayLayer(0)
			.layerCount(1));
		blit.get(0).srcOffsets(0).set(0, 0, 0);
		blit.get(0).srcOffsets(1).set(sourceWidth, sourceHeight, 1);
		blit.get(0).dstSubresource(r -> r
			.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
			.mipLevel(0)
			.baseArrayLayer(0)
			.layerCount(1));
		blit.get(0).dstOffsets(0).set(0, 0, 0);
		blit.get(0).dstOffsets(1).set(drawableWidth, drawableHeight, 1);
		vkCmdBlitImage(cmd,
			sourceImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
			drawableEntry.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
			blit, VK_FILTER_LINEAR);

		transitionImage(stack, cmd, drawableEntry.image,
			VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
			VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
			VK_PIPELINE_STAGE_TRANSFER_BIT,
			VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
			VK_ACCESS_TRANSFER_WRITE_BIT,
			0);
		drawableEntry.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

		Vk.check("vkEndCommandBuffer (present copy)", vkEndCommandBuffer(cmd));
	}

	private void processDrawComplete()
	{
		DrawManager dm = drawManager;
		if (dm != null)
		{
			dm.processDrawComplete(this::screenshot);
		}
	}

	private Image screenshot()
	{
		if (disableScreenshotReadback)
		{
			return null;
		}
		if (vkGetFenceStatus(device.handle(), sync.inFlightFence()) == VK_NOT_READY)
		{
			Vk.check("vkWaitForFences (screenshot)",
				vkWaitForFences(device.handle(), sync.inFlightFence(), true, Long.MAX_VALUE));
		}
		return screenshotReadback.toImage();
	}

	private boolean hasPendingScreenshotRequest()
	{
		DrawManager dm = drawManager;
		if (dm == null || drawManagerNextFrameFieldFailed)
		{
			return false;
		}
		try
		{
			Field field = drawManagerNextFrameField;
			if (field == null)
			{
				field = DrawManager.class.getDeclaredField("nextFrame");
				field.setAccessible(true);
				drawManagerNextFrameField = field;
			}
			Object value = field.get(dm);
			return value instanceof Queue && !((Queue<?>) value).isEmpty();
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			drawManagerNextFrameFieldFailed = true;
			log.warn("Unable to inspect DrawManager screenshot queue; Vulkan screenshot readback disabled", e);
			return false;
		}
	}

	@Override
	public void close()
	{
		vkDeviceWaitIdle(device.handle());
		if (metalDrawables != null)
		{
			metalDrawables.close();
		}
		if (customPresentTarget != null)
		{
			customPresentTarget.close();
			customPresentTarget = null;
		}
		screenshotReadback.close();
		// vkDestroyCommandPool implicitly frees all command buffers allocated
		// from it (per spec). Don't pre-reset them: that was added as a
		// defensive measure for the validation layer's tracking, but with
		// validation off it's just an extra Vulkan call that has been
		// observed to trip something in RADV's destroy path (process exits
		// silently between "disposing: VulkanRenderer" and the next dispose).
		if (commandPool != VK_NULL_HANDLE)
		{
			vkDestroyCommandPool(device.handle(), commandPool, null);
		}
	}

	private void rebuildSwapchain(int desiredWidth, int desiredHeight)
	{
		Vk.check("vkDeviceWaitIdle", vkDeviceWaitIdle(device.handle()));
		framebuffers.destroyAll();
		swapchain.recreate(desiredWidth, desiredHeight);
		depthBuffer.recreate(swapchain.width(), swapchain.height());
		if (msaaColor != null)
		{
			msaaColor.recreate(swapchain.width(), swapchain.height());
		}
		framebuffers.recreate(renderPass, swapchain, depthBuffer, msaaColor);
		sync.recreateRenderFinished(swapchain.imageCount());
		swapchainRebuild.markRebuilt();
		log.debug("rebuildSwapchain -> {}x{}", swapchain.width(), swapchain.height());
	}

	private void recordClearPass(MemoryStack stack, VkCommandBuffer cmd,
								 long framebuffer, int targetWidth, int targetHeight,
								 long targetImage)
	{
		VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
			.sType$Default()
			.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
		Vk.check("vkBeginCommandBuffer", vkBeginCommandBuffer(cmd, begin));

		// UI texture upload + transitions happen OUTSIDE the render pass —
		// vkCmdCopyBufferToImage isn't allowed inside one.
		long start = stats.startNanos();
		renderExtensions.recordBeforeRenderPass(cmd);
		stats.addNanos(stats.beforeRenderPassNanos, start);

		ScenePassRedirect redirect = renderExtensions.scenePassRedirect();
		if (redirect != null)
		{
			recordRedirectedPass(stack, cmd, redirect, framebuffer, targetWidth, targetHeight);
			if (screenshotReadbackRequested)
			{
				stats.screenshotReadbackBytes.addAndGet((long) targetWidth * targetHeight * Integer.BYTES);
				screenshotReadback.recordCopy(cmd, targetImage, targetWidth, targetHeight, sync.currentFrame());
			}
			Vk.check("vkEndCommandBuffer", vkEndCommandBuffer(cmd));
			return;
		}

		// Two clears now: colour attachment then depth attachment, in the same
		// order they were declared in the render pass. Depth clear = 0 because
		// the OSRS projection is reverse-Z (closer = bigger value).
		// Skybox-derived clear so the area outside rendered geometry blends
		// into the sky tint OSRS picked for the current region. Stock GpuPlugin
		// reads this from `client.getSkyboxColor()` once per frame.
		float skyR = ((skyboxColor >> 16) & 0xFF) / 255f;
		float skyG = ((skyboxColor >>  8) & 0xFF) / 255f;
		float skyB = ( skyboxColor        & 0xFF) / 255f;
		VkClearValue.Buffer clears = VkClearValue.calloc(2, stack);
		clears.get(0).color(c -> c.float32(0, skyR).float32(1, skyG).float32(2, skyB).float32(3, 1.0f));
		clears.get(1).depthStencil(ds -> ds.depth(0.0f).stencil(0));

		VkRenderPassBeginInfo rpInfo = VkRenderPassBeginInfo.calloc(stack)
			.sType$Default()
			.renderPass(renderPass.handle())
			.framebuffer(framebuffer)
			.renderArea(r -> r.offset(o -> o.set(0, 0)).extent(e -> e.set(targetWidth, targetHeight)))
			.pClearValues(clears);

		vkCmdBeginRenderPass(cmd, rpInfo, VK_SUBPASS_CONTENTS_INLINE);

		// Scene draws use the OSRS viewport rect — that's the area inside the
		// canvas where the UI texture is transparent. The UI fullscreen-quad
		// below switches back to the full canvas extent.
		//
		// Stretch ratio comes from CANVAS dims, not VIEWPORT dims (matches
		// stock GpuPlugin's `dim.getHeight() / canvasHeight` derivation). The
		// engine renders into a logical canvas (e.g. 1117×917) which gets
		// stretched uniformly to the swapchain (1676×1375 here, 1.5×). The
		// viewport rect lives inside the canvas with possible insets (e.g.
		// resizable mode's top toolbar trims viewportHeight). Deriving dpi
		// from viewport dims would give different X/Y ratios when the viewport
		// is shorter than the canvas — that non-uniform stretch shifts
		// rendered tiles off the engine's projection target by tens of
		// pixels in Y, which is exactly the "click only works in shifting
		// spots near the player" symptom.
		float dpiX = (float) targetWidth  / Math.max(canvasWidth, 1);
		float dpiY = (float) targetHeight / Math.max(canvasHeight, 1);
		int sx = Math.max(Math.round(viewportXOffset * dpiX), 0);
		int sy = Math.max(Math.round(viewportYOffset * dpiY), 0);
		int sw = Math.max(Math.min(Math.round(viewportWidth  * dpiX), targetWidth  - sx), 1);
		int sh = Math.max(Math.min(Math.round(viewportHeight * dpiY), targetHeight - sy), 1);
		// Standard positive-height Vulkan viewport. We Y-flip in the matrix
		// (scale Y by -1 below) instead of relying on KHR_maintenance1
		// negative-height — that was being silently ignored by the runtime,
		// leaving Y un-flipped and rendering everything at the wrong half.
		VkViewport.Buffer sceneVp = VkViewport.calloc(1, stack);
		sceneVp.get(0)
			.x(sx).y(sy)
			.width(sw).height(sh)
			.minDepth(0).maxDepth(1);
		vkCmdSetViewport(cmd, 0, sceneVp);

		VkRect2D.Buffer sceneScissor = VkRect2D.calloc(1, stack);
		sceneScissor.get(0)
			.offset(o -> o.set(sx, sy))
			.extent(e -> e.set(sw, sh));
		vkCmdSetScissor(cmd, 0, sceneScissor);

		// Stock GpuPlugin's matrix verbatim, with Y scale negated so the projection's
		// m[5]=-2/h (OpenGL y-up clip-space) lands right-side-up in Vulkan's y-down
		// clip space. viewportWidth/Height stay in logical pixels — the matrix output
		// is in those same units, and the DPI-scaled Vulkan viewport above maps NDC
		// onto the swapchain's display pixels. Z is unscaled to keep the reverse-Z
		// projection (z_ndc = 2n/z) inside Vulkan's [0,1] clip range.
		int vw = Math.max(viewportWidth, 1);
		int vh = Math.max(viewportHeight, 1);
		float[] sceneMvp = Mat4Ops.scale(scale, -scale, 1);
		Mat4Ops.mul(sceneMvp, Mat4Ops.projection(vw, vh, 50));
		Mat4Ops.mul(sceneMvp, Mat4Ops.rotateX((float) cameraPitch));
		Mat4Ops.mul(sceneMvp, Mat4Ops.rotateY((float) cameraYaw));
		Mat4Ops.mul(sceneMvp, Mat4Ops.translate(-(float) cameraX, -(float) cameraY, -(float) cameraZ));

		VulkanFrameContext frame = new DefaultVulkanFrameContext(cmd,
			targetWidth, targetHeight,
			viewportXOffset, viewportYOffset, viewportWidth, viewportHeight,
			canvasWidth, canvasHeight, scale,
			sceneMvp,
			(float) cameraX, (float) cameraY, (float) cameraZ, brightness,
			drawDistanceTiles, fogDepthTiles,
			((skyboxColor >> 16) & 0xFF) / 255f,
			((skyboxColor >>  8) & 0xFF) / 255f,
			( skyboxColor        & 0xFF) / 255f,
			gameTick, textureLightMode,
			colorBlindMode, colorBlindIntensity,
			smoothBanding, overlayColor);
		start = stats.startNanos();
		renderExtensions.recordRenderPass(frame);
		stats.addNanos(stats.renderPassNanos, start);

		// Switch viewport back to full canvas for the UI fullscreen quad — the UI
		// texture covers everything, including the regions outside the scene rect.
		VkViewport.Buffer uiVp = VkViewport.calloc(1, stack);
		uiVp.get(0)
			.x(0).y(0)
			.width(targetWidth).height(targetHeight)
			.minDepth(0).maxDepth(1);
		vkCmdSetViewport(cmd, 0, uiVp);

		VkRect2D.Buffer uiScissor = VkRect2D.calloc(1, stack);
		uiScissor.get(0)
			.offset(o -> o.set(0, 0))
			.extent(e -> e.set(targetWidth, targetHeight));
		vkCmdSetScissor(cmd, 0, uiScissor);

		// UI on top — fullscreen quad sampling the just-uploaded texture, alpha-blended,
		// depth disabled in the pipeline so it always wins. overlayColor is the engine's
		// per-frame fade/tint (login screen fade, etc.) — passed all the way through
		// from DrawCallbacks.draw(int) to the ui.frag push constant.
		vkCmdEndRenderPass(cmd);

		if (screenshotReadbackRequested)
		{
			stats.screenshotReadbackBytes.addAndGet((long) targetWidth * targetHeight * Integer.BYTES);
			screenshotReadback.recordCopy(cmd, targetImage, targetWidth, targetHeight, sync.currentFrame());
		}

		Vk.check("vkEndCommandBuffer", vkEndCommandBuffer(cmd));
	}

	private void recordRedirectedPass(MemoryStack stack, VkCommandBuffer cmd, ScenePassRedirect redirect,
									  long framebuffer, int targetWidth, int targetHeight)
	{
		GfxRenderTarget sceneTarget = (GfxRenderTarget) redirect.sceneTarget(targetWidth, targetHeight);

		float skyR = ((skyboxColor >> 16) & 0xFF) / 255f;
		float skyG = ((skyboxColor >>  8) & 0xFF) / 255f;
		float skyB = ( skyboxColor        & 0xFF) / 255f;
		VkClearValue.Buffer clears = VkClearValue.calloc(2, stack);
		clears.get(0).color(c -> c.float32(0, skyR).float32(1, skyG).float32(2, skyB).float32(3, 1.0f));
		clears.get(1).depthStencil(ds -> ds.depth(0.0f).stencil(0));

		VkRenderPassBeginInfo sceneRp = VkRenderPassBeginInfo.calloc(stack)
			.sType$Default()
			.renderPass(sceneTarget.renderPassHandle())
			.framebuffer(sceneTarget.framebuffer())
			.renderArea(r -> r.offset(o -> o.set(0, 0)).extent(e -> e.set(sceneTarget.width(), sceneTarget.height())))
			.pClearValues(clears);
		vkCmdBeginRenderPass(cmd, sceneRp, VK_SUBPASS_CONTENTS_INLINE);

		VulkanFrameContext sceneFrame = setupSceneViewportAndFrame(stack, cmd, sceneTarget.width(), sceneTarget.height());
		renderExtensions.recordScenePass(sceneFrame);
		vkCmdEndRenderPass(cmd);

		redirect.recordAfterScene(cmd);

		VkRenderPassBeginInfo mainRp = VkRenderPassBeginInfo.calloc(stack)
			.sType$Default()
			.renderPass(renderPass.handle())
			.framebuffer(framebuffer)
			.renderArea(r -> r.offset(o -> o.set(0, 0)).extent(e -> e.set(targetWidth, targetHeight)))
			.pClearValues(clears);
		vkCmdBeginRenderPass(cmd, mainRp, VK_SUBPASS_CONTENTS_INLINE);

		VulkanFrameContext resolveFrame = setupUiViewportAndFrame(stack, cmd, targetWidth, targetHeight);
		redirect.recordResolve(resolveFrame);
		renderExtensions.recordUiPass(resolveFrame);
		vkCmdEndRenderPass(cmd);
	}

	private VulkanFrameContext setupSceneViewportAndFrame(MemoryStack stack, VkCommandBuffer cmd,
														  int targetWidth, int targetHeight)
	{
		float dpiX = (float) targetWidth  / Math.max(canvasWidth, 1);
		float dpiY = (float) targetHeight / Math.max(canvasHeight, 1);
		int sx = Math.max(Math.round(viewportXOffset * dpiX), 0);
		int sy = Math.max(Math.round(viewportYOffset * dpiY), 0);
		int sw = Math.max(Math.min(Math.round(viewportWidth  * dpiX), targetWidth  - sx), 1);
		int sh = Math.max(Math.min(Math.round(viewportHeight * dpiY), targetHeight - sy), 1);

		VkViewport.Buffer sceneVp = VkViewport.calloc(1, stack);
		sceneVp.get(0)
			.x(sx).y(sy)
			.width(sw).height(sh)
			.minDepth(0).maxDepth(1);
		vkCmdSetViewport(cmd, 0, sceneVp);

		VkRect2D.Buffer sceneScissor = VkRect2D.calloc(1, stack);
		sceneScissor.get(0)
			.offset(o -> o.set(sx, sy))
			.extent(e -> e.set(sw, sh));
		vkCmdSetScissor(cmd, 0, sceneScissor);

		return createFrameContext(cmd, targetWidth, targetHeight);
	}

	private VulkanFrameContext setupUiViewportAndFrame(MemoryStack stack, VkCommandBuffer cmd,
													   int targetWidth, int targetHeight)
	{
		VkViewport.Buffer uiVp = VkViewport.calloc(1, stack);
		uiVp.get(0)
			.x(0).y(0)
			.width(targetWidth).height(targetHeight)
			.minDepth(0).maxDepth(1);
		vkCmdSetViewport(cmd, 0, uiVp);

		VkRect2D.Buffer uiScissor = VkRect2D.calloc(1, stack);
		uiScissor.get(0)
			.offset(o -> o.set(0, 0))
			.extent(e -> e.set(targetWidth, targetHeight));
		vkCmdSetScissor(cmd, 0, uiScissor);

		return createFrameContext(cmd, targetWidth, targetHeight);
	}

	private VulkanFrameContext createFrameContext(VkCommandBuffer cmd, int targetWidth, int targetHeight)
	{
		int vw = Math.max(viewportWidth, 1);
		int vh = Math.max(viewportHeight, 1);
		float[] sceneMvp = Mat4Ops.scale(scale, -scale, 1);
		Mat4Ops.mul(sceneMvp, Mat4Ops.projection(vw, vh, 50));
		Mat4Ops.mul(sceneMvp, Mat4Ops.rotateX((float) cameraPitch));
		Mat4Ops.mul(sceneMvp, Mat4Ops.rotateY((float) cameraYaw));
		Mat4Ops.mul(sceneMvp, Mat4Ops.translate(-(float) cameraX, -(float) cameraY, -(float) cameraZ));

		return new DefaultVulkanFrameContext(cmd,
			targetWidth, targetHeight,
			viewportXOffset, viewportYOffset, viewportWidth, viewportHeight,
			canvasWidth, canvasHeight, scale,
			sceneMvp,
			(float) cameraX, (float) cameraY, (float) cameraZ, brightness,
			drawDistanceTiles, fogDepthTiles,
			((skyboxColor >> 16) & 0xFF) / 255f,
			((skyboxColor >>  8) & 0xFF) / 255f,
			( skyboxColor        & 0xFF) / 255f,
			gameTick, textureLightMode,
			colorBlindMode, colorBlindIntensity,
			smoothBanding, overlayColor);
	}

	private void transitionImage(MemoryStack stack, VkCommandBuffer cmd, long image,
								 int oldLayout, int newLayout,
								 int srcStage, int dstStage,
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

	private void submit(MemoryStack stack, VkCommandBuffer cmd, int imageIdx)
	{
		IntBuffer waitStages = stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
		LongBuffer wait = stack.longs(sync.imageAvailable());
		LongBuffer signal = stack.longs(sync.renderFinishedFor(imageIdx));
		PointerBuffer cmdBuf = stack.pointers(cmd);

		VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
			.sType$Default()
			.waitSemaphoreCount(1)
			.pWaitSemaphores(wait)
			.pWaitDstStageMask(waitStages)
			.pCommandBuffers(cmdBuf)
			.pSignalSemaphores(signal);

		Vk.check("vkQueueSubmit", vkQueueSubmit(device.graphicsQueue(), submit, sync.inFlightFence()));
	}

	/**
	 * Submit for the macOS custom-present path: no semaphore wait (we
	 * acquired the drawable directly from CAMetalLayer, not via
	 * vkAcquireNextImageKHR's signal-semaphore handshake), no signal
	 * semaphore (the subsequent {@code [presentDrawable]} on the same
	 * MTLCommandQueue is implicitly ordered after our render commands
	 * by Metal). The inFlightFence still gates the slot for command-buffer
	 * reuse on the next iteration.
	 */
	private void submitNoSemaphores(MemoryStack stack, VkCommandBuffer cmd, VkCommandBuffer presentCmd)
	{
		PointerBuffer cmdBuf = presentCmd == null
			? stack.pointers(cmd)
			: stack.pointers(cmd, presentCmd);
		VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
			.sType$Default()
			.pCommandBuffers(cmdBuf);
		Vk.check("vkQueueSubmit (custom present)", vkQueueSubmit(device.graphicsQueue(), submit, sync.inFlightFence()));
	}

	private int present(MemoryStack stack, int imageIdx)
	{
		LongBuffer wait = stack.longs(sync.renderFinishedFor(imageIdx));
		LongBuffer chain = stack.longs(swapchain.handle());
		IntBuffer idx = stack.ints(imageIdx);

		VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack)
			.sType$Default()
			.pWaitSemaphores(wait)
			.swapchainCount(1)
			.pSwapchains(chain)
			.pImageIndices(idx);

		return KHRSwapchain.vkQueuePresentKHR(device.graphicsQueue(), present);
	}
}
