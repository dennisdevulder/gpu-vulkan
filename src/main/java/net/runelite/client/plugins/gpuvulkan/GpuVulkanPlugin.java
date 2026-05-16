package net.runelite.client.plugins.gpuvulkan;

import com.google.inject.Provides;
import java.awt.Canvas;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
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

@PluginDescriptor(
	name = "GPU (Vulkan)",
	description = "Vulkan-backed renderer (alternative to the OpenGL GPU plugin)",
	tags = {"vulkan", "renderer", "gpu"},
	enabledByDefault = false,
	loadInSafeMode = false
)
@Slf4j
public class GpuVulkanPlugin extends Plugin implements DrawCallbacks
{
	@Inject
	private GpuVulkanPluginConfig config;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private PluginManager pluginManager;

	private Disposables disposables;
	private VulkanInstance instance;
	private VulkanSurface surface;
	private VulkanDevice device;
	private Swapchain swapchain;
	private DepthBuffer depthBuffer;
	private MsaaColorBuffer msaaColor;
	private RenderPass renderPass;
	private SceneRenderer sceneRenderer;
	private TextureArray textureArray;
	private InterfaceRenderer interfaceRenderer;
	private net.runelite.client.plugins.gpuvulkan.gfx.Renderer gfx;
	private net.runelite.rlawt.AWTContext awtContext;
	private Framebuffers framebuffers;
	private FrameSync sync;
	private VulkanRenderer renderer;
	private Canvas canvas;
	private PlatformSurface platform;
	/** True once startup successfully flipped {@code client.setUnlockedFps(true)};
	 *  shutdown only undoes it when this is set, so we don't clobber a user's
	 *  unlocked-FPS state if we never opted in to UNCAPPED. */
	private boolean fpsTouched;
	private final DrawCallbackStats stats = new DrawCallbackStats();
	private volatile double lastCamX, lastCamY, lastCamZ;
	private volatile double lastCamPitch, lastCamYaw;
	private volatile boolean startRequested;
	private static boolean shutdownHookRegistered;

	/** Pointer to the currently-running plugin instance, set when startUp's
	 *  Vulkan init completes and cleared in shutDown. Read from the JVM
	 *  shutdown hook to run a best-effort Vulkan teardown when the user
	 *  hits X on the window (which doesn't trigger {@link #shutDown}). The
	 *  hook isn't tied to a specific instance because plugin enable/disable
	 *  cycles can produce multiple instances over the JVM's lifetime — the
	 *  hook always wants the live one. */
	private static volatile GpuVulkanPlugin activeInstance;

	/** Set by the JVM shutdown hook at entry. {@link #draw} bails out when
	 *  this is true so the Client thread stops queueing fresh frames while
	 *  the hook is running {@code vkDeviceWaitIdle} + {@code disposables.close()}.
	 *  Volatile = happens-before so {@code drawFrame} stops cleanly. */
	private static volatile boolean shuttingDown;

	/** Most recent {@code worldProjection} seen on {@code drawDynamic} /
	 *  {@code drawTemp}. The actor walk in {@link #captureActor} reads this
	 *  to drive {@code captureModelSorted}; the engine doesn't supply a
	 *  Projection to {@code drawScene} so we reuse the previous frame's.
	 *  The camera moves smoothly, so frame-lag is invisible — and on the
	 *  very first frame after plugin enable this stays null and actors
	 *  fall back to the unsorted path. */
	private volatile net.runelite.api.Projection lastWorldProjection;

