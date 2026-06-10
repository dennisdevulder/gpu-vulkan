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

import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Container;
import java.awt.IllegalComponentStateException;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.List;
import java.util.Set;
import javax.swing.SwingUtilities;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.TileObject;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "GPU (Vulkan)",
	description = "Experimental Vulkan-backed GPU renderer",
	tags = {"vulkan", "renderer", "gpu", "experimental"},
	enabledByDefault = false,
	loadInSafeMode = false
)
@Slf4j
public class GpuVulkanPlugin extends Plugin implements DrawCallbacks, VulkanRenderBackend
{
	@Inject
	private GpuVulkanPluginConfig config;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private DrawManager drawManager;

	private Disposables disposables;
	private VulkanInstance instance;
	private VulkanSurface surface;
	private VulkanDevice device;
	private Swapchain swapchain;
	private DepthBuffer depthBuffer;
	private MsaaColorBuffer msaaColor;
	private RenderPass renderPass;
	private RegionManager regionManager;
	private TextureArray textureArray;
	private RenderExtensions renderExtensions;
	private net.runelite.client.plugins.gpuvulkan.gfx.Renderer gfx;
	private net.runelite.rlawt.AWTContext awtContext;
	private Framebuffers framebuffers;
	private FrameSync sync;
	private VulkanRenderer renderer;
	private GpuVulkanDebugOverlayController debugOverlay;
	private Canvas canvas;
	private PlatformSurface platform;
	private int lastDrawCanvasWidth = -1;
	private int lastDrawCanvasHeight = -1;
	private int lastDrawCanvasX = Integer.MIN_VALUE;
	private int lastDrawCanvasY = Integer.MIN_VALUE;
	private javax.swing.Timer macResizeWakeTimer;
	private ComponentAdapter macResizeWakeListener;
	private boolean dispatchingMacResizeWake;
	private long x11Drawable;
	private ClientRuntimeConfig runtimeConfig;
	private final DrawCallbackStats stats = new DrawCallbackStats();
	private volatile double lastCamX, lastCamY, lastCamZ;
	private volatile double lastCamPitch, lastCamYaw;
	private volatile boolean startRequested;
	private boolean sceneFramePrepared;
	private boolean pendingSceneIdentityRecapture;
	private static boolean shutdownHookRegistered;
	private final VulkanExtensionQueue extensionQueue = new VulkanExtensionQueue();

	/** Read by the JVM shutdown hook to find the live instance — must be
	 *  static because the hook outlives any single plugin instance. */
	private static volatile GpuVulkanPlugin activeInstance;
	/** Set by the JVM shutdown hook so in-flight callbacks bail out before
	 *  the disposables stack tears down Vulkan objects. */
	private static volatile boolean shuttingDown;

	@Override
	protected void startUp()
	{
		log.info("Starting GPU (Vulkan)");
		shuttingDown = false;
		runtimeConfig = new ClientRuntimeConfig(client, config);
		// Refuse to coexist with stock GPU — two owners of the rlawt context
		// corrupt JAWT state and crash the JVM on disable.
		if (isStockGpuEnabled())
		{
			log.warn("Stock 'GPU' plugin is enabled — GPU (Vulkan) will not start. Disable 'GPU' first.");
			return;
		}
		if (!isVulkanLoaderAvailable())
		{
			log.warn("No Vulkan driver found — GPU (Vulkan) will not start.");
			return;
		}
		debugOverlay = new GpuVulkanDebugOverlayController(this, config, overlayManager, stats);
		updateDebugOverlayRegistration();
		startRequested = true;
		recordCapturedSceneIdentity(null);
		boolean wantVsync = config.fpsMode() != GpuVulkanPluginConfig.FpsMode.UNCAPPED;
		platform = PlatformSurface.current(wantVsync);
		// macOS owns its CAMetalLayer attach via JAWT_SurfaceLayers in
		// MacOSPlatformSurface, and rlawt's CAOpenGLLayer would conflict.
		final boolean isMac = platform instanceof MacOSPlatformSurface;
		registerShutdownHook();
		clientThread.invoke(() ->
		{
			try
			{
				if (!startRequested)
				{
					return true;
				}
				return initializeRenderer(isMac);
			}
			catch (Throwable e)
			{
				log.error("Vulkan unavailable — GPU (Vulkan) startup failed", e);
				cleanupFailedStartup();
				return true;
			}
		});
	}

	private static boolean isVulkanLoaderAvailable()
	{
		try
		{
			org.lwjgl.vulkan.VK.getInstanceVersionSupported();
			return true;
		}
		catch (Throwable t)
		{
			log.debug("Vulkan loader probe failed", t);
			return false;
		}
	}

