package net.runelite.client.plugins.gpuvulkan;

import com.google.inject.Provides;
import java.awt.Canvas;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
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
import net.runelite.api.TileObject;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

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
	private static boolean shutdownHookRegistered;

	@Override
	protected void startUp()
	{
		log.info("Starting GPU (Vulkan)");
		// Bail out before touching AWT state on platforms we can't render on
		// yet (currently macOS). Doing this here gives the user a clear
		// reason in the plugin-enable error toast instead of letting them
		// hit a confusing JAWT/Vulkan failure mid-setup.
		platform = PlatformSurface.current();
		if (platform instanceof MacOSPlatformSurface)
		{
			platform = null;
			throw new UnsupportedOperationException(
				"GPU (Vulkan) plugin on macOS is not implemented yet — "
					+ "use the stock GPU (OpenGL) plugin in the meantime.");
		}
		// One-time JVM-lifetime hook: logs when the JVM is shutting down so
		// we can distinguish clean exit (System.exit / window close / etc.)
		// from a hard native crash (no hook fires). Registered once; the
		// Runtime no-ops on subsequent identical adds via the if-check.
		if (!shutdownHookRegistered)
		{
			shutdownHookRegistered = true;
			Runtime.getRuntime().addShutdownHook(new Thread(() ->
				log.info("JVM shutdown hook fired (clean exit)"), "vkgpu-shutdown-watch"));
		}
		clientThread.invoke(() ->
		{
			try
			{
				canvas = client.getCanvas();
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
				canvas.setIgnoreRepaint(true);
				canvas.setFocusable(true);
				canvas.requestFocusInWindow();

				disposables = new Disposables();
				instance = new VulkanInstance(config.validation(), platform);
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
					case MSAA_8: desiredSamples = 8; break;
					case MSAA_4: desiredSamples = 4; break;
					case MSAA_2: desiredSamples = 2; break;
					case DISABLED:
					default:     desiredSamples = 1; break;
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

				// Texture array gets populated from the OSRS texture provider
				// once at startup. Layer 0 is the white "no-texture" tile;
				// layer N+1 holds OSRS texture id N.
				textureArray = new TextureArray(device, client.getTextureProvider(),
					config.anisotropicFilteringLevel());
				disposables.add(textureArray);

				sceneRenderer = new SceneRenderer(device, sync, renderPass, textureArray);
				applyWireframeConfig();
				disposables.add(sceneRenderer);

				interfaceRenderer = new InterfaceRenderer(device, renderPass);
				disposables.add(interfaceRenderer);

				framebuffers = new Framebuffers(device, renderPass, swapchain, depthBuffer, msaaColor);
				disposables.add(framebuffers);

				renderer = new VulkanRenderer(device, renderPass, sceneRenderer,
					interfaceRenderer, swapchain, depthBuffer, msaaColor, framebuffers, sync);
				disposables.add(renderer);

				log.info("Vulkan ready: {} ({}x{}, {} swapchain images)",
					device.deviceName(), swapchain.width(), swapchain.height(), swapchain.imageCount());

				client.setDrawCallbacks(this);
				// GPU: skip the Java software rasterizer. ZBUF: enable Z-buffer scene
				// rendering, which is what makes the client traverse zones and call
				// drawZoneOpaque / drawDynamic / etc. Without ZBUF we only get the
				// drawScene / postDrawScene boundary markers — no geometry callbacks.
				client.setGpuFlags(DrawCallbacks.GPU | DrawCallbacks.ZBUF);
				// Force the buffer-provider rebuild so the canvas gets an alpha channel
				// for compositing. Stock GpuPlugin does this; without it AWT paints
				// opaque over our Vulkan output.
				client.resizeCanvas();

				// FPS mode: VSYNC/TRIPLE_BUFFER keep the engine FPS at its
				// default cap (~50 game ticks). UNCAPPED tells the engine to
				// run as fast as possible alongside our IMMEDIATE present
				// mode, so the reported FPS reflects raw renderer capacity.
				// Only touch state when entering UNCAPPED — never call
				// setUnlockedFps(false) preemptively, since that overrides a
				// user-driven unlock from elsewhere (other GPU plugin, dev
				// console). fpsTouched gates the matching shutdown call.
				if (config.fpsMode() == GpuVulkanPluginConfig.FpsMode.UNCAPPED)
				{
					client.setUnlockedFps(true);
					client.setUnlockedFpsTarget(0); // 0 = no cap
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
				framebuffers = null;
				sync = null;
				renderer = null;
				canvas = null;
				throw e;
			}
		});
	}

	@Override
	protected void shutDown()
	{
		log.info("Stopping GPU (Vulkan)");
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
		if (renderer == null || canvas == null)
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
			config.drawDistance(),
			config.fogDepth(),
			// Stock vert.glsl uses `tick & 127` — modulo keeps the value
			// small so `tick * anim * (1/128)` doesn't accumulate float
			// precision drift while still cycling through every UV offset.
			client.getGameCycle() & 127);
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
		sceneRenderer.captureModel(m, actor.getCurrentOrientation(), loc.getX(), tileH, loc.getY());
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
		if (sceneRenderer != null) sceneRenderer.captureModel(m, orient, x, y, z);
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
		if (sceneRenderer != null) sceneRenderer.captureModel(m, orient, x, y, z);
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