	@Override
	protected void startUp()
	{
		log.info("Starting GPU (Vulkan)");
		// Both this plugin and the stock "GPU" plugin install themselves as the
		// canvas's renderer via rlawt + setDrawCallbacks. Two simultaneous
		// owners of the AWT/rlawt context corrupt the JAWT/GLX state and
		// disabling either one then exits the JVM natively (no hs_err). Refuse
		// to start up while stock GPU is enabled rather than letting both run.
		if (isStockGpuEnabled())
		{
			log.warn("Stock 'GPU' plugin is enabled — GPU (Vulkan) will not start. Disable 'GPU' first.");
			return;
		}
		startRequested = true;
		// macOS CAMetalLayer.displaySyncEnabled is set from this. VSYNC and
		// TRIPLE_BUFFER both pin to display refresh (the macOS compositor has
		// only one knob — vsync on/off); UNCAPPED releases it. Linux/Windows
		// path picks Vulkan present mode separately based on the same config.
		boolean wantVsync = config.fpsMode() != GpuVulkanPluginConfig.FpsMode.UNCAPPED;
		platform = PlatformSurface.current(wantVsync);
		// macOS doesn't need (and shouldn't run) the rlawt/X11 canvas-attach
		// dance below — Cocoa's AWT layer model is JAWT_SurfaceLayers-based
		// and MacOSPlatformSurface does the JAWT lock + CAMetalLayer attach
		// itself. Attaching rlawt's CAOpenGLLayer first would also conflict
		// with our subsequent setLayer: call.
		final boolean isMac = platform instanceof MacOSPlatformSurface;
		// One-time JVM-lifetime hook: runs at process exit (X-close,
		// System.exit, etc.) when Plugin.shutDown is NOT going to fire.
		// Without this, our Vulkan objects (device, swapchain, …) outlive
		// the validation layer's static state, and the loader's atexit
		// cleanup goes through validation after it's been torn down,
		// producing "dispatch handle not found" warnings (or crashes in
		// older validation builds). Doing the teardown here pre-empts that.
		//
		// Registered once per JVM via the static flag. Reads `activeInstance`
		// fresh so it sees the currently-running plugin instance, not a
		// stale one from before a plugin disable/re-enable cycle.
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
				// Best-effort teardown. We're on a JVM shutdown thread, not
				// the Client thread — the Client thread may still be alive
				// and mid-frame. vkDeviceWaitIdle drains in-flight work
				// before we destroy resources; if it takes too long the
				// JVM will halt the process regardless. Wrap every step so
				// one failure can't skip the rest.
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
				/* Defer until the user is actually in the world. Attaching on
				 * the login screen attaches over a canvas that the OSRS client
				 * isn't yet pushing scene data to, which on macOS makes
				 * debugging the surface/compositing path harder (empty layer
				 * = "broken" even when everything works). Wait for LOGGED_IN
				 * so the first attach happens with a real scene rendering. */
				if (client.getGameState() != GameState.LOGGED_IN)
				{
					log.debug("Deferring GPU (Vulkan) startup until game state is LOGGED_IN (currently {})",
						client.getGameState());
					return false;
				}
				canvas = client.getCanvas();
				// X11 specifically requires that the canvas have a live GL
				// context bound for AWT's resize handling to remain coherent;
				// see the long comment below. macOS doesn't share this quirk
				// (Cocoa's layer-backed AWT canvas doesn't depend on a GL
				// context for peer state) — and on macOS we attach a Metal
				// layer ourselves in MacOSPlatformSurface, so an rlawt GL
				// layer here would conflict. Skip on Mac.
				if (!isMac)
				{
					// Mirror stock GpuPlugin's full canvas-attachment dance.
					// We render via Vulkan, NOT GL — but the GLX context AWT thinks
					// the canvas has is what keeps libawt_xawt.so's native state
					// consistent across canvas peer manipulations (sidebar collapse
					// fires Component resize events with no hierarchy change, but
					// the underlying X11 backing buffer/pixmap still gets rebuilt
					// by AWT). Without a GL context bound, AWT's resize-handler
					// hits a dangling pointer mid-rebuild and exit_group(1)s the
					// EDT (verified via strace).
					//
					// detachCurrent() right after creation: rlawt's createGLContext
					// makes the new context current on the calling thread by
					// default. We don't render through GL, so we unbind it
					// immediately. The context still exists and AWT's native state
					// is well-formed; we just don't hold it as the active GL
					// context on the Client thread.
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
					// NOT calling detachCurrent — stock leaves the GL context
					// current on the Client thread for the lifetime of its rendering.
					// AWT's native code may expect the canvas's GLX context to be
					// "owned" by a thread for canvas state operations to be coherent
					// (resize/peer manipulation walks the context's binding). We
					// don't actually USE the GL context for rendering; we just keep
					// it bound so AWT's tracking stays consistent.
				}
				canvas.setIgnoreRepaint(true);
				canvas.setFocusable(true);
				canvas.requestFocusInWindow();

				disposables = new Disposables();
				// System property wins over stored config: `-Dvkgpu.validation=true`
				// always forces validation on, regardless of whether the user ever
				// toggled the config option in the UI (which would otherwise
				// persist `false` and shadow the property via the config proxy).
				boolean validationOn = Boolean.parseBoolean(System.getProperty("vkgpu.validation", "false"))
					|| config.validation();
				instance = new VulkanInstance(validationOn, platform);
				disposables.add(instance);

				surface = new VulkanSurface(instance, platform, canvas);
				disposables.add(surface);

				device = new VulkanDevice(instance, surface);
				disposables.add(device);

				// FrameSync is registered BEFORE Swapchain on purpose. Disposables
				// is a LIFO close-stack; whatever is added LAST is destroyed FIRST.
				// We need vkDestroySwapchainKHR to run BEFORE vkDestroySemaphore on
				// the renderFinished[] semaphores, because the WSI presentation
				// engine still references renderFinished[lastPresentedImage] until
				// the swapchain is destroyed. Destroying those semaphores while WSI
				// holds references hangs AMD/RADV (and some Mesa releases) — pure
				// hang, no SIGSEGV — because vkDestroySemaphore blocks waiting for
				// the present to complete. vkDeviceWaitIdle does NOT drain the WSI
				// presentation engine (per its man page: equivalent to
				// vkQueueWaitIdle on all queues only), so the swapchain destroy is
				// the only spec-clean release of those WSI references.
				sync = new FrameSync(device);
				disposables.add(sync);

				swapchain = new Swapchain(device, surface, canvas.getWidth(), canvas.getHeight(), config.fpsMode());
				disposables.add(swapchain);
				sync.recreateRenderFinished(swapchain.imageCount());

				// MSAA from config. Pick the highest the device actually
				// supports up to the requested level (clamped by hardware).
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

				// Gfx layer adopts the device/sync/renderPass we just built.
				// Currently only InterfaceRenderer consumes the layer; other
				// renderers stay on raw Vulkan until they're migrated.
				gfx = Gfx.wrap(device, sync, renderPass);
				disposables.add(gfx);

				// Texture array gets populated from the OSRS texture provider
				// once at startup. Layer 0 is the white "no-texture" tile;
				// layer N+1 holds OSRS texture id N.
				textureArray = new TextureArray(device, textureProvider,
					config.anisotropicFilteringLevel());
				disposables.add(textureArray);

				sceneRenderer = new SceneRenderer(device, sync, renderPass, textureArray);
				applyWireframeConfig();
				disposables.add(sceneRenderer);

				interfaceRenderer = new InterfaceRenderer(gfx);
				disposables.add(interfaceRenderer);

				framebuffers = new Framebuffers(device, renderPass, swapchain, depthBuffer, msaaColor);
				disposables.add(framebuffers);

				renderer = new VulkanRenderer(device, renderPass, sceneRenderer,
					interfaceRenderer, swapchain, depthBuffer, msaaColor, framebuffers, sync);
				disposables.add(renderer);

				log.info("Vulkan ready: {} ({}x{}, {} swapchain images)",
					device.deviceName(), swapchain.width(), swapchain.height(), swapchain.imageCount());

				// Publish to the JVM shutdown hook. Must happen AFTER all
				// Vulkan objects are initialised so the hook can't observe
				// a half-built instance.
				activeInstance = this;

				client.setDrawCallbacks(this);
				// GPU: skip the Java software rasterizer. ZBUF: enable Z-buffer scene
				// rendering, which is what makes the client traverse zones and call
				// drawZoneOpaque / drawDynamic / etc. Without ZBUF we only get the
				// drawScene / postDrawScene boundary markers — no geometry callbacks.
				// NO_VERTEX_SNAPPING: optional — disables the legacy 1/128-tile snap
				// on animated entities so player/NPC motion is smooth.
				int gpuFlags = DrawCallbacks.GPU | DrawCallbacks.ZBUF;
				if (config.removeVertexSnapping()) gpuFlags |= DrawCallbacks.NO_VERTEX_SNAPPING;
				client.setGpuFlags(gpuFlags);
				// Tell the engine to stream extra map chunks past the default
				// LoD edge. Required for large draw distances to actually show
				// geometry beyond the default loaded region (otherwise tiles
				// past the edge are unloaded and culled regardless of our
				// drawDistance config).
				client.setExpandedMapLoading(config.expandedMapLoadingChunks());
				// Force the buffer-provider rebuild so the canvas gets an alpha channel
				// for compositing. Stock GpuPlugin does this; without it AWT paints
				// opaque over our Vulkan output.
				client.resizeCanvas();

				// Engine FPS unlock + target. Two independent signals fold into
				// one decision:
				//   - fpsMode == UNCAPPED: render as fast as possible (paired
				//     with the IMMEDIATE present mode the swapchain selects).
				//   - fpsTarget > 0: park the render loop at that rate
				//     regardless of fpsMode. Works with vsync (limit < refresh
				//     rate caps below vsync) and with UNCAPPED (limit > vsync
				//     overrides the engine's default ~50 cap).
				// Only touch state if we're opting in to either — never call
				// setUnlockedFps(false) preemptively, since that overrides a
				// user-driven unlock from elsewhere. fpsTouched gates the
				// matching shutdown call.
				int fpsTarget = Math.max(0, config.fpsTarget());
				boolean unlockEngine =
					config.fpsMode() == GpuVulkanPluginConfig.FpsMode.UNCAPPED
					|| fpsTarget > 0;
				if (unlockEngine)
				{
					client.setUnlockedFps(true);
					client.setUnlockedFpsTarget(fpsTarget); // 0 = no cap
					fpsTouched = true;
				}

				// Plugin enable mid-session: the scene's already loaded, but loadScene
				// won't fire again until the player crosses a chunk boundary. Capture
				// terrain right away so M11 doesn't have to wait for that.
				Scene currentScene = client.getTopLevelWorldView() == null ? null
					: client.getTopLevelWorldView().getScene();
				if (currentScene != null)
				{
					sceneRenderer.captureScene(currentScene);
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
				sceneRenderer = null;
				textureArray = null;
				interfaceRenderer = null;
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
		// Unpublish from the JVM shutdown hook first. If JVM shutdown begins
		// while we're partway through shutDown's clientThread.invoke, the
		// hook seeing activeInstance == null skips its own teardown and we
		// avoid double-destroy. The Disposables LIFO already guards against
		// a single thread re-entering close, but the hook is a separate
		// thread and could overlap with this lambda.
		activeInstance = null;
		clientThread.invoke(() ->
		{
			// Teardown order mirrors stock GpuPlugin's shutDown. awtContext.destroy()
			// must run before client.resizeCanvas(): resizeCanvas rebuilds the canvas
			// buffer provider, and an attached AWTContext at that point leaves JAWT
			// state stale so the next plugin enable fails JAWT_DrawingSurface_Lock.
			// Drain the GPU first so no Vulkan work is in flight when the engine
			// switches its render path back.
			if (device != null)
			{
				try
				{
					org.lwjgl.vulkan.VK13.vkDeviceWaitIdle(device.handle());
				}
				catch (RuntimeException e)
				{
					log.warn("vkDeviceWaitIdle failed during shutdown: {}", e.getMessage());
				}
			}

			client.setGpuFlags(0);
			client.setDrawCallbacks(null);
			if (fpsTouched)
			{
				client.setUnlockedFps(false);
				fpsTouched = false;
			}

			if (disposables != null)
			{
				disposables.close();
				disposables = null;
			}

			if (awtContext != null)
			{
				awtContext.destroy();
				awtContext = null;
			}

			if (platform instanceof MacOSPlatformSurface)
			{
				MacOSMetalHelper.detachMetalLayer();
			}
			if (canvas != null)
			{
				canvas.setIgnoreRepaint(false);
			}
			client.resizeCanvas();
			instance = null;
			surface = null;
			device = null;
			swapchain = null;
			depthBuffer = null;
			msaaColor = null;
			renderPass = null;
			sceneRenderer = null;
			textureArray = null;
			interfaceRenderer = null;
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
		if (sceneRenderer != null && ev.getKey() != null && ev.getKey().startsWith("wireframe"))
		{
			applyWireframeConfig();
		}
	}

	@Subscribe
	public void onGameStateChanged(net.runelite.api.events.GameStateChanged ev)
	{
		// Logout / hop / connection-lost: drop the captured scene so the
		// login screen doesn't render with the previous world's terrain
		// bleeding through the edges. Mirrors stock GpuPlugin's
		// `sceneFboValid = false` on this same threshold (GpuPlugin.java:1583).
		// Anything below LOADING means the engine no longer has a scene
		// to drive — keep our captured geometry around through LOADING so
		// the world stays drawn while the next region streams in.
		if (sceneRenderer == null) return;
		net.runelite.api.GameState state = ev.getGameState();
		if (state.getState() < net.runelite.api.GameState.LOADING.getState())
		{
			sceneRenderer.invalidateCapturedScene();
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

	private void applyWireframeConfig()
	{
		if (sceneRenderer == null) return;
		sceneRenderer.setWireframe(SceneRenderer.Layer.TERRAIN,      config.wireframeTerrain());
		sceneRenderer.setWireframe(SceneRenderer.Layer.WALLS,        config.wireframeWalls());
		sceneRenderer.setWireframe(SceneRenderer.Layer.DECORATIVE,   config.wireframeDecorative());
		sceneRenderer.setWireframe(SceneRenderer.Layer.GROUND,       config.wireframeGround());
		sceneRenderer.setWireframe(SceneRenderer.Layer.GAME_OBJECTS, config.wireframeGameObjects());
		sceneRenderer.setWireframe(SceneRenderer.Layer.DYNAMIC,      config.wireframeDynamic());
	}

	// ---- DrawCallbacks --------------------------------------------------

	@Override
	public void draw(int overlayColor)
	{
		if (renderer == null || canvas == null || shuttingDown)
		{
			return;
		}
		// Detect resize between frames; the swapchain rebuild itself happens
		// inside drawFrame on OUT_OF_DATE_KHR or when the size changes.
		int w = canvas.getWidth();
		int h = canvas.getHeight();
		if (w != swapchain.width() || h != swapchain.height())
		{
			renderer.markSwapchainStale();
		}
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
			// Stock vert.glsl uses `tick & 127` — modulo keeps the value
			// small so `tick * anim * (1/128)` doesn't accumulate float
			// precision drift while still cycling through every UV offset.
			client.getGameCycle() & 127,
			config.smoothBanding() ? 1f : 0f,
			overlayColor);
		stats.maybeLog();
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane)
	{
		stats.drawScene.incrementAndGet();
		stats.lastCamX = cameraX;
		stats.lastCamY = cameraY;
		stats.lastCamZ = cameraZ;
		stats.lastCamPlane = plane;
		// We don't write to lastCamX/Y/Z/Pitch/Yaw here — those come exclusively
		// from preSceneDraw to match what GpuPlugin's projection uses. drawScene
		// fires after preSceneDraw and would overwrite the good values with
		// doubles from a different reference frame.
		// Start a fresh capture window — the upcoming drawDynamic / drawTemp / etc.
		// callbacks will pump geometry into sceneRenderer.
		if (sceneRenderer != null)
		{
			sceneRenderer.beginFrame();
			captureActors();
		}
	}

	/**
	 * Walks every NPC and player every frame and captures their current
	 * animated model. drawTemp / drawDynamic from the OSRS engine are
	 * unreliable for live actors — its frustum culling skips them for whole
	 * frames as the camera pans, leaving the player flickering. Iterating
	 * Client.getNpcs() / getPlayers() ourselves bypasses that culling.
	 */
	/** Reusable per-frame dedupe set for actor capture — avoids re-rendering
	 *  an actor when (a) the engine returns the local player at multiple slots
	 *  in `getPlayers()` during worldview transitions, or (b) the same actor
	 *  surfaces via both NPC and Player lists momentarily. The "two heads on
	 *  the player" symptom is exactly this case for the local player. */
	private final java.util.IdentityHashMap<net.runelite.api.Actor, Boolean> seenActors
		= new java.util.IdentityHashMap<>();

	private void captureActors()
	{
		seenActors.clear();
		java.util.List<NPC> npcs = client.getNpcs();
		if (npcs != null)
		{
			for (NPC npc : npcs) if (npc != null && seenActors.put(npc, Boolean.TRUE) == null) captureActor(npc);
		}
		java.util.List<Player> players = client.getPlayers();
		if (players != null)
		{
			for (Player p : players) if (p != null && seenActors.put(p, Boolean.TRUE) == null) captureActor(p);
		}
	}

	private void captureActor(net.runelite.api.Actor actor)
	{
		net.runelite.api.coords.LocalPoint loc = actor.getLocalLocation();
		if (loc == null) return;
		Model m = actor.getModel();
		if (m == null) return;
		int tileH = net.runelite.api.Perspective.getTileHeight(client, loc, client.getPlane());
		net.runelite.api.Projection proj = lastWorldProjection;
		if (proj != null)
		{
			sceneRenderer.captureModelSorted(proj, m, actor.getCurrentOrientation(), loc.getX(), tileH, loc.getY());
		}
		else
		{
			// First frame after enable — no projection cached yet.
			sceneRenderer.captureModel(m, actor.getCurrentOrientation(), loc.getX(), tileH, loc.getY());
		}
	}

	/**
	 * Called once per frame just before the scene is rendered. The float values
	 * here are exactly what GpuPlugin uses for its projection matrix, so we use
	 * the entire camera tuple from this callback (not drawScene's doubles) to
	 * stay in lockstep with what the OSRS scene compute expects.
	 */
	/** Identity of the scene captured into sceneRenderer. swapScene/loadScene
	 *  callbacks don't fire reliably in this engine version, so we detect
	 *  scene transitions ourselves by comparing references each frame. */
	private Scene capturedScene;

	@Override
	public void preSceneDraw(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		stats.preSceneDraw.incrementAndGet();
		// Scene-reference fallback: swapScene/loadScene callbacks don't fire
		// in this engine version (recon shows swap=0 load=0), so the only
		// reliable signal that the scene has been swapped is the Scene object
		// reference itself changing between frames. Re-capture on identity
		// change so geometry stays fresh after chunk transitions.
		if (scene != capturedScene && sceneRenderer != null)
		{
			capturedScene = scene;
			sceneRenderer.captureScene(scene);
		}
		// Plane bounds — matches Zone.renderOpaque(..., minLevel, level,
		// maxLevel, ...) in stock GpuPlugin (GpuPlugin.java:1062). Stock
		// skips geometry outside [minLevel..maxLevel]; we mirror it.
		if (sceneRenderer != null)
		{
			sceneRenderer.setLevelRange(minLevel, maxLevel);
		}
		lastCamX = cameraX;
		lastCamY = cameraY;
		lastCamZ = cameraZ;
		lastCamPitch = cameraPitch;
		lastCamYaw = cameraYaw;
		// Tell the engine how far to walk the scene for entity processing.
		// Drives the engine's internal `ep.qq/du/fi` loops that register
		// per-entity clickboxes — without this, only tiles within the
		// engine's tiny default range get clickable.
		scene.setDrawDistance(config.drawDistance());
		// Hand the per-frame roof-id set to the renderer so it can punch
		// holes in the GAME_OBJECTS draw for objects whose IDs are in here.
		if (sceneRenderer != null) sceneRenderer.setHideRoofIds(hideRoofIds);
	}

	@Override
	public void postDrawScene()
	{
		stats.postDrawScene.incrementAndGet();
	}

	@Override
	public void swapScene(Scene scene)
	{
		stats.swapScene.incrementAndGet();
		// Belt-and-braces: also re-capture here. The primary trigger is the
		// scene-reference check in preSceneDraw (engine doesn't reliably
		// call swapScene), but if a future engine version does, we still
		// honor it without double-capturing (preSceneDraw's check will see
		// scene == capturedScene next frame).
		if (sceneRenderer != null)
		{
			capturedScene = scene;
			sceneRenderer.captureScene(scene);
		}
	}

	@Override
	public void loadScene(Scene scene)
	{
		stats.loadScene.incrementAndGet();
	}

	// Modern 2-arg variant. Engine doesn't currently call either loadScene
	// overload for us (confirmed: load=0 in stats), so we rely on the
	// scene-reference check in preSceneDraw instead — these are wired up
	// for completeness in case engine behavior changes.
	@Override
	public void loadScene(net.runelite.api.WorldView worldView, Scene scene)
	{
		stats.loadScene.incrementAndGet();
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileZ)
	{
		stats.drawScenePaint.incrementAndGet();
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileZ)
	{
		stats.drawSceneTileModel.incrementAndGet();
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		stats.drawZoneOpaque.incrementAndGet();
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		stats.drawZoneAlpha.incrementAndGet();
	}

	@Override
	public void drawDynamic(Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m, int orient, int x, int y, int z)
	{
		stats.drawDynamic.incrementAndGet();
		stats.recordModel(m);
		// Skip when the engine fires drawDynamic without a real TileObject
		// (it does this for actor pipeline emissions in GPU mode) OR when
		// the renderable is itself an Actor — captureActors() handles those.
		// Without these guards the local player rendered twice.
		if (tileObject == null) return;
		if (r instanceof net.runelite.api.Actor) return;
		lastWorldProjection = worldProjection;
		if (sceneRenderer != null) sceneRenderer.captureModelSorted(worldProjection, m, orient, x, y, z);
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		stats.drawTemp.incrementAndGet();
		stats.recordModel(m);
		// Same skip rule. Engine fires drawTemp with gameObject == null when
		// emitting actors through the temp-render pipeline (since Actor isn't
		// a GameObject); captureActors() already drew them, so skipping null
		// here removes the duplicate copy.
		if (gameObject == null) return;
		lastWorldProjection = worldProjection;
		if (sceneRenderer != null) sceneRenderer.captureModelSorted(worldProjection, m, orient, x, y, z);
	}

	@Override
	public void drawPass(Projection entityProjection, Scene scene, int pass)
	{
		stats.drawPass.incrementAndGet();
	}

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		stats.drawSingle.incrementAndGet();
		if (renderable instanceof Model) stats.recordModel((Model) renderable);
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		stats.animate.incrementAndGet();
	}

	// ---- Guice ----------------------------------------------------------

	@Provides
	GpuVulkanPluginConfig provideConfig(ConfigManager cm)
	{
		return cm.getConfig(GpuVulkanPluginConfig.class);
	}
}