	// JVM-lifetime hook: tear Vulkan down before the loader's atexit
	// runs through the validation layer's torn-down state.
	private void registerShutdownHook()
	{
		if (shutdownHookRegistered)
		{
			return;
		}
		shutdownHookRegistered = true;
		Runtime.getRuntime().addShutdownHook(new Thread(() ->
		{
			log.info("JVM shutdown hook fired (clean exit)");
			// Stop the Client thread from queueing more frames. draw()
			// re-checks this flag on every callback.
			shuttingDown = true;
			GpuVulkanPlugin self = activeInstance;
			if (self == null)
			{
				// Plugin.shutDown already ran. Nothing to do.
				return;
			}
			// Best-effort teardown — Client thread may still be running.
			// Wrap each step so one failure can't skip the rest.
			try
			{
				VulkanDevice dev = self.device;
				if (dev != null)
				{
					try
					{
						org.lwjgl.vulkan.VK13.vkDeviceWaitIdle(dev.handle());
					}
					catch (Throwable t)
					{
						log.warn("vkDeviceWaitIdle in shutdown hook: {}", t.getMessage());
					}
				}
				Disposables dis = self.disposables;
				if (dis != null)
				{
					try
					{
						dis.close();
					}
					catch (Throwable t)
					{
						log.warn("disposables.close in shutdown hook: {}", t.getMessage());
					}
				}
			}
			catch (Throwable t)
			{
				log.error("Shutdown hook teardown failed", t);
			}
		}, "vkgpu-shutdown-watch"));
	}

	private boolean initializeRenderer(boolean isMac)
	{
		TextureProvider textureProvider = client.getTextureProvider();
		if (textureProvider == null)
		{
			log.debug("Deferring GPU (Vulkan) startup until the texture provider is available");
			return false;
		}
		// Defer attach until LOGGED_IN — attaching on the login screen
		// leaves a blank canvas that's hard to distinguish from a
		// broken surface, especially on macOS.
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			log.debug("Deferring GPU (Vulkan) startup until game state is LOGGED_IN (currently {})",
				client.getGameState());
			return false;
		}
		canvas = client.getCanvas();
		// X11: AWT's libawt_xawt resize path expects a live GLX
		// context on the canvas's owning thread; without it, sidebar
		// collapse exit_group(1)s the EDT mid-rebuild. We don't use
		// the GL context for rendering, just keep it bound.
		// Windows must not set an OpenGL pixel format on this HWND
		// before Vulkan creates its swapchain.
		if (platform instanceof X11PlatformSurface)
		{
			net.runelite.rlawt.AWTContext.loadNatives();
			synchronized (canvas.getTreeLock())
			{
				if (!canvas.isValid())
				{
					throw new RuntimeException("Canvas not valid at plugin start");
				}
				awtContext = new net.runelite.rlawt.AWTContext(canvas);
				awtContext.configurePixelFormat(0, 0, 0);
			}
			awtContext.createGLContext();
		}
		canvas.setIgnoreRepaint(true);
		canvas.setFocusable(true);
		canvas.requestFocusInWindow();

		disposables = new Disposables();
		// -Dvkgpu.validation=true overrides the stored config so devs
		// don't have to toggle the UI to enable validation.
		boolean validationOn = Boolean.parseBoolean(System.getProperty("vkgpu.validation", "false"))
			|| config.validation();
		instance = new VulkanInstance(validationOn, platform);
		disposables.add(instance);

		surface = new VulkanSurface(instance, platform, canvas);
		disposables.add(surface);
		if (platform instanceof X11PlatformSurface)
		{
			x11Drawable = X11PlatformSurface.currentDrawable(canvas);
		}

		device = new VulkanDevice(instance, surface);
		disposables.add(device);

		// LANDMINE: register FrameSync BEFORE Swapchain. Disposables
		// is LIFO so swapchain destroys first, releasing WSI refs to
		// renderFinished[] semaphores; destroying those semaphores
		// while WSI still holds them hangs AMD/RADV. vkDeviceWaitIdle
		// does not drain the WSI presentation engine.
		sync = new FrameSync(device);
		disposables.add(sync);

		swapchain = new Swapchain(device, surface, canvas.getWidth(), canvas.getHeight(), config.fpsMode());
		disposables.add(swapchain);
		if (isMac)
		{
			MacOSMetalHelper.resizeMetalLayer(canvas);
			installMacResizeWake();
		}
		sync.recreateRenderFinished(swapchain.imageCount());

		int samples = pickMsaaSamples();

		depthBuffer = new DepthBuffer(device, swapchain.width(), swapchain.height(), samples);
		disposables.add(depthBuffer);

