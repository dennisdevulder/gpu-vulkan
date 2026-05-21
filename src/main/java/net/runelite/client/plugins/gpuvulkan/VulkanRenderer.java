package net.runelite.client.plugins.gpuvulkan;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
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

	private final Swapchain swapchain;
	private final DepthBuffer depthBuffer;
	private final MsaaColorBuffer msaaColor; // null when MSAA is disabled
	private final Framebuffers framebuffers;
	/** macOS-only — custom-present path. Null on Linux (we use {@link #swapchain}). */
	private final MetalDrawableSet metalDrawables;
	/** Convenience flag; mirrors {@code metalDrawables != null}. */
	private final boolean useCustomPresent;
	/** Width/height tracked for the custom-present path so we can detect a
	 *  resize and rebuild depth/MSAA buffers + invalidate the drawable cache. */
	private int customPresentWidth, customPresentHeight;

	private final long commandPool;
	private final VkCommandBuffer[] commandBuffers;

	private boolean swapchainNeedsRebuild;
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
				   Framebuffers framebuffers, FrameSync sync)
	{
		this.device = device;
		this.renderPass = renderPass;
		this.renderExtensions = renderExtensions;
		this.swapchain = swapchain;
		this.depthBuffer = depthBuffer;
		this.msaaColor = msaaColor;
		this.framebuffers = framebuffers;
		this.sync = sync;
		// Keep macOS on the normal Vulkan swapchain path. The experimental
		// imported-CAMetalDrawable custom-present path crashes inside MoltenVK
		// on vkQueueSubmit with recent runtimes.
		this.useCustomPresent = false;
		this.metalDrawables = null;
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
				.commandBufferCount(FrameSync.FRAMES_IN_FLIGHT);
			PointerBuffer pBufs = stack.mallocPointer(FrameSync.FRAMES_IN_FLIGHT);
			Vk.check("vkAllocateCommandBuffers", vkAllocateCommandBuffers(device.handle(), allocInfo, pBufs));
			commandBuffers = new VkCommandBuffer[FrameSync.FRAMES_IN_FLIGHT];
			for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
			{
				commandBuffers[i] = new VkCommandBuffer(pBufs.get(i), device.handle());
			}
		}
	}

	void markSwapchainStale()
	{
		swapchainNeedsRebuild = true;
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
		// Lazy rebuild: only rebuild when the swap-chain itself reports it's
		// out-of-date (via SUBOPTIMAL/OUT_OF_DATE from acquire or present).
		// Eager size-check rebuilds caused worse problems: sidebar collapse
		// fires 12+ resize events in milliseconds (958x916 ↔ 1437x1374) and
		// eager rebuild would tear down + recreate the swapchain on each one,
		// hammering RADV faster than it can handle and de-syncing the engine
		// (observed paint=0 in stats after a resize storm). Waiting for
		// SUBOPTIMAL naturally debounces — we render one frame to the slightly-
		// stale swapchain and rebuild once after the resize cascade settles.
		if (swapchainNeedsRebuild)
		{
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

		try (MemoryStack stack = stackPush())
		{
			// Wait BEFORE writing this slot's UI staging buffer — otherwise the
			// CPU memcpy in uploadPixels can race the GPU's vkCmdCopyBufferToImage
			// from FRAMES_IN_FLIGHT frames ago, producing the half-old, half-new
			// UI texture that reads as the UI/scene "layer" intermittently
			// covering and uncovering.
			vkWaitForFences(device.handle(), sync.inFlightFence(), true, Long.MAX_VALUE);

			// CPU-side memcpy into the persistently-mapped staging buffer. Done
			// before recording the command buffer so the GPU read sees it.
			renderExtensions.uploadUiPixels(uiPixels, uiWidth, uiHeight);

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
	}

	/**
	 * Linux path: standard {@code KHRSwapchain.vkAcquireNextImageKHR}
	 * + render + {@code vkQueuePresentKHR}.
	 */
	private void drawFrameSwapchain(MemoryStack stack, int desiredWidth, int desiredHeight)
	{
		IntBuffer pImageIdx = stack.mallocInt(1);
		int acq = KHRSwapchain.vkAcquireNextImageKHR(device.handle(), swapchain.handle(),
			Long.MAX_VALUE, sync.imageAvailable(), VK_NULL_HANDLE, pImageIdx);

		if (acq == VK_ERROR_OUT_OF_DATE_KHR)
		{
			rebuildSwapchain(desiredWidth, desiredHeight);
			return;
		}
		if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR)
		{
			throw new RuntimeException("vkAcquireNextImageKHR failed: " + acq);
		}
		if (acq == VK_SUBOPTIMAL_KHR)
		{
			swapchainNeedsRebuild = true;
		}
		int imageIdx = pImageIdx.get(0);

		vkResetFences(device.handle(), sync.inFlightFence());

		VkCommandBuffer cmd = commandBuffers[sync.currentFrame()];
		vkResetCommandBuffer(cmd, 0);
		recordClearPass(stack, cmd, framebuffers.get(imageIdx),
			swapchain.width(), swapchain.height());

		submit(stack, cmd, imageIdx);
		int present = present(stack, imageIdx);
		if (present == VK_ERROR_OUT_OF_DATE_KHR || present == VK_SUBOPTIMAL_KHR)
		{
			swapchainNeedsRebuild = true;
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
	 * <p>Ordering: wait for the Vulkan submit fence before scheduling the
	 * Metal present. MoltenVK exposes the underlying Metal queue, but relying
	 * on implicit ordering between MoltenVK's internal command buffer commit
	 * and our own Metal command buffer has proven crash-prone on macOS.
	 */
	private void drawFrameCustomPresent(MemoryStack stack, int desiredWidth, int desiredHeight)
	{
		long[] d = MacOSMetalHelper.nextDrawable();
		if (d == null)
		{
			MacOSMetalHelper.resizeMetalLayerSize(desiredWidth, desiredHeight);
			d = MacOSMetalHelper.nextDrawable();
			if (d == null)
			{
				// CAMetalLayer didn't hand us a drawable inside its internal
				// timeout. Treat as a dropped frame.
				return;
			}
		}
		long drawable = d[0];
		long mtlTexture = d[1];
		int width = (int) d[2];
		int height = (int) d[3];

		// Drawable size changed (canvas resize). Rebuild depth/MSAA
		// buffers and drop cached drawable entries — they reference the
		// old depth/MSAA views and would be stale.
		if (width != customPresentWidth || height != customPresentHeight)
		{
			vkDeviceWaitIdle(device.handle());
			metalDrawables.flush();
			depthBuffer.recreate(width, height);
			if (msaaColor != null)
			{
				msaaColor.recreate(width, height);
			}
			customPresentWidth = width;
			customPresentHeight = height;
		}

		MetalDrawableSet.Entry entry = metalDrawables.acquire(
			mtlTexture, width, height, renderPass, depthBuffer, msaaColor);

		vkResetFences(device.handle(), sync.inFlightFence());

		VkCommandBuffer cmd = commandBuffers[sync.currentFrame()];
		vkResetCommandBuffer(cmd, 0);
		recordClearPass(stack, cmd, entry.framebuffer, width, height);

		submitNoSemaphores(stack, cmd);
		Vk.check("vkWaitForFences (custom present)",
			vkWaitForFences(device.handle(), sync.inFlightFence(), true, Long.MAX_VALUE));

		MacOSMetalHelper.presentDrawable(drawable, device.metalCommandQueue());
	}

	@Override
	public void close()
	{
		vkDeviceWaitIdle(device.handle());
		if (metalDrawables != null)
		{
			metalDrawables.close();
		}
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
		vkDeviceWaitIdle(device.handle());
		framebuffers.destroyAll();
		swapchain.recreate(desiredWidth, desiredHeight);
		depthBuffer.recreate(swapchain.width(), swapchain.height());
		if (msaaColor != null)
		{
			msaaColor.recreate(swapchain.width(), swapchain.height());
		}
		framebuffers.recreate(renderPass, swapchain, depthBuffer, msaaColor);
		sync.recreateRenderFinished(swapchain.imageCount());
		swapchainNeedsRebuild = false;
		log.debug("rebuildSwapchain -> {}x{}", swapchain.width(), swapchain.height());
	}

	private void recordClearPass(MemoryStack stack, VkCommandBuffer cmd,
								 long framebuffer, int targetWidth, int targetHeight)
	{
		VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
			.sType$Default()
			.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
		Vk.check("vkBeginCommandBuffer", vkBeginCommandBuffer(cmd, begin));

		// UI texture upload + transitions happen OUTSIDE the render pass —
		// vkCmdCopyBufferToImage isn't allowed inside one.
		renderExtensions.recordBeforeRenderPass(cmd);

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
			(float) cameraX, (float) cameraZ, brightness,
			drawDistanceTiles, fogDepthTiles,
			((skyboxColor >> 16) & 0xFF) / 255f,
			((skyboxColor >>  8) & 0xFF) / 255f,
			( skyboxColor        & 0xFF) / 255f,
			gameTick, textureLightMode,
			colorBlindMode, colorBlindIntensity,
			smoothBanding, overlayColor);
		renderExtensions.recordRenderPass(frame);

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

		Vk.check("vkEndCommandBuffer", vkEndCommandBuffer(cmd));
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
	private void submitNoSemaphores(MemoryStack stack, VkCommandBuffer cmd)
	{
		PointerBuffer cmdBuf = stack.pointers(cmd);
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
