package net.runelite.client.plugins.gpuvulkan;

import com.google.inject.Provides;
import java.awt.Canvas;
import java.util.ArrayList;
import java.util.List;
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
import java.util.Set;
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
	description = "Vulkan-backed renderer (alternative to the OpenGL GPU plugin)",
	tags = {"vulkan", "renderer", "gpu"},
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
	private GpuVulkanDebugOverlay debugOverlay;
	private boolean debugOverlayRegistered;
	private volatile List<String> debugOverlaySnapshot = List.of("GPU Vulkan", "status: starting");
	private Canvas canvas;
	private PlatformSurface platform;
	/** Tracks whether startup actually flipped {@code setUnlockedFps(true)},
	 *  so shutdown doesn't clobber a user-driven unlock. */
	private boolean fpsTouched;
	private final DrawCallbackStats stats = new DrawCallbackStats();
	private volatile double lastCamX, lastCamY, lastCamZ;
	private volatile double lastCamPitch, lastCamYaw;
	private volatile boolean startRequested;
	private static boolean shutdownHookRegistered;
	private final List<ExtensionRegistration> queuedExtensions = new ArrayList<>();

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
		// Refuse to coexist with stock GPU — two owners of the rlawt context
		// corrupt JAWT state and crash the JVM on disable.
		if (isStockGpuEnabled())
		{
			log.warn("Stock 'GPU' plugin is enabled — GPU (Vulkan) will not start. Disable 'GPU' first.");
			return;
		}
		if (debugOverlay == null)
		{
			debugOverlay = new GpuVulkanDebugOverlay(this, config);
		}
		updateDebugOverlayRegistration();
		startRequested = true;
		boolean wantVsync = config.fpsMode() != GpuVulkanPluginConfig.FpsMode.UNCAPPED;
		platform = PlatformSurface.current(wantVsync);
		// macOS owns its CAMetalLayer attach via JAWT_SurfaceLayers in
		// MacOSPlatformSurface, and rlawt's CAOpenGLLayer would conflict.
		final boolean isMac = platform instanceof MacOSPlatformSurface;
		// JVM-lifetime hook: tear Vulkan down before the loader's atexit
		// runs through the validation layer's torn-down state.
		if (!shutdownHookRegistered)
		{
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
		clientThread.invoke(() ->
		{
			try
			{
				if (!startRequested)
				{
					return true;
				}
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
				// Skipped on macOS — we attach our own Metal layer there.
				if (!isMac)
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
				}
				sync.recreateRenderFinished(swapchain.imageCount());

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

				depthBuffer = new DepthBuffer(device, swapchain.width(), swapchain.height(), samples);
				disposables.add(depthBuffer);

				if (samples != org.lwjgl.vulkan.VK13.VK_SAMPLE_COUNT_1_BIT)
				{
					msaaColor = new MsaaColorBuffer(device,
						swapchain.width(), swapchain.height(),
						swapchain.imageFormat(), samples);
					disposables.add(msaaColor);
				}

				renderPass = new RenderPass(device, swapchain.imageFormat(), samples);
				disposables.add(renderPass);

				gfx = Gfx.wrap(device, sync, renderPass);
				disposables.add(gfx);

				textureArray = new TextureArray(device, textureProvider,
					config.anisotropicFilteringLevel());
				disposables.add(textureArray);

				regionManager = new RegionManager();

				renderExtensions = new RenderExtensions(
					new DefaultVulkanRenderContext(client, config, gfx, device, sync, renderPass, textureArray, stats));
				renderExtensions.register(new BaseRenderer());
				synchronized (queuedExtensions)
				{
					for (ExtensionRegistration registration : queuedExtensions)
					{
						registration.attach(renderExtensions);
					}
				}
				disposables.add(renderExtensions);

				framebuffers = new Framebuffers(device, renderPass, swapchain, depthBuffer, msaaColor);
				disposables.add(framebuffers);

				renderer = new VulkanRenderer(device, renderPass, renderExtensions,
					swapchain, depthBuffer, msaaColor, framebuffers, sync, stats);
				renderer.setDrawManager(drawManager);
				disposables.add(renderer);

				log.info("Vulkan ready: {} ({}x{}, {} swapchain images)",
					device.deviceName(), swapchain.width(), swapchain.height(), swapchain.imageCount());

				// Publish AFTER full init so the shutdown hook can't observe
				// a half-built instance.
				activeInstance = this;

				client.setDrawCallbacks(this);
				// ZBUF is what makes the engine traverse zones and fire
				// drawZoneOpaque / drawDynamic; without it we only get
				// drawScene / postDrawScene boundary markers.
				int gpuFlags = DrawCallbacks.GPU | DrawCallbacks.ZBUF;
				if (config.removeVertexSnapping()) gpuFlags |= DrawCallbacks.NO_VERTEX_SNAPPING;
				client.setGpuFlags(gpuFlags);
				client.setExpandedMapLoading(config.expandedMapLoadingChunks());
				// Re-trigger BufferProvider so the canvas picks up an alpha
				// channel; without it AWT paints opaque over our output.
				client.resizeCanvas();

				// Only call setUnlockedFps when we're actually opting in —
				// never preemptively false, since that overrides a user-driven
				// unlock from elsewhere. fpsTouched gates the shutdown undo.
				int fpsTarget = Math.max(0, config.fpsTarget());
				boolean unlockEngine =
					config.fpsMode() == GpuVulkanPluginConfig.FpsMode.UNCAPPED
					|| fpsTarget > 0;
				if (unlockEngine)
				{
					client.setUnlockedFps(true);
					client.setUnlockedFpsTarget(fpsTarget);
					fpsTouched = true;
				}

				// Plugin enable mid-session: capture the already-loaded
				// scene right away rather than waiting for the next chunk
				// crossing.
				Scene currentScene = client.getTopLevelWorldView() == null ? null
					: client.getTopLevelWorldView().getScene();
				if (currentScene != null)
				{
					captureSceneNow(currentScene);
					capturedScene = currentScene;
				}
			}
			catch (RuntimeException e)
			{
				log.error("GPU (Vulkan) startup failed", e);
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
				if (fpsTouched)
				{
					client.setUnlockedFps(false);
					fpsTouched = false;
				}
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
				throw e;
			}
			return true;
		});
	}

	@Override
	protected void shutDown()
	{
		log.info("Stopping GPU (Vulkan)");
		startRequested = false;
		shuttingDown = true;
		if (debugOverlay != null)
		{
			removeDebugOverlay();
		}
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
			if (fpsTouched)
			{
				client.setUnlockedFps(false);
				fpsTouched = false;
			}

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
			instance = null;
			surface = null;
			device = null;
			swapchain = null;
			depthBuffer = null;
			msaaColor = null;
			renderPass = null;
			textureArray = null;
			renderExtensions = null;
			gfx = null;
			framebuffers = null;
			sync = null;
			renderer = null;
			canvas = null;
			platform = null;
			capturedScene = null;
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
		if (renderExtensions != null)
		{
			renderExtensions.onConfigChanged(ev);
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
			capturedScene = null;
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
		ExtensionRegistration registration = new ExtensionRegistration(extension);
		synchronized (queuedExtensions)
		{
			queuedExtensions.add(registration);
			if (renderExtensions != null)
			{
				registration.attach(renderExtensions);
			}
		}
		return registration;
	}

	private final class ExtensionRegistration implements AutoCloseable
	{
		private final VulkanRenderExtension extension;
		private RenderExtensions attachedRegistry;
		private boolean closed;
		private boolean closedByBackend;

		private ExtensionRegistration(VulkanRenderExtension extension)
		{
			this.extension = extension;
		}

		private void attach(RenderExtensions registry)
		{
			if (closed || attachedRegistry == registry)
			{
				return;
			}
			registry.register(extension);
			attachedRegistry = registry;
			closedByBackend = false;
		}

		private void markBackendDetached()
		{
			attachedRegistry = null;
			closedByBackend = true;
		}

		@Override
		public void close()
		{
			synchronized (queuedExtensions)
			{
				if (closed)
				{
					return;
				}
				closed = true;
				queuedExtensions.remove(this);
				if (attachedRegistry != null)
				{
					attachedRegistry.unregister(extension);
					attachedRegistry = null;
				}
				else if (!closedByBackend)
				{
					extension.close();
				}
			}
		}
	}

	private void markExtensionBackendDetached()
	{
		synchronized (queuedExtensions)
		{
			for (ExtensionRegistration registration : queuedExtensions)
			{
				registration.markBackendDetached();
			}
		}
	}

	// ---- DrawCallbacks --------------------------------------------------

	@Override
	public void draw(int overlayColor)
	{
		if (renderer == null || canvas == null || shuttingDown)
		{
			return;
		}
		int w = canvas.getWidth();
		int h = canvas.getHeight();
		if (w != swapchain.width() || h != swapchain.height())
		{
			renderer.markSwapchainStale();
		}
		stats.setDetailedModelStats(config.detailedModelStats());
		BufferProvider bp = client.getBufferProvider();
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
		if (debugOverlayRegistered)
		{
			updateDebugOverlaySnapshot();
		}
		stats.maybeLog();
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
			stats.addNanos(stats.beginFrameNanos, start);
			start = statsEnabled ? System.nanoTime() : 0L;
			renderExtensions.rebuildDirtyZones(capturedScene);
			stats.addNanos(stats.sceneCaptureNanos, start);
			start = statsEnabled ? System.nanoTime() : 0L;
			renderExtensions.captureDynamicPending();
			stats.addNanos(stats.pendingCaptureNanos, start);
		}
	}

	/** Identity of the scene captured into render extensions. swapScene /
	 *  loadScene callbacks are unreliable in this engine version, so we
	 *  detect scene transitions by reference comparison instead. */
	private Scene capturedScene;

	@Override
	public void preSceneDraw(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		if (stats.isEnabled())
		{
			stats.preSceneDraw.incrementAndGet();
		}
		// Scene-reference fallback: swapScene/loadScene callbacks don't fire
		// in this engine version (recon shows swap=0 load=0), so the only
		// reliable signal that the scene has been swapped is the Scene object
		// reference itself changing between frames. Re-capture on identity
		// change so geometry stays fresh after chunk transitions.
		if (scene != capturedScene && renderExtensions != null)
		{
			capturedScene = scene;
			captureSceneNow(scene);
		}
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
		// Belt-and-braces. The primary trigger is the reference check in
		// preSceneDraw (engine doesn't reliably call swapScene), but if it
		// does, preSceneDraw's check will see scene == capturedScene and
		// won't double-capture.
		if (renderExtensions != null)
		{
			capturedScene = scene;
			captureSceneNow(scene);
		}
	}

	private void prepareScene(Scene scene)
	{
		if (regionManager != null)
		{
			regionManager.prepare(scene, config.hideUnrelatedMaps());
		}
	}

	private void captureSceneNow(Scene scene)
	{
		if (renderExtensions == null) return;
		prepareScene(scene);
		long start = stats.isEnabled() ? System.nanoTime() : 0L;
		renderExtensions.captureScene(scene);
		stats.addNanos(stats.sceneCaptureNanos, start);
	}

	List<String> debugOverlayLines()
	{
		return debugOverlaySnapshot;
	}

	private void updateDebugOverlaySnapshot()
	{
		ArrayList<String> lines = new ArrayList<>(16);
		Runtime rt = Runtime.getRuntime();
		long heapUsed = rt.totalMemory() - rt.freeMemory();
		long heapMax = rt.maxMemory();
		GpuVulkanDebugMetrics metrics = new GpuVulkanDebugMetrics();
		if (renderExtensions != null)
		{
			renderExtensions.collectDebugMetrics(metrics);
		}

		lines.add("GPU Vulkan");
		lines.add("device: " + compactDeviceName(device == null ? "not ready" : device.deviceName()));
		lines.add("swap: " + (swapchain == null ? "-" :
			swapchain.width() + "x" + swapchain.height() + " x" + swapchain.imageCount()));
		lines.add("heap: " + mib(heapUsed) + " / " + mib(heapMax) + " MiB");
		lines.add("scene buf: " + mib(metrics.sceneBufferBytes) + " MiB native");
		lines.add("verts: " + compactCount(metrics.totalVertices) + " / " + compactCount(metrics.maxVertices));
		lines.add("static: " + compactCount(metrics.sceneVertices));
		lines.add("roofs: " + metrics.roofRanges);
		lines.add("dirty zones: " + metrics.dirtyZones);
		lines.add("pending: " + metrics.pendingRenderables);
		lines.add("model cache: " + compactCount(metrics.modelCacheEntries)
			+ " h/m " + compactCount(metrics.modelCacheHits) + "/" + compactCount(metrics.modelCacheMisses));
		lines.add("model mesh: " + mib(metrics.modelMeshBytes) + " / "
			+ mib(metrics.modelMeshCapacityBytes) + " MiB");
		lines.add("model inst: " + compactCount(metrics.modelInstances) + " / "
			+ compactCount(metrics.modelInstanceMax) + " o=" + metrics.modelInstanceOverflows);
		lines.add("overflow: " + (metrics.overflowed ? "yes" : "no"));
		lines.add("scene/pre/post: " + stats.drawSceneCount() + " / "
			+ stats.preSceneDrawCount() + " / " + stats.postDrawSceneCount());
		lines.add("dyn calls: " + stats.drawDynamicCount());
		lines.add("dyn temp/pass: " + stats.drawTempCount() + " / " + stats.drawPassCount());
		lines.add("dyn single: " + stats.drawSingleCount());
		lines.add("dyn faces: " + compactCount(stats.totalDynamicFacesCount()));
		lines.add("dyn max: " + stats.maxDynamicFacesCount());
		debugOverlaySnapshot = List.copyOf(lines);
	}

	private void updateDebugOverlayRegistration()
	{
		if (debugOverlay == null)
		{
			return;
		}
		if (config.debugOverlay())
		{
			if (!debugOverlayRegistered)
			{
				overlayManager.add(debugOverlay);
				debugOverlayRegistered = true;
				stats.setOverlayStatsEnabled(true);
			}
		}
		else
		{
			removeDebugOverlay();
		}
	}

	private void removeDebugOverlay()
	{
		if (debugOverlayRegistered)
		{
			overlayManager.remove(debugOverlay);
			debugOverlayRegistered = false;
			stats.setOverlayStatsEnabled(false);
		}
	}

	private static String compactDeviceName(String name)
	{
		return name.startsWith("Apple ") ? name.substring("Apple ".length()) : name;
	}

	private static String compactCount(long value)
	{
		if (value >= 1_000_000L)
		{
			long tenths = value / 100_000L;
			return (tenths / 10L) + "." + (tenths % 10L) + "M";
		}
		if (value >= 10_000L)
		{
			return (value / 1_000L) + "k";
		}
		return Long.toString(value);
	}

	private static long mib(long bytes)
	{
		return (bytes + 1024L * 1024L - 1L) / (1024L * 1024L);
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
		if (renderExtensions != null) renderExtensions.captureModel(worldProjection, m, orient, x, y, z, renderModeOf(r));
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		if (stats.isEnabled())
		{
			stats.drawTemp.incrementAndGet();
			stats.recordModel(m);
		}
		if (gameObject == null)
		{
			return;
		}
		if (renderExtensions != null) renderExtensions.captureModel(worldProjection, m, orient, x, y, z,
			renderModeOf(gameObject.getRenderable()));
	}

	private static int renderModeOf(Renderable renderable)
	{
		return renderable != null ? renderable.getRenderMode() : Renderable.RENDERMODE_DEFAULT;
	}

	@Override
	public void drawPass(Projection entityProjection, Scene scene, int pass)
	{
		if (stats.isEnabled())
		{
			stats.drawPass.incrementAndGet();
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
		if (renderExtensions == null) return;
		if (renderable instanceof net.runelite.api.Actor) return;
		Model m = (renderable instanceof Model) ? (Model) renderable : renderable.getModel();
		if (m == null) return;
		renderExtensions.captureModel(projection, m, orientation, x, y, z, renderModeOf(renderable));
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