		if (samples != org.lwjgl.vulkan.VK13.VK_SAMPLE_COUNT_1_BIT)
		{
			msaaColor = new MsaaColorBuffer(device,
				swapchain.width(), swapchain.height(),
				swapchain.imageFormat(), samples);
			disposables.add(msaaColor);
		}

		renderPass = new RenderPass(device, swapchain.imageFormat(), samples,
			!device.supportsMetalObjects());
		disposables.add(renderPass);

		gfx = Gfx.wrap(device, sync, renderPass, swapchain.imageFormat());
		disposables.add(gfx);

		textureArray = new TextureArray(device, textureProvider,
			config.anisotropicFilteringLevel());
		disposables.add(textureArray);

		regionManager = new RegionManager();

		renderExtensions = new RenderExtensions(
			new DefaultVulkanRenderContext(client, config, gfx, device, sync, renderPass, textureArray, stats));
		renderExtensions.register(new BaseRenderer());
		if (config.upscalingMode() == GpuVulkanPluginConfig.UpscalingMode.FSR1)
		{
			renderExtensions.register(new FsrUpscalerExtension());
		}
		extensionQueue.attachQueued(renderExtensions);
		disposables.add(renderExtensions);

		framebuffers = new Framebuffers(device, renderPass, swapchain, depthBuffer, msaaColor);
		disposables.add(framebuffers);

		renderer = new VulkanRenderer(device, renderPass, renderExtensions,
			swapchain, depthBuffer, msaaColor, framebuffers, sync, stats, config, gfx);
		renderer.setDrawManager(drawManager);
		disposables.add(renderer);

		log.info("Vulkan ready: {} ({}x{}, {} swapchain images)",
			device.deviceName(), swapchain.width(), swapchain.height(), swapchain.imageCount());

		// Publish AFTER full init so the shutdown hook can't observe
		// a half-built instance.
		activeInstance = this;

		client.setDrawCallbacks(this);
		applyClientRuntimeConfig();
		// Re-trigger BufferProvider so the canvas picks up an alpha
		// channel; without it AWT paints opaque over our output.
		client.resizeCanvas();

