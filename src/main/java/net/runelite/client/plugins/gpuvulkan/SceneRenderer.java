/*
 * Scene capture and draw scheduling are original to this project, even where
 * behaviour mirrors stock GpuPlugin. Stock-ported UV mapping lives in
 * ModelUvMapper with its original license note.
 */
package net.runelite.client.plugins.gpuvulkan;

import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.hooks.DrawCallbacks;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Captures OSRS scene geometry into one host-visible vertex buffer with a
 * static arena followed by per-frame dynamic/overlay arenas. The five static
 * layers (TERRAIN..GAME_OBJECTS) are populated by {@link #captureScene} on
 * scene load; dirty static zones and the DYNAMIC layer are refilled in the
 * current frame arena.
 *
 * <p>{@link #recordDraw} issues one draw per non-empty layer, picking the fill
 * or line pipeline based on each layer's individual wireframe flag, and binds
 * the OSRS texture array as descriptor set 0.
 *
 * <p>Vertex layout (matches {@link ScenePipeline}):
 * {@code [float3 position, packed alpha/bias/HSL, packed texture/u/v]}.
 */
@Slf4j
final class SceneRenderer implements AutoCloseable, PendingRenderables.Sink,
	SceneModelEmitter.VertexSink, SceneTileEmitter.Sink
{
	enum Layer
	{
		TERRAIN, WALLS, DECORATIVE, GROUND, GAME_OBJECTS,
		/** Translucent faces of all static object classes, captured in a
		 *  second filtered sweep. The blended alpha pass draws only this
		 *  layer (plus dynamics) instead of replaying the whole scene. */
		STATIC_ALPHA,
		DYNAMIC;
	}
	private static final Layer[] LAYERS = Layer.values();
	private static final int LAYER_COUNT = LAYERS.length;
	private static final Layer LAST_STATIC = Layer.STATIC_ALPHA;

	/** Default arena sizes for the top-level scene. Sub-worldview instances
	 *  use much smaller arenas — see the capacity constructor. */
	static final int DEFAULT_STATIC_VERTICES = 5_000_000;
	static final int DEFAULT_FRAME_VERTICES = 3_000_000;

	/** Grows on capture overflow (see {@link #captureScene}); hard-capped so a
	 *  pathological scene can't eat the address space. */
	private int maxStaticVertices;
	private static final int MAX_STATIC_VERTICES_HARD_CAP = 12_000_000;
	private final int maxFrameVertices;
	private final int overlayHighWaterVertices;
	/** Vertices the capture wanted but the static arena couldn't hold —
	 *  the demand signal for growing the arena. */
	private int droppedVertices;
	/** Escape hatch back to drawing the whole captured static scene every
	 *  frame instead of the draw-distance zone radius. */
	private static final boolean FULL_SCENE_DRAW = Boolean.getBoolean("vkgpu.fullSceneDraw");
	private static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
	/** False for sub-worldview renderers — only the top-level scene owns a skybox dome. */
	private final boolean emitSkybox;

	private final VulkanDevice device;
	private final FrameSync sync;
	private final ScenePipeline fillPipeline;
	private final ScenePipeline linePipeline;
	private final ScenePipeline alphaPipeline;
	private final ScenePipeline skyboxPipeline;
	private final ScenePipeline priorityColorPipeline;
	private final ScenePipeline priorityDepthPipeline;
	private final TextureArray textureArray;
	private SceneVertexBuffer vbuf;
	private ByteBuffer mapped;
	private long descriptorSet;
	private int slotBytes;
	private final DrawCallbackStats stats;
	private final boolean repushConstantsEveryDraw;
	private final SceneDrawEmitter drawEmitter;
	private final SceneZoneDrawScheduler zoneDrawScheduler;
	private final boolean singlePassAlpha;
	private long writePtr;
	private long writeBasePtr;
	private int writeBaseVertex;
	private int writeVertexLimit;

	private static final int MAX_PLANES = 4;

	/** Stock GPU uploads the whole extended scene: EXTENDED_SCENE_SIZE / 8. */
	static final int ZONE_SIZE = 8;
	static final int ZONES_PER_SIDE = Constants.EXTENDED_SCENE_SIZE / ZONE_SIZE;
	static final int ZONE_COUNT = ZONES_PER_SIDE * ZONES_PER_SIDE;

	private final int[] regionEnds = new int[LAYER_COUNT];
	private int skyboxStart = -1;
	private int skyboxEnd = -1;
	private int loggedSkyboxFaces = Integer.MIN_VALUE;
	private int loggedSkyboxVertices = Integer.MIN_VALUE;
	/** Per-layer, per-plane: vertex count after that plane's tiles emitted.
	 *  Used to clip at {@code planeEnds[layer][maxPlane]} so upper-plane
	 *  geometry is culled when the player is below. */
	private final int[][] planeEnds = new int[LAYER_COUNT][MAX_PLANES];

	/** Per (layer, plane, zone) vertex ranges within the static region.
	 *  Populated by {@link #captureScene}; used to avoid submitting static
	 *  geometry outside the configured scene/fog radius. */
	private final int[][][] zoneVertexStart = new int[LAYER_COUNT][MAX_PLANES][ZONE_COUNT];
	private final int[][][] zoneVertexCount = new int[LAYER_COUNT][MAX_PLANES][ZONE_COUNT];
	private final int[][][][] overlayZoneStart =
		new int[FrameSync.FRAMES_IN_FLIGHT][LAYER_COUNT][MAX_PLANES][ZONE_COUNT];
	private final int[][][][] overlayZoneCount =
		new int[FrameSync.FRAMES_IN_FLIGHT][LAYER_COUNT][MAX_PLANES][ZONE_COUNT];
	private final boolean[][] overlayZoneValid = new boolean[FrameSync.FRAMES_IN_FLIGHT][ZONE_COUNT];
	private final boolean[] overlaySlotHasZones = new boolean[FrameSync.FRAMES_IN_FLIGHT];
	private final int[] overlayNextVertex = new int[FrameSync.FRAMES_IN_FLIGHT];

	private final boolean[] wireframe = new boolean[LAYER_COUNT];
	private final PriorityRangeSet priorityRanges = new PriorityRangeSet();
	private int dynamicOpaqueEnd = -1;
	/** Plane range to render this frame, forwarded from preSceneDraw's
	 *  (minLevel, level, maxLevel). Roof hiding only applies above the
	 *  current plane, matching the stock GPU plugin's zone renderer. */
	private volatile int minPlane = 0;
	private volatile int currentPlane = 0;
	private volatile int maxPlane = MAX_PLANES - 1;

	/** Engine-supplied per-frame set of tile-roof IDs (NOT GameObject IDs,
	 *  values come from {@link net.runelite.api.Scene#getRoofs()}) to hide
	 *  above the player. */
	private volatile java.util.Set<Integer> hideRoofIds = java.util.Collections.emptySet();

	/** Per-tile-range roof tags recorded during {@link #captureScene}.
	 *  At draw time the layer is split into render/skip sub-ranges based
	 *  on the current frame's {@link #hideRoofIds}. */
	private final RoofRangeSet roofRanges = new RoofRangeSet();
	private boolean rebuildingOverlayZone;
	/** Sorted [start, end) pairs of vertex sub-ranges to skip this frame. */
	private int[] skipScratch = new int[256];
	private final DirtyZoneTracker dirtyZones = new DirtyZoneTracker(ZONE_COUNT);

	private int vertexCount;
	private boolean overflowed;

	private final PendingRenderables pendingRenderables = new PendingRenderables();
	private final SceneModelEmitter modelEmitter;
	private final SceneTileEmitter tileEmitter;
	private int tileLookupOffset;
	private int capturedSceneOrigin;
	private int capturedSceneSize = Constants.SCENE_SIZE;

	/**
	 * Drops the captured scene so {@link #recordDraw} skips its work on the
	 * next frame. Called when {@code GameState} drops below {@code LOADING}
	 * (logout, world hop, connection lost): without this the previously
	 * captured terrain / walls / objects keep rendering behind the login
	 * screen, leaking the prior world's view through the new state. Mirrors
	 * stock GpuPlugin's {@code sceneFboValid = false} on the same event.
	 */
	void invalidateCapturedScene()
	{
		vertexCount = 0;
		roofRanges.clear();
		dirtyZones.clear();
		pendingRenderables.clear();
		modelEmitter.clearCache();
		for (int i = 0; i < LAYER_COUNT; i++)
		{
			regionEnds[i] = 0;
			for (int p = 0; p < MAX_PLANES; p++) planeEnds[i][p] = 0;
		}
		skyboxStart = skyboxEnd = -1;
		clearOverlayRanges();
	}

	void invalidateZone(Scene scene, int zx, int zz)
	{
		if (zx < 0 || zz < 0 || zx >= ZONES_PER_SIDE || zz >= ZONES_PER_SIDE)
		{
			return;
		}
		markZoneDirty(zx * ZONES_PER_SIDE + zz);
	}

	@Override
	public void markZoneDirty(int zone)
	{
		dirtyZones.mark(zone);
	}

	SceneRenderer(VulkanDevice device, FrameSync sync,
		RenderPass renderPass, TextureArray textureArray,
		DrawCallbackStats stats, boolean alphaToCoverage, boolean singlePassAlpha)
	{
		this(device, sync, renderPass, textureArray, stats, alphaToCoverage, singlePassAlpha,
			DEFAULT_STATIC_VERTICES, DEFAULT_FRAME_VERTICES, true);
	}

	SceneRenderer(VulkanDevice device, FrameSync sync,
		RenderPass renderPass, TextureArray textureArray,
		DrawCallbackStats stats, boolean alphaToCoverage, boolean singlePassAlpha,
		int maxStaticVertices, int maxFrameVertices, boolean emitSkybox)
	{
		this.maxStaticVertices = maxStaticVertices;
		this.maxFrameVertices = maxFrameVertices;
		this.overlayHighWaterVertices = maxFrameVertices / 2;
		this.emitSkybox = emitSkybox;
		this.writeVertexLimit = maxStaticVertices;
		this.device = device;
		this.sync = sync;
		this.textureArray = textureArray;
		this.stats = stats;
		this.repushConstantsEveryDraw = device.supportsMetalObjects();
		this.drawEmitter = new SceneDrawEmitter(stats, repushConstantsEveryDraw);
		this.zoneDrawScheduler = new SceneZoneDrawScheduler(drawEmitter,
			ZONES_PER_SIDE, ZONE_COUNT, planeEnds,
			zoneVertexStart, zoneVertexCount,
			overlayZoneStart, overlayZoneCount,
			overlayZoneValid, overlaySlotHasZones);
		this.modelEmitter = new SceneModelEmitter(stats, priorityRanges, this);
		this.tileEmitter = new SceneTileEmitter(this);
		this.singlePassAlpha = singlePassAlpha || Boolean.getBoolean("vkgpu.skipAlphaPass");
		this.fillPipeline = new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL, true,
			renderPass.samples(), alphaToCoverage);
		this.alphaPipeline = new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL,
			true, false, true, renderPass.samples(), false, true);
		this.skyboxPipeline = new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL,
			true, false, true, renderPass.samples(), false, true);
		this.priorityColorPipeline = new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL,
			true, false, true, renderPass.samples(), alphaToCoverage);
		this.priorityDepthPipeline = new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL,
			true, true, false, renderPass.samples(), alphaToCoverage);
		// fillModeNonSolid is required for VK_POLYGON_MODE_LINE; without it
		// (llvmpipe, some embedded SoCs) wireframe collapses to FILL.
		this.linePipeline = device.supportsFillModeNonSolid()
			? new ScenePipeline(device, renderPass, VK_POLYGON_MODE_LINE, true,
				renderPass.samples(), alphaToCoverage)
			: null;
		this.vbuf = new SceneVertexBuffer(device,
			staticBufferBytes() + frameBufferBytes() * FrameSync.FRAMES_IN_FLIGHT,
			frameBufferBytes(),
			fillPipeline.descriptorSetLayout(), textureArray);
		this.mapped = vbuf.mapped();
		this.descriptorSet = vbuf.descriptorSet();
		this.slotBytes = (int) vbuf.slotBytes();
	}

	void setWireframe(Layer layer, boolean on)
	{
		wireframe[layer.ordinal()] = on;
	}

	void setLevelRange(int minLevel, int maxLevel)
	{
		setLevelRange(minLevel, minLevel, maxLevel);
	}

	void setLevelRange(int minLevel, int currentLevel, int maxLevel)
	{
		int min = Math.max(0, Math.min(MAX_PLANES - 1, minLevel));
		int cur = Math.max(min, Math.min(MAX_PLANES - 1, currentLevel));
		int max = Math.max(cur, Math.min(MAX_PLANES - 1, maxLevel));
		minPlane = min;
		currentPlane = cur;
		maxPlane = max;
	}

	void setHideRoofIds(java.util.Set<Integer> ids)
	{
		hideRoofIds = ids != null ? ids : java.util.Collections.emptySet();
	}

	void collectDebugMetrics(GpuVulkanDebugMetrics metrics)
	{
		metrics.sceneVertices += regionEnds[LAST_STATIC.ordinal()];
		metrics.totalVertices += vertexCount;
		metrics.maxVertices += maxStaticVertices + maxFrameVertices;
		metrics.roofRanges += roofRanges.count();
		metrics.pendingRenderables += pendingRenderables.size();
		metrics.dirtyZones += dirtyZones.count();
		modelEmitter.collectDebugMetrics(metrics);
		metrics.sceneDrawCalls += stats.sceneDrawCalls.get();
		metrics.sceneDrawVertices += stats.sceneDrawVertices.get();
		metrics.scenePushConstants += stats.scenePushConstants.get();
		metrics.roofSkipPairs += stats.roofSkipPairs.get();
		metrics.overlayDirtyZones += stats.overlayDirtyZones.get();
		metrics.overflowed |= overflowed;
		metrics.sceneBufferBytes += staticBufferBytes() + frameBufferBytes() * FrameSync.FRAMES_IN_FLIGHT;
	}

	private long staticBufferBytes()
	{
		return (long) maxStaticVertices * ScenePipeline.VERTEX_STRIDE;
	}

	private long frameBufferBytes()
	{
		return (long) maxFrameVertices * ScenePipeline.VERTEX_STRIDE;
	}

	private int staticVertexCount()
	{
		return regionEnds[LAST_STATIC.ordinal()];
	}

	private void useStaticWriteArena()
	{
		writeBaseVertex = 0;
		writeVertexLimit = maxStaticVertices;
		writeBasePtr = MemoryUtil.memAddress(mapped);
		writePtr = writeBasePtr;
	}

	private void useFrameWriteArena(int slot, int logicalVertex)
	{
		writeBaseVertex = staticVertexCount();
		writeVertexLimit = writeBaseVertex + maxFrameVertices;
		writeBasePtr = MemoryUtil.memAddress(mapped) + staticBufferBytes() + (long) slot * slotBytes;
		setWriteCursor(logicalVertex);
	}

	private void setWriteCursor(int logicalVertex)
	{
		writePtr = writeBasePtr + (long) (logicalVertex - writeBaseVertex) * ScenePipeline.VERTEX_STRIDE;
	}

	@Override
	public boolean reserveVertices(int vertices)
	{
		if (vertices < 0 || vertexCount > writeVertexLimit - vertices)
		{
			if (vertices > 0)
			{
				droppedVertices += vertices;
			}
			overflow();
			return false;
		}
		return true;
	}

	/**
	 * Drops the dynamic suffix; static layers preserved. Waits on this
	 * slot's in-flight fence first — without it, CPU writes race the GPU
	 * read from FRAMES_IN_FLIGHT frames ago, producing torn-vertex
	 * flicker (especially on macOS where Metal keeps frames in flight
	 * longer).
	 */
	void beginFrame()
	{
		try (MemoryStack stack = stackPush())
		{
			Vk.check("vkWaitForFences",
				vkWaitForFences(device.handle(), stack.longs(sync.inFlightFence()), true, Long.MAX_VALUE));
		}
		int slot = sync.currentFrame();
		vertexCount = overlayNextVertex[slot];
		useFrameWriteArena(slot, vertexCount);
		overflowed = false;
		priorityRanges.clear();
		dynamicOpaqueEnd = -1;
	}

	void drawPass(int pass)
	{
		if (pass == DrawCallbacks.PASS_OPAQUE)
		{
			dynamicOpaqueEnd = vertexCount;
		}
	}

	void rebuildDirtyZones(Scene scene)
	{
		// Restore the frame arena unconditionally: a mid-frame captureScene
		// leaves the write cursor in the static arena.
		int slot = sync.currentFrame();
		vertexCount = overlayNextVertex[slot];
		useFrameWriteArena(slot, vertexCount);

		if (scene == null || dirtyZones.count() == 0)
		{
			return;
		}

		if (vertexCount - staticVertexCount() > overlayHighWaterVertices)
		{
			log.info("Overlay arena past high-water mark ({} verts), recapturing scene",
				vertexCount - staticVertexCount());
			captureScene(scene);
			vertexCount = overlayNextVertex[slot];
			useFrameWriteArena(slot, vertexCount);
			return;
		}

		Tile[][][] tiles = captureTiles(scene);
		if (tiles == null)
		{
			return;
		}

		int slotBit = 1 << slot;
		int allSlots = (1 << FrameSync.FRAMES_IN_FLIGHT) - 1;

		final int planes = Math.min(tiles.length, MAX_PLANES);
		final int sceneOrigin = capturedSceneOrigin;
		final int sceneSize = capturedSceneSize;
		final int[][][] roofs = scene.getRoofs();
		final byte[][][] tileSettings = scene.getExtendedTileSettings();
		final int roofOffset = scene.getWorldViewId() == net.runelite.api.WorldView.TOPLEVEL
			? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 : 0;

		for (int zone = 0; zone < ZONE_COUNT; zone++)
		{
			if (!dirtyZones.needsSlot(zone, slotBit))
			{
				continue;
			}

			clearOverlayZone(slot, zone);
			boolean priorOverflow = overflowed;
			overflowed = false;
			rebuildingOverlayZone = true;
			try
			{
				captureDirtyZone(scene, tiles, planes, sceneOrigin, sceneSize, roofs, tileSettings, roofOffset, slot, zone);
			}
			finally
			{
				rebuildingOverlayZone = false;
			}
			boolean zoneOverflowed = overflowed;
			overflowed |= priorOverflow;
			if (zoneOverflowed)
			{
				// Truncated capture: leave dirty for retry, don't mask static.
				clearOverlayZone(slot, zone);
				continue;
			}
			// Valid even at zero vertices — an emptied zone still masks static.
			overlayZoneValid[slot][zone] = true;
			overlaySlotHasZones[slot] = true;
			dirtyZones.markSlotRebuilt(zone, slotBit, allSlots);
		}

		overlayNextVertex[slot] = vertexCount;
		setWriteCursor(vertexCount);
	}

	/** Visits one tile (including any bridge tile underneath) within a layer pass. */
	@FunctionalInterface
	private interface TileCapture
	{
		void capture(Tile tile, int plane, int sx, int sy);
	}

	void captureScene(Scene scene)
	{
		// Dense regions can exceed the static arena (observed: ~5.5M verts at
		// Lumbridge with full extended load vs the 5M default — overflow
		// silently dropped the trailing layers, so whole staircases and wall
		// runs vanished). On overflow, grow to measured demand and recapture.
		for (int attempt = 0; ; attempt++)
		{
			captureSceneOnce(scene);
			int demand = vertexCount + droppedVertices;
			if (!overflowed || attempt >= 2 || maxStaticVertices >= MAX_STATIC_VERTICES_HARD_CAP)
			{
				if (overflowed)
				{
					log.warn("Scene demand {} verts exceeds static arena cap {}; geometry truncated",
						demand, maxStaticVertices);
				}
				return;
			}
			int target = Math.min(MAX_STATIC_VERTICES_HARD_CAP,
				Math.max(demand + demand / 5, maxStaticVertices + maxStaticVertices / 2));
			log.info("Static scene arena overflow (demand {} verts, capacity {}), growing to {} and recapturing",
				demand, maxStaticVertices, target);
			growStaticArena(target);
		}
	}

	/** Reallocates the vertex buffer with a larger static region. Caller
	 *  guarantees the GPU is idle (captureSceneOnce drains first). */
	private void growStaticArena(int newStaticVertices)
	{
		Vk.check("vkDeviceWaitIdle (arena grow)", vkDeviceWaitIdle(device.handle()));
		vbuf.close();
		maxStaticVertices = newStaticVertices;
		vbuf = new SceneVertexBuffer(device,
			staticBufferBytes() + frameBufferBytes() * FrameSync.FRAMES_IN_FLIGHT,
			frameBufferBytes(),
			fillPipeline.descriptorSetLayout(), textureArray);
		mapped = vbuf.mapped();
		descriptorSet = vbuf.descriptorSet();
		slotBytes = (int) vbuf.slotBytes();
	}

	private void captureSceneOnce(Scene scene)
	{
		// Drain the GPU before rewriting the buffer; captureScene is rare
		// (region change) and the buffer is shared across all in-flight frames.
		Vk.check("vkDeviceWaitIdle", vkDeviceWaitIdle(device.handle()));
		droppedVertices = 0;

		for (int i = 0; i < LAYER_COUNT; i++)
		{
			regionEnds[i] = 0;
			for (int p = 0; p < MAX_PLANES; p++)
			{
				planeEnds[i][p] = 0;
				for (int z = 0; z < ZONE_COUNT; z++)
				{
					zoneVertexStart[i][p][z] = 0;
					zoneVertexCount[i][p][z] = 0;
				}
			}
		}
		vertexCount = 0;
		skyboxStart = skyboxEnd = -1;
		useStaticWriteArena();
		overflowed = false;
		roofRanges.clear();
		clearOverlayRanges();
		dirtyZones.clear();
		pendingRenderables.clear();
		modelEmitter.clearCache();

		Tile[][][] tiles = captureTiles(scene);
		if (tiles == null) return;
		captureSkybox(scene);
		final int planes = Math.min(tiles.length, MAX_PLANES);
		final int sceneOrigin = capturedSceneOrigin;
		final int sceneSize = capturedSceneSize;

		// Scene.getRoofs() dims are EXTENDED_SCENE_SIZE (184) on toplevel,
		// not SCENE_SIZE (104) like scene.getTiles(). Instances aren't
		// extended, so the offset is 0 there.
		final int[][][] roofs = scene.getRoofs();
		final byte[][][] tileSettings = scene.getExtendedTileSettings();
		final int roofOffset = scene.getWorldViewId() == net.runelite.api.WorldView.TOPLEVEL
			? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 : 0;

		// Pass order: emit each layer plane-by-plane so vertices are sorted
		// (layer-major, plane-minor). recordDraw clips per layer at
		// planeEnds[layer][visiblePlane] to hide roofs above the player.
		// Static object sweeps keep only opaque faces; translucent faces are
		// captured afterwards into STATIC_ALPHA so the blended pass doesn't
		// replay the whole scene. Tiles are always opaque (no trans byte).
		modelEmitter.setStaticFaceFilter(SceneModelEmitter.FILTER_OPAQUE);
		captureLayer(Layer.TERRAIN, tiles, planes, sceneOrigin, sceneSize, roofs, tileSettings, roofOffset,
			(cur, p, sx, sy) ->
			{
				SceneTilePaint paint = cur.getSceneTilePaint();
				if (paint != null) captureTilePaint(scene, paint, p, sx, sy);
				SceneTileModel m = cur.getSceneTileModel();
				if (m != null) captureTileModel(m, sx, sy);
			});

		captureLayer(Layer.WALLS, tiles, planes, sceneOrigin, sceneSize, roofs, tileSettings, roofOffset,
			(cur, p, sx, sy) ->
			{
				WallObject w = cur.getWallObject();
				if (w == null) return;
				captureRenderable(w.getRenderable1(), 0, w.getX(), w.getZ(), w.getY());
				captureRenderable(w.getRenderable2(), 0, w.getX(), w.getZ(), w.getY());
			});

		captureLayer(Layer.DECORATIVE, tiles, planes, sceneOrigin, sceneSize, roofs, tileSettings, roofOffset,
			(cur, p, sx, sy) ->
			{
				DecorativeObject d = cur.getDecorativeObject();
				if (d == null) return;
				captureRenderable(d.getRenderable(),  0,
					d.getX() + d.getXOffset(),  d.getZ(), d.getY() + d.getYOffset());
				captureRenderable(d.getRenderable2(), 0,
					d.getX() + d.getXOffset2(), d.getZ(), d.getY() + d.getYOffset2());
			});

		captureLayer(Layer.GROUND, tiles, planes, sceneOrigin, sceneSize, roofs, tileSettings, roofOffset,
			(cur, p, sx, sy) ->
			{
				GroundObject g = cur.getGroundObject();
				if (g == null) return;
				captureRenderable(g.getRenderable(), 0, g.getX(), g.getZ(), g.getY());
			});

		captureGameObjectsLayer(tiles, planes, sceneOrigin, sceneSize, roofs, tileSettings, roofOffset);

		modelEmitter.setStaticFaceFilter(SceneModelEmitter.FILTER_ALPHA);
		captureLayer(Layer.STATIC_ALPHA, tiles, planes, sceneOrigin, sceneSize, roofs, tileSettings, roofOffset,
			this::captureStaticAlphaTile);
		modelEmitter.setStaticFaceFilter(SceneModelEmitter.FILTER_ALL);

		// Pad planeEnds past `planes` so max-plane lookups still draw
		// everything if requested.
		for (int i = 0; i < LAYER_COUNT; i++)
			for (int p = planes; p < MAX_PLANES; p++)
				planeEnds[i][p] = regionEnds[i];

		for (int slot = 0; slot < FrameSync.FRAMES_IN_FLIGHT; slot++)
		{
			overlayNextVertex[slot] = vertexCount;
		}

		log.info("Vulkan scene capture: base=({}, {}) worldView={} instance={} tileOffset={} sceneOrigin={} sceneSize={} tileDims={} vertices total={} terrain={} walls={} decorative={} ground={} gameObjects={} staticAlpha={} roofTiles={}",
			scene.getBaseX(), scene.getBaseY(), scene.getWorldViewId(), scene.isInstance(),
			tileLookupOffset, sceneOrigin, sceneSize, tileDims(tiles), vertexCount,
			regionEnds[Layer.TERRAIN.ordinal()],
			regionEnds[Layer.WALLS.ordinal()] - regionEnds[Layer.TERRAIN.ordinal()],
			regionEnds[Layer.DECORATIVE.ordinal()] - regionEnds[Layer.WALLS.ordinal()],
			regionEnds[Layer.GROUND.ordinal()] - regionEnds[Layer.DECORATIVE.ordinal()],
			regionEnds[Layer.GAME_OBJECTS.ordinal()] - regionEnds[Layer.GROUND.ordinal()],
			regionEnds[Layer.STATIC_ALPHA.ordinal()] - regionEnds[Layer.GAME_OBJECTS.ordinal()],
			roofRanges.count());
	}

	private void clearOverlayRanges()
	{
		for (int slot = 0; slot < FrameSync.FRAMES_IN_FLIGHT; slot++)
		{
			overlayNextVertex[slot] = regionEnds[LAST_STATIC.ordinal()];
			overlaySlotHasZones[slot] = false;
			java.util.Arrays.fill(overlayZoneValid[slot], false);
			for (int layer = 0; layer < LAYER_COUNT; layer++)
			{
				for (int plane = 0; plane < MAX_PLANES; plane++)
				{
					java.util.Arrays.fill(overlayZoneStart[slot][layer][plane], 0);
					java.util.Arrays.fill(overlayZoneCount[slot][layer][plane], 0);
				}
			}
		}
	}

	void captureSkybox(Scene scene)
	{
		skyboxStart = skyboxEnd = -1;
		if (scene == null || !emitSkybox)
		{
			return;
		}
		Model skybox = scene.getSkybox();
		if (skybox != null)
		{
			int faces = skybox.getFaceCount();
			skyboxStart = vertexCount;
			captureModel(skybox, 0, 0, 0, 0);
			skyboxEnd = vertexCount;
			int vertices = skyboxEnd - skyboxStart;
			if (faces != loggedSkyboxFaces || vertices != loggedSkyboxVertices)
			{
				log.info("Vulkan skybox: model faces={} capturedVertices={}", faces, vertices);
				loggedSkyboxFaces = faces;
				loggedSkyboxVertices = vertices;
			}
		}
	}

	private void clearOverlayZone(int slot, int zone)
	{
		overlayZoneValid[slot][zone] = false;
		for (int layer = 0; layer < LAYER_COUNT; layer++)
		{
			for (int plane = 0; plane < MAX_PLANES; plane++)
			{
				overlayZoneStart[slot][layer][plane][zone] = 0;
				overlayZoneCount[slot][layer][plane][zone] = 0;
			}
		}
	}

	private void captureDirtyZone(Scene scene, Tile[][][] tiles, int planes, int sceneOrigin, int sceneSize,
								  int[][][] roofs, byte[][][] tileSettings, int roofOffset,
								  int slot, int zone)
	{
		int zx = zone / ZONES_PER_SIDE;
		int zy = zone % ZONES_PER_SIDE;
		// Same opaque/alpha sweep split as captureScene.
		modelEmitter.setStaticFaceFilter(SceneModelEmitter.FILTER_OPAQUE);
		captureOverlayLayer(Layer.TERRAIN, tiles, planes, sceneOrigin, sceneSize,
			roofs, tileSettings, roofOffset, slot, zone, zx, zy,
			(cur, p, sx, sy) ->
			{
				SceneTilePaint paint = cur.getSceneTilePaint();
				if (paint != null) captureTilePaint(scene, paint, p, sx, sy);
				SceneTileModel m = cur.getSceneTileModel();
				if (m != null) captureTileModel(m, sx, sy);
			});

		captureOverlayLayer(Layer.WALLS, tiles, planes, sceneOrigin, sceneSize,
			roofs, tileSettings, roofOffset, slot, zone, zx, zy,
			(cur, p, sx, sy) ->
			{
				WallObject w = cur.getWallObject();
				if (w == null) return;
				captureRenderable(w.getRenderable1(), 0, w.getX(), w.getZ(), w.getY());
				captureRenderable(w.getRenderable2(), 0, w.getX(), w.getZ(), w.getY());
			});

		captureOverlayLayer(Layer.DECORATIVE, tiles, planes, sceneOrigin, sceneSize,
			roofs, tileSettings, roofOffset, slot, zone, zx, zy,
			(cur, p, sx, sy) ->
			{
				DecorativeObject d = cur.getDecorativeObject();
				if (d == null) return;
				captureRenderable(d.getRenderable(), 0,
					d.getX() + d.getXOffset(), d.getZ(), d.getY() + d.getYOffset());
				captureRenderable(d.getRenderable2(), 0,
					d.getX() + d.getXOffset2(), d.getZ(), d.getY() + d.getYOffset2());
			});

		captureOverlayLayer(Layer.GROUND, tiles, planes, sceneOrigin, sceneSize,
			roofs, tileSettings, roofOffset, slot, zone, zx, zy,
			(cur, p, sx, sy) ->
			{
				GroundObject g = cur.getGroundObject();
				if (g == null) return;
				captureRenderable(g.getRenderable(), 0, g.getX(), g.getZ(), g.getY());
			});

		captureOverlayGameObjectsLayer(tiles, planes, sceneOrigin, sceneSize,
			roofs, tileSettings, roofOffset, slot, zone, zx, zy);

		modelEmitter.setStaticFaceFilter(SceneModelEmitter.FILTER_ALPHA);
		captureOverlayLayer(Layer.STATIC_ALPHA, tiles, planes, sceneOrigin, sceneSize,
			roofs, tileSettings, roofOffset, slot, zone, zx, zy,
			this::captureStaticAlphaTile);
		modelEmitter.setStaticFaceFilter(SceneModelEmitter.FILTER_ALL);
	}

	private void captureOverlayLayer(Layer layer,
		Tile[][][] tiles, int planes, int sceneOrigin, int sceneSize,
		int[][][] roofs, byte[][][] tileSettings, int roofOffset,
		int slot, int zone, int zx, int zy, TileCapture body)
	{
		final int L = layer.ordinal();
		int sceneEnd = sceneOrigin + sceneSize;
		int x0 = sceneOrigin + zx * ZONE_SIZE, x1 = Math.min(x0 + ZONE_SIZE, sceneEnd);
		int y0 = sceneOrigin + zy * ZONE_SIZE, y1 = Math.min(y0 + ZONE_SIZE, sceneEnd);
		for (int outputLevel = 0; outputLevel < planes; outputLevel++)
		{
			int start = vertexCount;
			if (outputLevel == 0)
			{
				captureLayerPass(tiles, planes, x0, x1, y0, y1, 0, false,
					roofs, tileSettings, roofOffset, body);
				for (int sourceLevel = 0; sourceLevel < planes; sourceLevel++)
				{
					captureLayerPass(tiles, planes, x0, x1, y0, y1, sourceLevel, true,
						roofs, tileSettings, roofOffset, body);
				}
			}
			else
			{
				captureLayerPass(tiles, planes, x0, x1, y0, y1, outputLevel, false,
					roofs, tileSettings, roofOffset, body);
			}
			overlayZoneStart[slot][L][outputLevel][zone] = start;
			overlayZoneCount[slot][L][outputLevel][zone] = vertexCount - start;
		}
	}

	private void captureOverlayGameObjectsLayer(Tile[][][] tiles, int planes, int sceneOrigin, int sceneSize,
		int[][][] roofs, byte[][][] tileSettings, int roofOffset,
		int slot, int zone, int zx, int zy)
	{
		final int L = Layer.GAME_OBJECTS.ordinal();
		int sceneEnd = sceneOrigin + sceneSize;
		int x0 = sceneOrigin + zx * ZONE_SIZE, x1 = Math.min(x0 + ZONE_SIZE, sceneEnd);
		int y0 = sceneOrigin + zy * ZONE_SIZE, y1 = Math.min(y0 + ZONE_SIZE, sceneEnd);
		for (int outputLevel = 0; outputLevel < planes; outputLevel++)
		{
			int start = vertexCount;
			if (outputLevel == 0)
			{
				captureGameObjectsPass(tiles, planes, x0, x1, y0, y1, 0, false,
					roofs, tileSettings, roofOffset);
				for (int sourceLevel = 0; sourceLevel < planes; sourceLevel++)
				{
					captureGameObjectsPass(tiles, planes, x0, x1, y0, y1, sourceLevel, true,
						roofs, tileSettings, roofOffset);
				}
			}
			else
			{
				captureGameObjectsPass(tiles, planes, x0, x1, y0, y1, outputLevel, false,
					roofs, tileSettings, roofOffset);
			}
			overlayZoneStart[slot][L][outputLevel][zone] = start;
			overlayZoneCount[slot][L][outputLevel][zone] = vertexCount - start;
		}
	}

	/**
	 * Walks every tile of {@code layer} in the same effective level order as
	 * stock GPU's SceneUploader: level 0 contains normal level-0 geometry and
	 * VIS_BELOW geometry from all upper levels; upper levels contain only their
	 * non-VIS_BELOW source level. This keeps roof removal from behaving like a
	 * flat world-space clipping plane.
	 */
	private void captureLayer(Layer layer,
		Tile[][][] tiles, int planes, int sceneOrigin, int sceneSize,
		int[][][] roofs, byte[][][] tileSettings, int roofOffset,
		TileCapture body)
	{
		final int L = layer.ordinal();
		int sceneEnd = sceneOrigin + sceneSize;
		for (int outputLevel = 0; outputLevel < planes; outputLevel++)
		{
			for (int zx = 0; zx < ZONES_PER_SIDE; zx++)
			{
				for (int zy = 0; zy < ZONES_PER_SIDE; zy++)
				{
					int zoneStart = vertexCount;
					int x0 = sceneOrigin + zx * ZONE_SIZE, x1 = Math.min(x0 + ZONE_SIZE, sceneEnd);
					int y0 = sceneOrigin + zy * ZONE_SIZE, y1 = Math.min(y0 + ZONE_SIZE, sceneEnd);
					if (outputLevel == 0)
					{
						captureLayerPass(tiles, planes, x0, x1, y0, y1, 0, false,
							roofs, tileSettings, roofOffset, body);
						for (int sourceLevel = 0; sourceLevel < planes; sourceLevel++)
						{
							captureLayerPass(tiles, planes, x0, x1, y0, y1, sourceLevel, true,
								roofs, tileSettings, roofOffset, body);
						}
					}
					else
					{
						captureLayerPass(tiles, planes, x0, x1, y0, y1, outputLevel, false,
							roofs, tileSettings, roofOffset, body);
					}
					int zoneIdx = zx * ZONES_PER_SIDE + zy;
					zoneVertexStart[L][outputLevel][zoneIdx] = zoneStart;
					zoneVertexCount[L][outputLevel][zoneIdx] = vertexCount - zoneStart;
				}
			}
			planeEnds[L][outputLevel] = vertexCount;
		}
		regionEnds[L] = vertexCount;
	}

	private void captureLayerPass(Tile[][][] tiles, int planes,
		int x0, int x1, int y0, int y1, int sourceLevel, boolean visbelow,
		int[][][] roofs, byte[][][] tileSettings, int roofOffset, TileCapture body)
	{
		if (sourceLevel < 0 || sourceLevel >= planes) return;
		for (int sx = x0; sx < x1; sx++)
		{
			for (int sy = y0; sy < y1; sy++)
			{
				int msx = sx + roofOffset;
				int msy = sy + roofOffset;
				SceneRoofInfo roofInfo = SceneRoofInfo.forTile(roofs, tileSettings, sourceLevel, msx, msy);
				if (roofInfo.visbelow != visbelow) continue;

				Tile t = tileAt(tiles, sourceLevel, sx + tileLookupOffset, sy + tileLookupOffset);
				if (t == null) continue;
				Tile cur = t;
				while (cur != null)
				{
					int renderLevel = SceneRoofInfo.renderLevel(cur, sourceLevel);
					net.runelite.api.Point sceneLocation = cur.getSceneLocation();
					int tileX = sceneLocation != null ? sceneLocation.getX() : sx;
					int tileY = sceneLocation != null ? sceneLocation.getY() : sy;
					int before = vertexCount;
					body.capture(cur, renderLevel, tileX, tileY);
					if (roofInfo.roofId != 0 && vertexCount > before)
						recordRoofRange(roofInfo.roofId, before, vertexCount - before);
					cur = (cur == t) ? t.getBridge() : null;
				}
			}
		}
	}

	/** GameObjects emit only on the sceneMinLocation tile (multi-tile
	 *  objects naturally dedupe), and each gets its own roof-range entry
	 *  instead of a merged-per-tile range. */
	private void captureGameObjectsLayer(Tile[][][] tiles, int planes, int sceneOrigin, int sceneSize,
		int[][][] roofs, byte[][][] tileSettings, int roofOffset)
	{
		final int L = Layer.GAME_OBJECTS.ordinal();
		int sceneEnd = sceneOrigin + sceneSize;
		for (int outputLevel = 0; outputLevel < planes; outputLevel++)
		{
			for (int zx = 0; zx < ZONES_PER_SIDE; zx++)
			{
				for (int zy = 0; zy < ZONES_PER_SIDE; zy++)
				{
					int zoneStart = vertexCount;
					int x0 = sceneOrigin + zx * ZONE_SIZE, x1 = Math.min(x0 + ZONE_SIZE, sceneEnd);
					int y0 = sceneOrigin + zy * ZONE_SIZE, y1 = Math.min(y0 + ZONE_SIZE, sceneEnd);
					if (outputLevel == 0)
					{
						captureGameObjectsPass(tiles, planes, x0, x1, y0, y1, 0, false,
							roofs, tileSettings, roofOffset);
						for (int sourceLevel = 0; sourceLevel < planes; sourceLevel++)
						{
							captureGameObjectsPass(tiles, planes, x0, x1, y0, y1, sourceLevel, true,
								roofs, tileSettings, roofOffset);
						}
					}
					else
					{
						captureGameObjectsPass(tiles, planes, x0, x1, y0, y1, outputLevel, false,
							roofs, tileSettings, roofOffset);
					}
					int zoneIdx = zx * ZONES_PER_SIDE + zy;
					zoneVertexStart[L][outputLevel][zoneIdx] = zoneStart;
					zoneVertexCount[L][outputLevel][zoneIdx] = vertexCount - zoneStart;
				}
			}
			planeEnds[L][outputLevel] = vertexCount;
		}
		regionEnds[L] = vertexCount;
	}

	private void captureGameObjectsPass(Tile[][][] tiles, int planes,
		int x0, int x1, int y0, int y1, int sourceLevel, boolean visbelow,
		int[][][] roofs, byte[][][] tileSettings, int roofOffset)
	{
		if (sourceLevel < 0 || sourceLevel >= planes) return;
		for (int sx = x0; sx < x1; sx++)
		{
			for (int sy = y0; sy < y1; sy++)
			{
				int msx = sx + roofOffset;
				int msy = sy + roofOffset;
				SceneRoofInfo roofInfo = SceneRoofInfo.forTile(roofs, tileSettings, sourceLevel, msx, msy);
				if (roofInfo.visbelow != visbelow) continue;

				Tile t = tileAt(tiles, sourceLevel, sx + tileLookupOffset, sy + tileLookupOffset);
				if (t == null) continue;
				Tile cur = t;
				while (cur != null)
				{
					GameObject[] objs = cur.getGameObjects();
					if (objs == null) { cur = (cur == t) ? t.getBridge() : null; continue; }
					net.runelite.api.Point tilePoint = cur.getSceneLocation();
					for (GameObject o : objs)
					{
						if (o == null) continue;
						net.runelite.api.Point min = o.getSceneMinLocation();
						if (min == null || !min.equals(tilePoint)) continue;
						int before = vertexCount;
						// getModelOrientation(), not getOrientation() — the latter folds
						// in animation orient and rotates static arches / walls / fences.
						captureRenderable(o.getRenderable(), o.getModelOrientation(),
							o.getX(), o.getZ(), o.getY());
						if (roofInfo.roofId != 0 && vertexCount > before)
							recordRoofRange(roofInfo.roofId, before, vertexCount - before);
					}
					cur = (cur == t) ? t.getBridge() : null;
				}
			}
		}
	}

	/** Fused per-tile body for the STATIC_ALPHA sweep: every static object
	 *  class in one walk so the layer gets a single coherent set of per-zone
	 *  ranges. The model emitter's ALPHA filter (set by the caller) keeps
	 *  only translucent faces; game objects dedupe on their min tile exactly
	 *  like {@link #captureGameObjectsPass}. */
	private void captureStaticAlphaTile(Tile cur, int p, int sx, int sy)
	{
		WallObject w = cur.getWallObject();
		if (w != null)
		{
			captureAlphaRenderable(w.getRenderable1(), 0, w.getX(), w.getZ(), w.getY());
			captureAlphaRenderable(w.getRenderable2(), 0, w.getX(), w.getZ(), w.getY());
		}
		DecorativeObject d = cur.getDecorativeObject();
		if (d != null)
		{
			captureAlphaRenderable(d.getRenderable(), 0,
				d.getX() + d.getXOffset(), d.getZ(), d.getY() + d.getYOffset());
			captureAlphaRenderable(d.getRenderable2(), 0,
				d.getX() + d.getXOffset2(), d.getZ(), d.getY() + d.getYOffset2());
		}
		GroundObject g = cur.getGroundObject();
		if (g != null)
		{
			captureAlphaRenderable(g.getRenderable(), 0, g.getX(), g.getZ(), g.getY());
		}
		GameObject[] objs = cur.getGameObjects();
		if (objs != null)
		{
			net.runelite.api.Point tilePoint = cur.getSceneLocation();
			for (GameObject o : objs)
			{
				if (o == null) continue;
				net.runelite.api.Point min = o.getSceneMinLocation();
				if (min == null || !min.equals(tilePoint)) continue;
				captureAlphaRenderable(o.getRenderable(), o.getModelOrientation(),
					o.getX(), o.getZ(), o.getY());
			}
		}
	}

	/** Alpha-sweep variant of {@link #captureRenderable}: unstreamed models
	 *  were already queued on {@code pendingRenderables} by the opaque sweep
	 *  of the same tile — queuing again would double-emit once loaded. */
	private void captureAlphaRenderable(Renderable r, int orient, int x, int y, int z)
	{
		if (r == null || r instanceof net.runelite.api.Actor) return;
		Model m = PendingRenderables.resolveModel(r);
		if (m != null)
		{
			captureModel(m, orient, x, y, z);
		}
	}

	private void captureRenderable(Renderable r, int orient, int x, int y, int z)
	{
		if (r == null) return;
		if (r instanceof net.runelite.api.Actor) return;
		Model m = PendingRenderables.resolveModel(r);
		if (m == null)
		{
			// Model not streamed yet — retry until it can promote its zone
			// through the per-slot overlay rebuild path.
			pendingRenderables.add(r, orient, x, y, z, zoneForWorldPoint(x, z));
			return;
		}
		captureModel(m, orient, x, y, z);
	}

	/**
	 * Per-frame retry for renderables whose Model was null at capture
	 * time. Emits any whose model has since loaded into the dynamic
	 * suffix (same budget actors use); entries that are still null roll
	 * forward unchanged. Reset on each {@link #captureScene} or
	 * {@link #invalidateCapturedScene}.
	 */
	void captureDynamicPending()
	{
		pendingRenderables.captureLoaded(this);
	}

	@Override
	public boolean isZoneDirty(int zone)
	{
		return zone >= 0 && zone < ZONE_COUNT && dirtyZones.isDirty(zone);
	}

	private int zoneForWorldPoint(int x, int z)
	{
		int sx = x >> 7;
		int sz = z >> 7;
		int sceneEnd = capturedSceneOrigin + capturedSceneSize;
		if (sx < capturedSceneOrigin || sz < capturedSceneOrigin || sx >= sceneEnd || sz >= sceneEnd)
		{
			return -1;
		}
		return ((sx - capturedSceneOrigin) / ZONE_SIZE) * ZONES_PER_SIDE
			+ ((sz - capturedSceneOrigin) / ZONE_SIZE);
	}

	private void captureTilePaint(Scene scene, SceneTilePaint paint, int plane, int sx, int sy)
	{
		tileEmitter.captureTilePaint(scene, paint, plane, sx, sy);
	}

	private void captureTileModel(SceneTileModel model, int sx, int sy)
	{
		tileEmitter.captureTileModel(model, sx, sy);
	}

	@Override
	public void captureModel(Model m, int orient, int worldX, int worldY, int worldZ)
	{
		modelEmitter.captureModel(m, orient, worldX, worldY, worldZ);
	}

	void captureModelSorted(net.runelite.api.Projection proj, Model m, int orient, int worldX, int worldY, int worldZ)
	{
		modelEmitter.captureModelSorted(proj, m, orient, worldX, worldY, worldZ);
	}

	void captureModelSorted(net.runelite.api.Projection proj, Model m, int orient, int worldX, int worldY, int worldZ, int renderMode)
	{
		modelEmitter.captureModelSorted(proj, m, orient, worldX, worldY, worldZ, renderMode);
	}

	void captureModelSorted(net.runelite.api.Projection proj, Model m, int orient, int worldX, int worldY, int worldZ, int renderMode,
		boolean actorModel)
	{
		modelEmitter.captureModelSorted(proj, m, orient, worldX, worldY, worldZ, renderMode, actorModel);
	}

	void recordDraw(VkCommandBuffer cmd, float[] mvp, float brightness,
					float cameraX, float cameraY, float cameraZ, float drawDistanceTiles, float fogDepthTiles,
					float fogR, float fogG, float fogB,
					int tick, float textureLightMode,
					int colorBlindMode, float colorBlindIntensity,
					float smoothBanding)
	{
		recordOpaque(cmd, mvp, brightness, cameraX, cameraY, cameraZ, drawDistanceTiles, fogDepthTiles,
			fogR, fogG, fogB, tick, textureLightMode, colorBlindMode, colorBlindIntensity, smoothBanding,
			0, 0, 0);
		recordAlpha(cmd, mvp, brightness, cameraX, cameraY, cameraZ, drawDistanceTiles, fogDepthTiles,
			fogR, fogG, fogB, tick, textureLightMode, colorBlindMode, colorBlindIntensity, smoothBanding,
			0, 0, 0);
	}

	/**
	 * Opaque phase: skybox, static layers, dynamic layer, priority ranges.
	 * {@code entityTx/entityTz/entityYawJau} are the sub-worldview placement
	 * the shader uses to reconstruct toplevel-scene coordinates for fog —
	 * zero for the top-level scene, where local == world (scene.vert misc.yzw).
	 */
	void recordOpaque(VkCommandBuffer cmd, float[] mvp, float brightness,
					float cameraX, float cameraY, float cameraZ, float drawDistanceTiles, float fogDepthTiles,
					float fogR, float fogG, float fogB,
					int tick, float textureLightMode,
					int colorBlindMode, float colorBlindIntensity,
					float smoothBanding,
					int entityTx, int entityTz, int entityYawJau)
	{
		drawEmitter.beginFrame(stats.isEnabled());
		if (vertexCount == 0) return;
		// LANDMINE: bind at offset 0 and shift via firstVertex rather than
		// byte-offset binding — MoltenVK's offset translation produces no
		// visible geometry at hundreds-of-MB offsets on Apple Silicon.
		final int slot = sync.currentFrame();
		final int slotFirstVertex = maxStaticVertices + slot * maxFrameVertices - staticVertexCount();
		final int staticFirstVertex = 0;
		try (MemoryStack stack = stackPush())
		{
			vkCmdBindVertexBuffers(cmd, 0,
				stack.longs(vbuf.handle()),
				stack.longs(0L));

			// Vertex push (96 bytes): mat4 mvp + vec4 fogVtx + ivec4 (tick, entity placement).
			ByteBuffer vertPush = stack.malloc(96);
			Mat4Ops.writeTo(vertPush, mvp);
			vertPush.position(64);
			vertPush.putFloat(cameraX);
			vertPush.putFloat(cameraZ);
			vertPush.putFloat(drawDistanceTiles * 128f);
			vertPush.putFloat(fogDepthTiles * 128f);
			vertPush.putInt(tick).putInt(entityTx).putInt(entityTz).putInt(entityYawJau);
			vertPush.flip();

			// Fragment push (32 bytes at offset 96):
			//   vec4 fogFrag     = (fogR, fogG, fogB, brightness)
			//   vec4 fragExtras  = (textureLightMode, colorBlindMode, colorBlindIntensity, smoothBanding)
			ByteBuffer fragPush = stack.malloc(32);
			fragPush.putFloat(fogR).putFloat(fogG).putFloat(fogB).putFloat(brightness);
			fragPush.putFloat(textureLightMode);
			fragPush.putFloat((float) colorBlindMode);
			fragPush.putFloat(colorBlindIntensity);
			fragPush.putFloat(singlePassAlpha ? 20f + smoothBanding : smoothBanding);
			fragPush.flip();

			java.util.Set<Integer> hr = hideRoofIds;
			if (skipScratch.length < roofRanges.requiredSkipPairCapacity())
			{
				skipScratch = java.util.Arrays.copyOf(skipScratch, roofRanges.requiredSkipPairCapacity());
			}
			int skipPairs = roofRanges.buildSkipPairs(hr, skipScratch);
			if (stats.isEnabled())
			{
				stats.roofSkipPairs.addAndGet(skipPairs);
				stats.overlayDirtyZones.addAndGet(overlaySlotHasZones[slot] ? countOverlayDirtyZones(slot) : 0);
			}

			ScenePipeline boundPipeline = null;
			final int loMin = minPlane;
			final int loCur = currentPlane;
			final int loMax = maxPlane;
			int radiusTiles = (int) Math.ceil(drawDistanceTiles + fogDepthTiles + 2f);
			int radiusZones = Math.max(1, (radiusTiles + ZONE_SIZE - 1) / ZONE_SIZE);
			// Zone-radius culling, matching stock (the engine only invokes
			// zones inside the draw distance). A fully streamed extended
			// scene captures ~8.6M verts in dense regions — drawing all of
			// it every frame halves FPS vs the radius. The old "bad-tile
			// black-scene" caution gets an escape hatch instead of a
			// permanent full draw: -Dvkgpu.fullSceneDraw=true reverts.
			boolean fullZoneRange = FULL_SCENE_DRAW;
			int sceneEnd = capturedSceneOrigin + capturedSceneSize;
			int camTileX = clamp((int) Math.floor(cameraX / 128f), capturedSceneOrigin, sceneEnd - 1);
			int camTileZ = clamp((int) Math.floor(cameraZ / 128f), capturedSceneOrigin, sceneEnd - 1);
			int camZoneX = (camTileX - capturedSceneOrigin) / ZONE_SIZE;
			int camZoneZ = (camTileZ - capturedSceneOrigin) / ZONE_SIZE;
			int minZoneX = fullZoneRange ? 0 : Math.max(0, camZoneX - radiusZones);
			int maxZoneX = fullZoneRange ? ZONES_PER_SIDE - 1 : Math.min(ZONES_PER_SIDE - 1, camZoneX + radiusZones);
			int minZoneZ = fullZoneRange ? 0 : Math.max(0, camZoneZ - radiusZones);
			int maxZoneZ = fullZoneRange ? ZONES_PER_SIDE - 1 : Math.min(ZONES_PER_SIDE - 1, camZoneZ + radiusZones);
			if (skyboxEnd > skyboxStart)
			{
				float[] skyboxMvp = mvp.clone();
				Mat4Ops.mul(skyboxMvp, Mat4Ops.translate(cameraX, cameraY, cameraZ));

				ByteBuffer skyboxVertPush = stack.malloc(96);
				Mat4Ops.writeTo(skyboxVertPush, skyboxMvp);
				skyboxVertPush.position(64);
				skyboxVertPush.putFloat(cameraX);
				skyboxVertPush.putFloat(cameraZ);
				skyboxVertPush.putFloat(drawDistanceTiles * 128f);
				skyboxVertPush.putFloat(0f);
				skyboxVertPush.putInt(tick).putInt(0).putInt(0).putInt(0);
				skyboxVertPush.flip();

				vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, skyboxPipeline.handle());
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
					skyboxPipeline.layout(), 0, stack.longs(descriptorSet), null);
				if (!repushConstantsEveryDraw)
				{
					drawEmitter.pushConstants(cmd, skyboxPipeline.layout(), skyboxVertPush, fragPush);
				}
				drawEmitter.drawRange(cmd, skyboxStart, skyboxEnd, skipScratch, 0, false,
					staticFirstVertex, skyboxPipeline.layout(), skyboxVertPush, fragPush);
			}

			int layerStart = skyboxEnd > skyboxStart ? skyboxEnd : 0;
			for (int i = 0; i < LAYER_COUNT; i++)
			{
				int regionEnd = i == LAYER_COUNT - 1 ? vertexCount : regionEnds[i];
				if (regionEnd <= layerStart)
				{
					layerStart = regionEnd;
					continue;
				}
				// Translucent statics belong to the blended pass; in
				// single-pass mode alpha-to-coverage handles them here.
				if (LAYERS[i] == Layer.STATIC_ALPHA && !singlePassAlpha)
				{
					layerStart = regionEnd;
					continue;
				}

				ScenePipeline want = (wireframe[i] && linePipeline != null) ? linePipeline : fillPipeline;
				if (want != boundPipeline)
				{
					vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, want.handle());
					vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
						want.layout(), 0, stack.longs(descriptorSet), null);
					if (!repushConstantsEveryDraw)
					{
						drawEmitter.pushConstants(cmd, want.layout(), vertPush, fragPush);
					}
					boundPipeline = want;
				}

				if (LAYERS[i] == Layer.DYNAMIC)
				{
					int dynamicStart = overlayNextVertex[slot];
					int dynamicEnd = dynamicOpaqueEnd >= dynamicStart ? Math.min(dynamicOpaqueEnd, regionEnd) : regionEnd;
					drawEmitter.drawRange(cmd, dynamicStart, dynamicEnd, priorityRanges.skipPairs(), priorityRanges.count(), priorityRanges.count() > 0, slotFirstVertex,
						want.layout(), vertPush, fragPush);
				}
				else
				{
					// Static layers emit plane-major. Draw one plane at a time
					// so roof skips only affect planes above the player; current
					// and lower planes must remain intact or buildings get
					// chopped when their roof id is hidden.
					for (int p = loMin; p <= loMax; p++)
					{
						zoneDrawScheduler.drawStaticPlane(cmd, i, p, layerStart,
							fullZoneRange, minZoneX, maxZoneX, minZoneZ, maxZoneZ,
							skipScratch, skipPairs, p > loCur,
							slot, staticFirstVertex, want.layout(), vertPush, fragPush);
						zoneDrawScheduler.drawOverlayPlane(cmd, i, p, minZoneX, maxZoneX, minZoneZ, maxZoneZ,
							skipScratch, skipPairs, p > loCur,
							slot, slotFirstVertex, want.layout(), vertPush, fragPush);
					}
				}
				layerStart = regionEnd;
			}

			if (priorityRanges.count() > 0)
			{
				vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, priorityColorPipeline.handle());
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
					priorityColorPipeline.layout(), 0, stack.longs(descriptorSet), null);
				if (!repushConstantsEveryDraw)
				{
					drawEmitter.pushConstants(cmd, priorityColorPipeline.layout(), vertPush, fragPush);
				}
				drawPriorityRanges(cmd, slotFirstVertex, priorityColorPipeline.layout(), vertPush, fragPush);

				vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, priorityDepthPipeline.handle());
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
					priorityDepthPipeline.layout(), 0, stack.longs(descriptorSet), null);
				if (!repushConstantsEveryDraw)
				{
					drawEmitter.pushConstants(cmd, priorityDepthPipeline.layout(), vertPush, fragPush);
				}
				drawPriorityRanges(cmd, slotFirstVertex, priorityDepthPipeline.layout(), vertPush, fragPush);
			}

		}
	}

	/**
	 * Blended-alpha phase. Self-contained: rebinds this renderer's vertex
	 * buffer and pushes, so phases of different renderers (toplevel +
	 * sub-worldviews) can interleave in one command buffer.
	 */
	void recordAlpha(VkCommandBuffer cmd, float[] mvp, float brightness,
					float cameraX, float cameraY, float cameraZ, float drawDistanceTiles, float fogDepthTiles,
					float fogR, float fogG, float fogB,
					int tick, float textureLightMode,
					int colorBlindMode, float colorBlindIntensity,
					float smoothBanding,
					int entityTx, int entityTz, int entityYawJau)
	{
		if (singlePassAlpha || vertexCount == 0) return;
		final int slot = sync.currentFrame();
		final int slotFirstVertex = maxStaticVertices + slot * maxFrameVertices - staticVertexCount();
		final int staticFirstVertex = 0;
		try (MemoryStack stack = stackPush())
		{
			vkCmdBindVertexBuffers(cmd, 0,
				stack.longs(vbuf.handle()),
				stack.longs(0L));

			ByteBuffer vertPush = stack.malloc(96);
			Mat4Ops.writeTo(vertPush, mvp);
			vertPush.position(64);
			vertPush.putFloat(cameraX);
			vertPush.putFloat(cameraZ);
			vertPush.putFloat(drawDistanceTiles * 128f);
			vertPush.putFloat(fogDepthTiles * 128f);
			vertPush.putInt(tick).putInt(entityTx).putInt(entityTz).putInt(entityYawJau);
			vertPush.flip();

			ByteBuffer alphaFragPush = stack.malloc(32);
			alphaFragPush.putFloat(fogR).putFloat(fogG).putFloat(fogB).putFloat(brightness);
			alphaFragPush.putFloat(textureLightMode);
			alphaFragPush.putFloat((float) colorBlindMode);
			alphaFragPush.putFloat(colorBlindIntensity);
			alphaFragPush.putFloat(10f + smoothBanding);
			alphaFragPush.flip();

			if (skipScratch.length < roofRanges.requiredSkipPairCapacity())
			{
				skipScratch = java.util.Arrays.copyOf(skipScratch, roofRanges.requiredSkipPairCapacity());
			}
			int skipPairs = roofRanges.buildSkipPairs(hideRoofIds, skipScratch);

			final int loMin = minPlane;
			final int loCur = currentPlane;
			final int loMax = maxPlane;
			// Mirrors recordOpaque's zone-radius culling.
			int radiusTiles = (int) Math.ceil(drawDistanceTiles + fogDepthTiles + 2f);
			int radiusZones = Math.max(1, (radiusTiles + ZONE_SIZE - 1) / ZONE_SIZE);
			boolean fullZoneRange = FULL_SCENE_DRAW;
			int sceneEnd = capturedSceneOrigin + capturedSceneSize;
			int camTileX = clamp((int) Math.floor(cameraX / 128f), capturedSceneOrigin, sceneEnd - 1);
			int camTileZ = clamp((int) Math.floor(cameraZ / 128f), capturedSceneOrigin, sceneEnd - 1);
			int camZoneX = (camTileX - capturedSceneOrigin) / ZONE_SIZE;
			int camZoneZ = (camTileZ - capturedSceneOrigin) / ZONE_SIZE;
			int minZoneX = fullZoneRange ? 0 : Math.max(0, camZoneX - radiusZones);
			int maxZoneX = fullZoneRange ? ZONES_PER_SIDE - 1 : Math.min(ZONES_PER_SIDE - 1, camZoneX + radiusZones);
			int minZoneZ = fullZoneRange ? 0 : Math.max(0, camZoneZ - radiusZones);
			int maxZoneZ = fullZoneRange ? ZONES_PER_SIDE - 1 : Math.min(ZONES_PER_SIDE - 1, camZoneZ + radiusZones);

			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, alphaPipeline.handle());
			vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
				alphaPipeline.layout(), 0, stack.longs(descriptorSet), null);
			if (!repushConstantsEveryDraw)
			{
				drawEmitter.pushConstants(cmd, alphaPipeline.layout(), vertPush, alphaFragPush);
			}
			drawAlphaPass(cmd, loMin, loMax, fullZoneRange, minZoneX, maxZoneX, minZoneZ, maxZoneZ,
				skipScratch, skipPairs, loCur,
				slot, staticFirstVertex, slotFirstVertex, alphaPipeline.layout(), vertPush, alphaFragPush);
		}
	}

	void recordBeforeRenderPass(VkCommandBuffer cmd)
	{
	}

	private int layerStartFor(int layer)
	{
		return layer == 0 ? 0 : regionEnds[layer - 1];
	}

	private void drawAlphaPass(VkCommandBuffer cmd, int loMin, int loMax,
							   boolean fullZoneRange, int minZoneX, int maxZoneX, int minZoneZ, int maxZoneZ,
							   int[] skips, int skipPairs, int currentPlane,
							   int slot, int staticFirstVertex, int slotFirstVertex,
							   long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		for (int i = 0; i < LAYER_COUNT; i++)
		{
			// Only translucent statics and dynamics blend; the opaque static
			// layers were fully drawn (and depth-written) in the opaque phase.
			if (LAYERS[i] != Layer.STATIC_ALPHA && LAYERS[i] != Layer.DYNAMIC)
			{
				continue;
			}
			int layerStart = layerStartFor(i);
			int regionEnd = i == LAYER_COUNT - 1 ? vertexCount : regionEnds[i];
			if (LAYERS[i] == Layer.DYNAMIC)
			{
				int dynamicStart = overlayNextVertex[slot];
				int dynamicEnd = dynamicOpaqueEnd >= dynamicStart ? Math.min(dynamicOpaqueEnd, regionEnd) : regionEnd;
				drawEmitter.drawRange(cmd, dynamicStart, dynamicEnd, priorityRanges.skipPairs(), priorityRanges.count(),
					false, slotFirstVertex, pipelineLayout, vertPush, fragPush);
			}
			else
			{
				if (regionEnd <= layerStart)
				{
					continue;
				}
				for (int p = loMin; p <= loMax; p++)
				{
					zoneDrawScheduler.drawStaticPlane(cmd, i, p, layerStart,
						fullZoneRange, minZoneX, maxZoneX, minZoneZ, maxZoneZ,
						skips, skipPairs, p > currentPlane,
						slot, staticFirstVertex, pipelineLayout, vertPush, fragPush);
					zoneDrawScheduler.drawOverlayPlane(cmd, i, p, minZoneX, maxZoneX, minZoneZ, maxZoneZ,
						skips, skipPairs, p > currentPlane,
						slot, slotFirstVertex, pipelineLayout, vertPush, fragPush);
				}
			}
		}
	}

	private void drawPriorityRanges(VkCommandBuffer cmd, int slotFirstVertex,
									long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		for (int i = 0; i < priorityRanges.count(); i++)
		{
			int start = priorityRanges.start(i);
			int end = priorityRanges.end(i);
			if (end <= start)
			{
				continue;
			}
			drawEmitter.drawRange(cmd, start, end, skipScratch, 0, false,
				slotFirstVertex, pipelineLayout, vertPush, fragPush);
		}
	}

	private static int clamp(int value, int min, int max)
	{
		return value < min ? min : (value > max ? max : value);
	}

	private Tile[][][] captureTiles(Scene scene)
	{
		Tile[][][] tiles = null;
		tileLookupOffset = 0;
		capturedSceneOrigin = 0;
		capturedSceneSize = Constants.SCENE_SIZE;

		if (scene.getWorldViewId() == net.runelite.api.WorldView.TOPLEVEL)
		{
			Tile[][][] extended = scene.getExtendedTiles();
			if (canCoverScene(extended, 0, Constants.EXTENDED_SCENE_SIZE))
			{
				tiles = extended;
				tileLookupOffset = SCENE_OFFSET;
				capturedSceneOrigin = -SCENE_OFFSET;
				capturedSceneSize = Constants.EXTENDED_SCENE_SIZE;
			}
		}

		if (tiles == null)
		{
			Tile[][][] regular = scene.getTiles();
			if (canCoverScene(regular, 0, Constants.SCENE_SIZE))
			{
				tiles = regular;
				tileLookupOffset = 0;
				capturedSceneOrigin = 0;
				capturedSceneSize = Constants.SCENE_SIZE;
			}
		}

		return tiles;
	}

	private static boolean canCoverScene(Tile[][][] tiles, int offset, int size)
	{
		if (tiles == null || tiles.length == 0)
		{
			return false;
		}
		int required = offset + size;
		for (Tile[][] plane : tiles)
		{
			if (plane == null)
			{
				continue;
			}
			if (plane.length < required)
			{
				continue;
			}
			for (int x = offset; x < required; x++)
			{
				Tile[] row = plane[x];
				if (row != null && row.length >= required)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static Tile tileAt(Tile[][][] tiles, int plane, int x, int y)
	{
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return null;
		}
		Tile[][] tilePlane = tiles[plane];
		if (tilePlane == null || x < 0 || x >= tilePlane.length)
		{
			return null;
		}
		Tile[] row = tilePlane[x];
		return row != null && y >= 0 && y < row.length ? row[y] : null;
	}

	private static String tileDims(Tile[][][] tiles)
	{
		if (tiles == null)
		{
			return "null";
		}
		int x = 0;
		int y = 0;
		for (Tile[][] plane : tiles)
		{
			if (plane == null)
			{
				continue;
			}
			x = Math.max(x, plane.length);
			for (Tile[] row : plane)
			{
				if (row != null)
				{
					y = Math.max(y, row.length);
				}
			}
		}
		return tiles.length + "x" + x + "x" + y;
	}

	private void recordRoofRange(int roofId, int vertexStart, int vertexCount)
	{
		if (rebuildingOverlayZone)
		{
			return;
		}
		roofRanges.record(roofId, vertexStart, vertexCount);
	}

	private int countOverlayDirtyZones(int slot)
	{
		int count = 0;
		for (int zoneIdx = 0; zoneIdx < ZONE_COUNT; zoneIdx++)
		{
			if (overlayZoneValid[slot][zoneIdx])
			{
				count++;
			}
		}
		return count;
	}

	int vertexCount() { return vertexCount; }

	@Override
	public int currentVertexCount()
	{
		return vertexCount;
	}

	@Override
	public void addVertices(int vertices)
	{
		vertexCount += vertices;
	}

	@Override
	public boolean canAppendVertices(int pendingVertices, int vertices)
	{
		if (vertices < 0 || vertexCount + pendingVertices > writeVertexLimit - vertices)
		{
			overflow();
			return false;
		}
		return true;
	}

	@Override
	public void close()
	{
		vkDeviceWaitIdle(device.handle());
		vbuf.close();
		fillPipeline.close();
		alphaPipeline.close();
		skyboxPipeline.close();
		priorityColorPipeline.close();
		priorityDepthPipeline.close();
		if (linePipeline != null) linePipeline.close();
	}

	private void writeRotatedVertex(float lx, float ly, float lz,
									float cos, float sin,
									int wx, int wy, int wz,
									int hsl16, float u, float v, int texLayer)
	{
		float rx = lx * cos + lz * sin;
		float rz = -lx * sin + lz * cos;
		writePackedVertex(rx + wx, ly + wy, rz + wz, hsl16, u, v, texLayer);
	}

	@Override
	public void writeRotatedVertexRgb(float lx, float ly, float lz,
									   float cos, float sin,
									   int wx, int wy, int wz,
									   float light, float r, float g, float b,
									   float u, float v, int texLayer)
	{
		float rx = lx * cos + lz * sin;
		float rz = -lx * sin + lz * cos;
		writePackedVertexRgb(rx + wx, ly + wy, rz + wz, light, r, g, b, u, v, texLayer);
	}

	@Override
	public void writeRotatedVertexRgbNoUv(float lx, float ly, float lz,
										   float cos, float sin,
										   int wx, int wy, int wz,
										   float light, float r, float g, float b,
										   int texLayer)
	{
		float rx = lx * cos + lz * sin;
		float rz = -lx * sin + lz * cos;
		writePackedVertexRgbNoUv(rx + wx, ly + wy, rz + wz, light, r, g, b, texLayer);
	}

	@Override
	public void writeHslVert(float x, float y, float z, int hsl16, float u, float v, int texLayer)
	{
		writePackedVertex(x, y, z, hsl16, u, v, texLayer);
	}

	private void writePackedVertex(float x, float y, float z, int hsl16, float u, float v, int texLayer)
	{
		writePackedVertexPacked(x, y, z, hsl16, u, v, texLayer);
	}

	@Override
	public void writePackedVertexRgb(float x, float y, float z,
									  float light, float r, float g, float b,
									  float u, float v, int texLayer)
	{
		writePackedVertexPacked(x, y, z, (int) light, u, v, texLayer);
	}

	@Override
	public void writePackedVertexRgbNoUv(float x, float y, float z,
										  float light, float r, float g, float b,
										  int texLayer)
	{
		writePackedVertexPacked(x, y, z, (int) light, 0f, 0f, texLayer);
	}

	@Override
	public void writePackedTriangleRgbNoUv(float x0, float y0, float z0,
											float x1, float y1, float z1,
											float x2, float y2, float z2,
											float light, float r, float g, float b,
											int texLayer)
	{
		long p = writePtr;
		writePackedVertexRgbNoUvAt(p, x0, y0, z0, light, r, g, b, texLayer);
		p += ScenePipeline.VERTEX_STRIDE;
		writePackedVertexRgbNoUvAt(p, x1, y1, z1, light, r, g, b, texLayer);
		p += ScenePipeline.VERTEX_STRIDE;
		writePackedVertexRgbNoUvAt(p, x2, y2, z2, light, r, g, b, texLayer);
		writePtr = p + ScenePipeline.VERTEX_STRIDE;
	}

	private static void writePackedVertexRgbNoUvAt(long p, float x, float y, float z,
												   float light, float r, float g, float b,
												   int texLayer)
	{
		SceneVertexPacker.writePacked(p, x, y, z, (int) light, 0f, 0f, texLayer);
	}

	private void writePackedVertexPacked(float x, float y, float z, int hsl16, float u, float v, int texLayer)
	{
		long p = writePtr;
		SceneVertexPacker.writePacked(p, x, y, z, hsl16, u, v, texLayer);
		writePtr = p + ScenePipeline.VERTEX_STRIDE;
	}

	private void overflow()
	{
		if (!overflowed)
		{
			log.warn("Scene vertex buffer full ({} verts), dropping further captures", vertexCount);
			overflowed = true;
		}
	}
}