		// Plugin enable mid-session: capture the already-loaded
		// scene right away rather than waiting for the next chunk
		// crossing.
		Scene currentScene = client.getTopLevelWorldView() == null ? null
			: client.getTopLevelWorldView().getScene();
		if (currentScene != null)
		{
			captureSceneNow(currentScene);
		}
		return true;
	}

	private int pickMsaaSamples()
	{
		int desiredSamples;
		switch (config.antiAliasingMode())
		{
			case MSAA_16: desiredSamples = 16; break;
			case MSAA_8:  desiredSamples = 8;  break;
			case MSAA_4:  desiredSamples = 4;  break;
			case MSAA_2:  desiredSamples = 2;  break;
			case DISABLED:
			default:      desiredSamples = 1;  break;
		}
		int samples = device.pickSampleCount(desiredSamples);
		if (device.supportsMetalObjects() && samples != org.lwjgl.vulkan.VK13.VK_SAMPLE_COUNT_1_BIT)
		{
			log.warn("MSAA is unavailable on macOS, rendering at 1x");
			samples = org.lwjgl.vulkan.VK13.VK_SAMPLE_COUNT_1_BIT;
		}
		return samples;
	}

	private void cleanupFailedStartup()
	{
		removeMacResizeWake();
		// Mirror shutDown's order: drain Vulkan, undo any client-state
		// flips, drop disposables, then unwind AWT/canvas. Without
		// this, a mid-startup throw (device pick, swapchain, etc.)
		// leaves the canvas with ignoreRepaint=true and an AWTContext
		// still attached — JAWT lock then fails on the next plugin
		// enable, looking like "plugin can't toggle back on".
		if (device != null)
		{
			try
			{
				org.lwjgl.vulkan.VK13.vkDeviceWaitIdle(device.handle());
			}
			catch (RuntimeException waitEx)
			{
				log.warn("vkDeviceWaitIdle during startup-recovery: {}", waitEx.getMessage());
			}
		}
		restoreClientRuntimeConfig();
		client.setDrawCallbacks(null);
		client.setGpuFlags(0);
		if (disposables != null)
		{
			disposables.close();
			disposables = null;
			markExtensionBackendDetached();
		}
		if (awtContext != null)
		{
			awtContext.destroy();
			awtContext = null;
		}
		if (canvas != null)
		{
			canvas.setIgnoreRepaint(false);
		}
		if (platform instanceof MacOSPlatformSurface)
		{
			MacOSMetalHelper.detachMetalLayer();
		}
		clearRendererState();
		startRequested = false;
	}

	private void clearRendererState()
	{
		instance = null;
		surface = null;
		device = null;
		swapchain = null;
		depthBuffer = null;
		msaaColor = null;
		renderPass = null;
		regionManager = null;
		textureArray = null;
		renderExtensions = null;
		gfx = null;
		framebuffers = null;
		sync = null;
		renderer = null;
		canvas = null;
		platform = null;
		x11Drawable = 0L;
	}

	@Override
	protected void shutDown()
	{
		log.info("Stopping GPU (Vulkan)");
		startRequested = false;
		shuttingDown = true;
		removeMacResizeWake();
		removeDebugOverlay();
		// Unpublish first so a concurrent shutdown hook sees null and bails,
		// avoiding double-destroy from two threads.
		activeInstance = null;
		clientThread.invoke(() ->
		{
			log.info("GPU (Vulkan) shutdown: detach draw callbacks");
			// Teardown order: stop callbacks → drain GPU → close Disposables
			// → destroy AWTContext → resizeCanvas. resizeCanvas with an
			// attached AWTContext leaves JAWT state stale and the next
			// plugin enable fails JAWT_DrawingSurface_Lock.
			client.setGpuFlags(0);
			client.setDrawCallbacks(null);
			restoreClientRuntimeConfig();

			if (device != null)
			{
				try
				{
					log.info("GPU (Vulkan) shutdown: vkDeviceWaitIdle begin");
					org.lwjgl.vulkan.VK13.vkDeviceWaitIdle(device.handle());
					log.info("GPU (Vulkan) shutdown: vkDeviceWaitIdle end");
				}
				catch (RuntimeException e)
				{
					log.warn("vkDeviceWaitIdle failed during shutdown: {}", e.getMessage());
				}
			}

			if (disposables != null)
			{
				log.info("GPU (Vulkan) shutdown: close disposables begin");
				disposables.close();
				log.info("GPU (Vulkan) shutdown: close disposables end");
				disposables = null;
				markExtensionBackendDetached();
			}

			if (awtContext != null)
			{
				log.info("GPU (Vulkan) shutdown: destroy AWT context begin");
				awtContext.destroy();
				log.info("GPU (Vulkan) shutdown: destroy AWT context end");
				awtContext = null;
			}

			if (platform instanceof MacOSPlatformSurface)
			{
				log.info("GPU (Vulkan) shutdown: detach Metal layer");
				MacOSMetalHelper.detachMetalLayer();
			}
			if (canvas != null)
			{
				log.info("GPU (Vulkan) shutdown: restore canvas repaint");
				canvas.setIgnoreRepaint(false);
			}
			log.info("GPU (Vulkan) shutdown: resize canvas begin");
			client.resizeCanvas();
			log.info("GPU (Vulkan) shutdown: resize canvas end");
			clearRendererState();
			recordCapturedSceneIdentity(null);
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged ev)
	{
		if (!GpuVulkanPluginConfig.GROUP.equals(ev.getGroup())) return;
		if ("debugOverlay".equals(ev.getKey()))
		{
			updateDebugOverlayRegistration();
		}
		if ("removeVertexSnapping".equals(ev.getKey())
			|| "expandedMapLoadingChunks".equals(ev.getKey())
			|| "fpsTarget".equals(ev.getKey()))
		{
			clientThread.invokeLater(this::applyClientRuntimeConfig);
		}
		if (renderExtensions != null)
		{
			renderExtensions.onConfigChanged(ev);
		}
	}

	private void applyClientRuntimeConfig()
	{
		if (runtimeConfig == null)
		{
			runtimeConfig = new ClientRuntimeConfig(client, config);
		}
		runtimeConfig.apply();
	}

	private void restoreClientRuntimeConfig()
	{
		if (runtimeConfig != null)
		{
			runtimeConfig.restoreFpsIfTouched();
		}
	}

	@Subscribe
	public void onGameStateChanged(net.runelite.api.events.GameStateChanged ev)
	{
		// Drop captured scene on logout / hop / connection-lost so the
		// login screen doesn't render with the previous world bleeding
		// through. LOADING is kept (next region is streaming in).
		if (renderExtensions == null) return;
		net.runelite.api.GameState state = ev.getGameState();
		if (state.getState() < net.runelite.api.GameState.LOADING.getState())
		{
			renderExtensions.invalidateCapturedScene();
			recordCapturedSceneIdentity(null);
		}
	}

	private boolean isStockGpuEnabled()
	{
		for (Plugin p : pluginManager.getPlugins())
		{
			PluginDescriptor d = p.getClass().getAnnotation(PluginDescriptor.class);
			if (d != null && "GPU".equals(d.name()))
			{
				return pluginManager.isPluginEnabled(p);
			}
		}
		return false;
	}

	@Override
	public boolean isReady()
	{
		return renderExtensions != null;
	}

	@Override
	public AutoCloseable registerExtension(VulkanRenderExtension extension)
	{
		return extensionQueue.register(extension, renderExtensions);
	}

	private void markExtensionBackendDetached()
	{
		extensionQueue.markBackendDetached();
	}

	// ---- DrawCallbacks --------------------------------------------------

	@Override
	public void draw(int overlayColor)
	{
		if (renderer == null || canvas == null || shuttingDown)
		{
			return;
		}
		if (!canvas.isDisplayable() || !canvas.isValid())
		{
			renderer.markSwapchainStale();
			sceneFramePrepared = false;
			return;
		}
		int w = canvas.getWidth();
		int h = canvas.getHeight();
		ResizeTrace.frame(w + "x" + h);
		if (!ensureNativeSurfaceCurrent(w, h))
		{
			sceneFramePrepared = false;
			return;
		}
		if (renderer.usesCustomPresent())
		{
			updateCustomPresentGeometry(w, h);
		}
		else
		{
			if (w != lastDrawCanvasWidth || h != lastDrawCanvasHeight)
			{
				lastDrawCanvasWidth = w;
				lastDrawCanvasHeight = h;
				renderer.markSwapchainStale();
			}
			if (w != swapchain.width() || h != swapchain.height())
			{
				renderer.markSwapchainStale();
			}
		}
		stats.setDetailedModelStats(config.detailedModelStats());
		BufferProvider bp = client.getBufferProvider();
		// draw() without a scene pass this frame would replay the previous
		// frame's dynamic capture against a rotated slot — reset instead.
		if (!sceneFramePrepared && renderExtensions != null)
		{
			long start = stats.isEnabled() ? System.nanoTime() : 0L;
			renderExtensions.beginFrame();
			stats.addNanos(stats.beginFrameNanos, start);
		}
		try
		{
			renderer.drawFrame(w, h, bp.getPixels(), bp.getWidth(), bp.getHeight(),
				lastCamX, lastCamY, lastCamZ, lastCamPitch, lastCamYaw,
				client.getViewportXOffset(), client.getViewportYOffset(),
				client.getViewportWidth(), client.getViewportHeight(),
				client.getCanvasWidth(), client.getCanvasHeight(),
				client.getScale(),
				client.getSkyboxColor(),
				(float) client.getTextureProvider().getBrightness(),
				config.brightTextures() ? 1f : 0f,
				config.colorBlindMode().ordinal(),
				Math.max(0, Math.min(100, config.colorBlindIntensity())) / 100f,
				config.drawDistance(),
				config.fogDepth(),
				// Modulo prevents tick * anim_speed * (1/128) from accumulating
				// float drift over a long session.
				client.getGameCycle() & 127,
				config.smoothBanding() ? 1f : 0f,
				overlayColor);
		}
		finally
		{
			sceneFramePrepared = false;
		}
		if (debugOverlay != null && debugOverlay.isRegistered())
		{
			updateDebugOverlaySnapshot();
		}
		stats.maybeLog();
	}

	private void updateCustomPresentGeometry(int width, int height)
	{
		int x = Integer.MIN_VALUE;
		int y = Integer.MIN_VALUE;
		try
		{
			Point location = canvas.getLocationOnScreen();
			x = location.x;
			y = location.y;
		}
		catch (IllegalComponentStateException ignored)
		{
			// Canvas is temporarily detached during AWT window changes.
		}

		boolean changed = width != lastDrawCanvasWidth
			|| height != lastDrawCanvasHeight
			|| x != lastDrawCanvasX
			|| y != lastDrawCanvasY;
		if (changed)
		{
			lastDrawCanvasWidth = width;
			lastDrawCanvasHeight = height;
			lastDrawCanvasX = x;
			lastDrawCanvasY = y;
			MacOSMetalHelper.resizeMetalLayerSize(width, height);
			ResizeTrace.mark("plugin.geometry", width + "x" + height + " at " + x + "," + y);
		}
	}

	private void installMacResizeWake()
	{
		if (canvas == null || macResizeWakeListener != null)
		{
			return;
		}

		macResizeWakeListener = new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent e)
			{
				if (!dispatchingMacResizeWake)
				{
					scheduleMacResizeWake();
				}
			}

			@Override
			public void componentMoved(ComponentEvent e)
			{
				if (!dispatchingMacResizeWake)
				{
					scheduleMacResizeWake();
				}
			}
		};
		canvas.addComponentListener(macResizeWakeListener);
	}

	private void removeMacResizeWake()
	{
		if (macResizeWakeTimer != null)
		{
			macResizeWakeTimer.stop();
			macResizeWakeTimer = null;
		}
		if (canvas != null && macResizeWakeListener != null)
		{
			canvas.removeComponentListener(macResizeWakeListener);
		}
		macResizeWakeListener = null;
	}

	private void scheduleMacResizeWake()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (macResizeWakeTimer != null)
			{
				macResizeWakeTimer.restart();
				return;
			}
			macResizeWakeTimer = new javax.swing.Timer(180, ev -> runMacResizeWake());
			macResizeWakeTimer.setRepeats(false);
			macResizeWakeTimer.start();
		});
	}

	private void runMacResizeWake()
	{
		Canvas currentCanvas = canvas;
		if (currentCanvas == null || shuttingDown)
		{
			return;
		}

		ResizeTrace.mark("plugin.macResizeWake", currentCanvas.getWidth() + "x" + currentCanvas.getHeight());
		MacOSMetalHelper.resizeMetalLayer(currentCanvas);
		dispatchingMacResizeWake = true;
		try
		{
			currentCanvas.dispatchEvent(new ComponentEvent(currentCanvas, ComponentEvent.COMPONENT_RESIZED));
			Container parent = currentCanvas.getParent();
			while (parent != null)
			{
				parent.invalidate();
				parent.validate();
				parent.repaint();
				parent = parent.getParent();
			}
			Window window = SwingUtilities.getWindowAncestor(currentCanvas);
			if (window != null)
			{
				window.invalidate();
				window.validate();
				window.repaint();
			}
			currentCanvas.repaint();
		}
		finally
		{
			dispatchingMacResizeWake = false;
		}

		clientThread.invokeLater(() ->
		{
			if (canvas != null && !shuttingDown)
			{
				client.resizeCanvas();
			}
		});
	}

	private boolean ensureNativeSurfaceCurrent(int width, int height)
	{
		if (!(platform instanceof X11PlatformSurface) || surface == null || swapchain == null)
		{
			return true;
		}

		long currentDrawable = X11PlatformSurface.currentDrawable(canvas);
		if (currentDrawable == 0L || currentDrawable == x11Drawable)
		{
			return true;
		}

		log.warn("X11 canvas drawable changed 0x{} -> 0x{}; recreating Vulkan surface",
			Long.toHexString(x11Drawable), Long.toHexString(currentDrawable));
		recreateNativeSurface(width, height);
		x11Drawable = X11PlatformSurface.currentDrawable(canvas);
		lastDrawCanvasWidth = width;
		lastDrawCanvasHeight = height;
		return false;
	}

	private void recreateNativeSurface(int width, int height)
	{
		org.lwjgl.vulkan.VK13.vkDeviceWaitIdle(device.handle());
		framebuffers.destroyAll();
		swapchain.close();
		surface.recreate(canvas);
		swapchain.recreate(width, height);
		depthBuffer.recreate(swapchain.width(), swapchain.height());
		if (msaaColor != null)
		{
			msaaColor.recreate(swapchain.width(), swapchain.height());
		}
		framebuffers.recreate(renderPass, swapchain, depthBuffer, msaaColor);
		sync.recreateRenderFinished(swapchain.imageCount());
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane)
	{
		boolean statsEnabled = stats.isEnabled();
		if (statsEnabled)
		{
			stats.drawScene.incrementAndGet();
			stats.lastCamX = cameraX;
			stats.lastCamY = cameraY;
			stats.lastCamZ = cameraZ;
			stats.lastCamPlane = plane;
		}
		// Camera doubles here come from a different reference frame than
		// preSceneDraw's floats — we use preSceneDraw's exclusively for the
		// projection MVP, so don't overwrite them here.
		if (renderExtensions != null)
		{
			long start = statsEnabled ? System.nanoTime() : 0L;
			renderExtensions.beginFrame();
			sceneFramePrepared = true;
			stats.addNanos(stats.beginFrameNanos, start);
			start = statsEnabled ? System.nanoTime() : 0L;
			Scene sceneForFrame = currentScene != null ? currentScene : capturedScene;
			if (pendingSceneIdentityRecapture && sceneForFrame != null)
			{
				pendingSceneIdentityRecapture = false;
				captureSceneNow(sceneForFrame);
			}
			renderExtensions.rebuildDirtyZones(sceneForFrame);
			stats.addNanos(stats.sceneCaptureNanos, start);
			start = statsEnabled ? System.nanoTime() : 0L;
			renderExtensions.captureDynamicPending();
			stats.addNanos(stats.pendingCaptureNanos, start);
		}
	}

	/** Identity of the scene captured into render extensions. swapScene /
	 *  loadScene callbacks are unreliable in this engine version, so we
	 *  detect scene transitions by reference plus map identity. The engine
	 *  can reuse the same Scene object across boundary shifts while changing
	 *  its base coordinates; reference-only detection leaves stale static
	 *  terrain until the plugin is restarted. */
	private Scene capturedScene;
	private int capturedSceneBaseX = Integer.MIN_VALUE;
	private int capturedSceneBaseY = Integer.MIN_VALUE;
	private int capturedSceneWorldViewId = Integer.MIN_VALUE;
	private boolean capturedSceneInstance;
	private Scene currentScene;

	@Override
	public void preSceneDraw(Scene scene, Projection projection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		if (stats.isEnabled())
		{
			stats.preSceneDraw.incrementAndGet();
		}
		if (!isTopLevelScene(scene))
		{
			return;
		}
		// Scene lifecycle fallback: swapScene/loadScene callbacks don't fire
		// reliably in this engine version. Re-capture when either the Scene
		// object changes or the same object is reused with a different map
		// identity after a boundary shift.
		if (renderExtensions != null && sceneIdentityChanged(scene))
		{
			pendingSceneIdentityRecapture = false;
			captureSceneNow(scene);
		}
		currentScene = scene;
		if (renderExtensions != null)
		{
			renderExtensions.setLevelRange(minLevel, level, maxLevel);
		}
		lastCamX = cameraX;
		lastCamY = cameraY;
		lastCamZ = cameraZ;
		lastCamPitch = cameraPitch;
		lastCamYaw = cameraYaw;
		// Drives the engine's per-entity clickbox loops; without it only
		// tiles within the engine's tiny default range register clicks.
		scene.setDrawDistance(config.drawDistance());
		if (renderExtensions != null) renderExtensions.setHideRoofIds(hideRoofIds);
	}

	@Override
	public void postDrawScene()
	{
		if (stats.isEnabled())
		{
			stats.postDrawScene.incrementAndGet();
		}
	}

	@Override
	public void swapScene(Scene scene)
	{
		if (stats.isEnabled())
		{
			stats.swapScene.incrementAndGet();
		}
		if (!isTopLevelScene(scene))
		{
			return;
		}
		currentScene = scene;
		if (renderExtensions != null && sceneIdentityChanged(scene))
		{
			captureSceneNow(scene);
		}
	}

	private void prepareScene(Scene scene)
	{
		// Region stripping mutates the live Scene via removeTile(). That is too
		// strong an operation for this renderer: if the reference chunk is wrong
		// for an overlapping/edge map, the first full capture after plugin start
		// can permanently remove the active scene. Keep capture side-effect free.
	}

	private void captureSceneNow(Scene scene)
	{
		if (renderExtensions == null) return;
		if (!isTopLevelScene(scene))
		{
			return;
		}
		if (!sceneIdentityChanged(scene))
		{
			return;
		}
		prepareScene(scene);
		long start = stats.isEnabled() ? System.nanoTime() : 0L;
		log.info("Vulkan recapture: base=({}, {}) worldView={} instance={}",
			scene.getBaseX(), scene.getBaseY(), scene.getWorldViewId(), scene.isInstance());
		renderExtensions.captureScene(scene);
		stats.addNanos(stats.sceneCaptureNanos, start);
		recordCapturedSceneIdentity(scene);
	}

	private boolean sceneIdentityChanged(Scene scene)
	{
		if (!isTopLevelScene(scene))
		{
			return false;
		}
		return scene != capturedScene
			|| scene.getBaseX() != capturedSceneBaseX
			|| scene.getBaseY() != capturedSceneBaseY
			|| scene.getWorldViewId() != capturedSceneWorldViewId
			|| scene.isInstance() != capturedSceneInstance;
	}

	private void recordCapturedSceneIdentity(Scene scene)
	{
		if (scene == null)
		{
			capturedScene = null;
			capturedSceneBaseX = Integer.MIN_VALUE;
			capturedSceneBaseY = Integer.MIN_VALUE;
			capturedSceneWorldViewId = Integer.MIN_VALUE;
			capturedSceneInstance = false;
			return;
		}
		capturedScene = scene;
		capturedSceneBaseX = scene.getBaseX();
		capturedSceneBaseY = scene.getBaseY();
		capturedSceneWorldViewId = scene.getWorldViewId();
		capturedSceneInstance = scene.isInstance();
	}

	List<String> debugOverlayLines()
	{
		return debugOverlay == null ? List.of("GPU Vulkan", "status: starting") : debugOverlay.lines();
	}

	private void updateDebugOverlaySnapshot()
	{
		if (debugOverlay != null)
		{
			debugOverlay.updateSnapshot(device, swapchain, renderExtensions);
		}
	}

	private void updateDebugOverlayRegistration()
	{
		if (debugOverlay != null)
		{
			debugOverlay.updateRegistration();
		}
	}

	private void removeDebugOverlay()
	{
		if (debugOverlay != null)
		{
			debugOverlay.remove();
		}
	}

	@Override
	public void loadScene(Scene scene)
	{
		if (stats.isEnabled())
		{
			stats.loadScene.incrementAndGet();
		}
	}

	// Wired up but unused — engine doesn't fire either loadScene overload
	// in this version; preSceneDraw's reference check is the live signal.
	@Override
	public void loadScene(net.runelite.api.WorldView worldView, Scene scene)
	{
		if (stats.isEnabled())
		{
			stats.loadScene.incrementAndGet();
		}
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		if (scene != null && !isTopLevelScene(scene))
		{
			return;
		}
		if (scene != null)
		{
			currentScene = scene;
			if (sceneIdentityChanged(scene))
			{
				pendingSceneIdentityRecapture = true;
			}
		}
		if (renderExtensions != null)
		{
			renderExtensions.invalidateZone(scene, zx, zz);
		}
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileZ)
	{
		if (stats.isEnabled())
		{
			stats.drawScenePaint.incrementAndGet();
		}
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileZ)
	{
		if (stats.isEnabled())
		{
			stats.drawSceneTileModel.incrementAndGet();
		}
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		if (stats.isEnabled())
		{
			stats.drawZoneOpaque.incrementAndGet();
		}
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		if (stats.isEnabled())
		{
			stats.drawZoneAlpha.incrementAndGet();
		}
	}

	@Override
	public void drawDynamic(Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m, int orient, int x, int y, int z)
	{
		if (stats.isEnabled())
		{
			stats.drawDynamic.incrementAndGet();
			stats.recordModel(m);
		}
		if (!isTopLevelScene(scene))
		{
			return;
		}
		boolean actorModel = r instanceof net.runelite.api.Actor || tileObject == null;
		if (renderExtensions != null)
		{
			long start = stats.isEnabled() ? System.nanoTime() : 0L;
			renderExtensions.captureModel(worldProjection, m, orient, x, y, z,
				renderModeOf(r), actorModel);
			stats.addNanos(stats.dynamicCaptureNanos, start);
		}
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		if (stats.isEnabled())
		{
			stats.drawTemp.incrementAndGet();
			stats.recordModel(m);
		}
		if (!isTopLevelScene(scene))
		{
			return;
		}
		if (gameObject == null)
		{
			return;
		}
		if (renderExtensions != null)
		{
			long start = stats.isEnabled() ? System.nanoTime() : 0L;
			renderExtensions.captureModel(worldProjection, m, orient, x, y, z,
				renderModeOf(gameObject.getRenderable()), false);
			stats.addNanos(stats.tempCaptureNanos, start);
		}
	}

	private static int renderModeOf(Renderable renderable)
	{
		return renderable != null ? renderable.getRenderMode() : Renderable.RENDERMODE_DEFAULT;
	}

	private static boolean isTopLevelScene(Scene scene)
	{
		return scene != null && scene.getWorldViewId() == net.runelite.api.WorldView.TOPLEVEL;
	}

	@Override
	public void drawPass(Projection entityProjection, Scene scene, int pass)
	{
		if (stats.isEnabled())
		{
			stats.drawPass.incrementAndGet();
		}
		if (!isTopLevelScene(scene))
		{
			return;
		}
		if (renderExtensions != null)
		{
			renderExtensions.drawPass(pass);
		}
	}

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		if (stats.isEnabled())
		{
			stats.drawSingle.incrementAndGet();
			if (renderable instanceof Model) stats.recordModel((Model) renderable);
		}
		// Don't delete the rest of this method. Projectiles, spell
		// animations and the home-teleport graphic stop rendering without
		// it — even though the stats counter above says this method never
		// runs. We don't fully understand why yet; see issue #1.
		if (renderExtensions == null || !isTopLevelScene(scene)) return;
		if (renderable instanceof net.runelite.api.Actor) return;
		Model m = (renderable instanceof Model) ? (Model) renderable : renderable.getModel();
		if (m == null) return;
		long start = stats.isEnabled() ? System.nanoTime() : 0L;
		renderExtensions.captureModel(projection, m, orientation, x, y, z, renderModeOf(renderable), false);
		stats.addNanos(stats.singleCaptureNanos, start);
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		if (stats.isEnabled())
		{
			stats.animate.incrementAndGet();
		}
	}

	// ---- Guice ----------------------------------------------------------

	@Provides
	GpuVulkanPluginConfig provideConfig(ConfigManager cm)
	{
		return cm.getConfig(GpuVulkanPluginConfig.class);
	}

	@Provides
	VulkanRenderBackend provideVulkanRenderBackend()
	{
		return this;
	}
}
