/*
 * The {@code computeFaceUvs} helper near the bottom of this file is ported
 * verbatim from RuneLite's
 * {@code net.runelite.client.plugins.gpu.SceneUploader.computeFaceUvs}
 * (BSD-2-Clause). Original copyright + license:
 *
 *   Copyright (c) 2018, Adam <Adam@sigterm.info>
 *   All rights reserved.
 *
 *   Redistribution and use in source and binary forms, with or without
 *   modification, are permitted provided that the following conditions are met:
 *
 *   1. Redistributions of source code must retain the above copyright notice, this
 *      list of conditions and the following disclaimer.
 *   2. Redistributions in binary form must reproduce the above copyright notice,
 *      this list of conditions and the following disclaimer in the documentation
 *      and/or other materials provided with the distribution.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 *   ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *   WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *   DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 *   ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *   (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *   ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *   (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Other methods are original to this project, even where their behaviour
 * mirrors stock GpuPlugin (e.g. tile-paint capture). They were re-derived
 * from the Scene / Tile / SceneTilePaint / SceneTileModel public API and the
 * stock comments only.
 */
package net.runelite.client.plugins.gpuvulkan;

import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Captures OSRS scene geometry into one host-visible vertex buffer that's split
 * into six contiguous regions, one per {@link Layer}. The five static layers
 * (TERRAIN..GAME_OBJECTS) are populated by {@link #captureScene} on scene load;
 * the DYNAMIC layer is refilled each frame by {@link #captureModel}.
 *
 * <p>{@link #recordDraw} issues one draw per non-empty layer, picking the fill
 * or line pipeline based on each layer's individual wireframe flag, and binds
 * the OSRS texture array as descriptor set 0.
 *
 * <p>Vertex layout (matches {@link ScenePipeline}):
 * {@code [posX posY posZ colorR colorG colorB u v texLayer]} as 8 floats + 1
 * uint per vertex (36 bytes).
 */
@Slf4j
final class SceneRenderer implements AutoCloseable
{
	enum Layer
	{
		TERRAIN, WALLS, DECORATIVE, GROUND, GAME_OBJECTS, DYNAMIC;
	}
	private static final Layer[] LAYERS = Layer.values();
	private static final int LAYER_COUNT = LAYERS.length;
	private static final Layer LAST_STATIC = Layer.GAME_OBJECTS;

	private static final int MAX_VERTICES = 8_000_000;
	private static final long BUFFER_BYTES = (long) MAX_VERTICES * ScenePipeline.VERTEX_STRIDE;
	private static final int HSL_HIDDEN = 12345678;
	private static final int OPAQUE_UNSORTED_FACE_THRESHOLD = 24;
	private static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
	private static final float[] HSL_RGB = buildHslRgbTable();

	private final VulkanDevice device;
	private final FrameSync sync;
	private final ScenePipeline fillPipeline;
	private final ScenePipeline linePipeline;
	private final SceneVertexBuffer vbuf;
	private final ByteBuffer mapped;
	private final long descriptorSet;
	private final int slotBytes;
	private final DrawCallbackStats stats;
	private long writePtr;

	private static final int MAX_PLANES = 4;

	/** OSRS engine convention: SCENE_SIZE / ZONE_SIZE = 13 zones per side. */
	static final int ZONE_SIZE = 8;
	static final int ZONES_PER_SIDE = Constants.SCENE_SIZE / ZONE_SIZE;
	static final int ZONE_COUNT = ZONES_PER_SIDE * ZONES_PER_SIDE;

	private final int[] regionEnds = new int[LAYER_COUNT];
	/** Per-layer, per-plane: vertex count after that plane's tiles emitted.
	 *  Used to clip at {@code planeEnds[layer][maxPlane]} so upper-plane
	 *  geometry is culled when the player is below. */
	private final int[][] planeEnds = new int[LAYER_COUNT][MAX_PLANES];

	/** Per (layer, plane, zone) vertex ranges within the static region.
	 *  Populated by {@link #captureScene}; currently unused but reserved
	 *  for future per-zone re-emission. */
	private final int[][][] zoneVertexStart = new int[LAYER_COUNT][MAX_PLANES][ZONE_COUNT];
	private final int[][][] zoneVertexCount = new int[LAYER_COUNT][MAX_PLANES][ZONE_COUNT];

	private final boolean[] wireframe = new boolean[LAYER_COUNT];
	/** Plane range to render this frame, forwarded from preSceneDraw's
	 *  (minLevel, maxLevel). */
	private volatile int minPlane = 0;
	private volatile int maxPlane = MAX_PLANES - 1;

	/** Engine-supplied per-frame set of tile-roof IDs (NOT GameObject IDs,
	 *  values come from {@link net.runelite.api.Scene#getRoofs()}) to hide
	 *  above the player. */
	private volatile java.util.Set<Integer> hideRoofIds = java.util.Collections.emptySet();

	/** Per-tile-range roof tags recorded during {@link #captureScene}.
	 *  At draw time the layer is split into render/skip sub-ranges based
	 *  on the current frame's {@link #hideRoofIds}. */
	private int[] tileRoofIds      = new int[2048];
	private int[] tileRoofStarts   = new int[2048];
	private int[] tileRoofCounts   = new int[2048];
	private int   tileRoofCount;
	/** Sorted [start, end) pairs of vertex sub-ranges to skip this frame. */
	private int[] skipScratch = new int[256];

	private int vertexCount;
	private boolean overflowed;

	/** Renderables whose Model came back null during {@link #captureScene}
	 *  — usually because the engine hasn't finished streaming the model
	 *  yet on a freshly-loaded region. {@link #captureDynamicPending}
	 *  retries each entry every frame and emits the loaded ones into the
	 *  dynamic suffix; no GPU drain or static-buffer rewrite needed. */
	private static final class PendingRenderable
	{
		final Renderable r;
		final int orient, x, y, z;
		PendingRenderable(Renderable r, int orient, int x, int y, int z)
		{
			this.r = r;
			this.orient = orient;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}
	private final java.util.ArrayList<PendingRenderable> pendingRenderables = new java.util.ArrayList<>();
	private static final int MAX_PENDING = 4096;

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
		tileRoofCount = 0;
		pendingRenderables.clear();
		for (int i = 0; i < LAYER_COUNT; i++)
		{
			regionEnds[i] = 0;
			for (int p = 0; p < MAX_PLANES; p++) planeEnds[i][p] = 0;
		}
	}

	/** Single instance reused across captures — all captures run on the
	 *  Client thread. */
	private final ModelSorter sorter = new ModelSorter();

	SceneRenderer(VulkanDevice device, FrameSync sync,
		RenderPass renderPass, TextureArray textureArray,
		DrawCallbackStats stats)
	{
		this.device = device;
		this.sync = sync;
		this.stats = stats;
		this.fillPipeline = new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL, true, renderPass.samples());
		// fillModeNonSolid is required for VK_POLYGON_MODE_LINE; without it
		// (llvmpipe, some embedded SoCs) wireframe collapses to FILL.
		this.linePipeline = device.supportsFillModeNonSolid()
			? new ScenePipeline(device, renderPass, VK_POLYGON_MODE_LINE, true, renderPass.samples())
			: null;
		this.vbuf = new SceneVertexBuffer(device, BUFFER_BYTES, FrameSync.FRAMES_IN_FLIGHT,
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
		minPlane = Math.max(0, Math.min(MAX_PLANES - 1, minLevel));
		maxPlane = Math.max(minPlane, Math.min(MAX_PLANES - 1, maxLevel));
	}

	void setHideRoofIds(java.util.Set<Integer> ids)
	{
		hideRoofIds = ids != null ? ids : java.util.Collections.emptySet();
	}

	/** Per-frame (Model identity, worldX, worldZ) dedupe — collapses the
	 *  "two heads on the player" case where multiple capture paths emit
	 *  the same actor model at the same position. */
	private final java.util.HashSet<Long> seenCaptures = new java.util.HashSet<>();

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
			vkWaitForFences(device.handle(), stack.longs(sync.inFlightFence()), true, Long.MAX_VALUE);
		}
		vertexCount = regionEnds[LAST_STATIC.ordinal()];
		writePtr = MemoryUtil.memAddress(mapped)
			+ (long) sync.currentFrame() * slotBytes
			+ (long) vertexCount * ScenePipeline.VERTEX_STRIDE;
		overflowed = false;
		seenCaptures.clear();
	}

	/** Visits one tile (including any bridge tile underneath) within a layer pass. */
	@FunctionalInterface
	private interface TileCapture
	{
		void capture(Tile tile, int plane, int sx, int sy);
	}

	/**
	 * Walks every tile and emits all static geometry — terrain, walls,
	 * decorative objects, ground objects, and static game objects — into their
	 * respective layer regions.
	 */
	void captureScene(Scene scene)
	{
		// Drain the GPU before rewriting the buffer; captureScene is rare
		// (region change) and the buffer is shared across all in-flight frames.
		vkDeviceWaitIdle(device.handle());

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
		writePtr = MemoryUtil.memAddress(mapped);
		overflowed = false;
		tileRoofCount = 0;
		pendingRenderables.clear();

		Tile[][][] tiles = scene.getTiles();
		if (tiles == null) return;
		final int planes = Math.min(tiles.length, MAX_PLANES);
		final int sceneSize = Constants.SCENE_SIZE;

		// Scene.getRoofs() dims are EXTENDED_SCENE_SIZE (184) on toplevel,
		// not SCENE_SIZE (104) like scene.getTiles(). Instances aren't
		// extended, so the offset is 0 there.
		final int[][][] roofs = scene.getRoofs();
		final int roofOffset = scene.getWorldViewId() == net.runelite.api.WorldView.TOPLEVEL
			? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 : 0;

		// Pass order: emit each layer plane-by-plane so vertices are sorted
		// (layer-major, plane-minor). recordDraw clips per layer at
		// planeEnds[layer][visiblePlane] to hide roofs above the player.
		captureLayer(Layer.TERRAIN, tiles, planes, sceneSize, roofs, roofOffset,
			(cur, p, sx, sy) ->
			{
				SceneTilePaint paint = cur.getSceneTilePaint();
				if (paint != null) captureTilePaint(scene, paint, p, sx, sy);
				SceneTileModel m = cur.getSceneTileModel();
				if (m != null) captureTileModel(m, sx, sy);
			});

		captureLayer(Layer.WALLS, tiles, planes, sceneSize, roofs, roofOffset,
			(cur, p, sx, sy) ->
			{
				WallObject w = cur.getWallObject();
				if (w == null) return;
				captureRenderable(w.getRenderable1(), 0, w.getX(), w.getZ(), w.getY());
				captureRenderable(w.getRenderable2(), 0, w.getX(), w.getZ(), w.getY());
			});

		captureLayer(Layer.DECORATIVE, tiles, planes, sceneSize, roofs, roofOffset,
			(cur, p, sx, sy) ->
			{
				DecorativeObject d = cur.getDecorativeObject();
				if (d == null) return;
				captureRenderable(d.getRenderable(),  0,
					d.getX() + d.getXOffset(),  d.getZ(), d.getY() + d.getYOffset());
				captureRenderable(d.getRenderable2(), 0,
					d.getX() + d.getXOffset2(), d.getZ(), d.getY() + d.getYOffset2());
			});

		captureLayer(Layer.GROUND, tiles, planes, sceneSize, roofs, roofOffset,
			(cur, p, sx, sy) ->
			{
				GroundObject g = cur.getGroundObject();
				if (g == null) return;
				captureRenderable(g.getRenderable(), 0, g.getX(), g.getZ(), g.getY());
			});

		captureGameObjectsLayer(tiles, planes, sceneSize, roofs, roofOffset);

		// Pad planeEnds past `planes` so max-plane lookups still draw
		// everything if requested.
		for (int i = 0; i < LAYER_COUNT; i++)
			for (int p = planes; p < MAX_PLANES; p++)
				planeEnds[i][p] = regionEnds[i];

		copyStaticPrefixToAllSlots(vertexCount * ScenePipeline.VERTEX_STRIDE);

		log.debug("captureScene: {} vertices, {} roof tiles", vertexCount, tileRoofCount);
	}

	/** Mirror static prefix from slot 0 into every other slot so all
	 *  FRAMES_IN_FLIGHT bindings see identical static geometry. */
	private void copyStaticPrefixToAllSlots(int staticBytes)
	{
		if (staticBytes <= 0) return;
		long base = MemoryUtil.memAddress(mapped, 0);
		for (int slot = 1; slot < FrameSync.FRAMES_IN_FLIGHT; slot++)
		{
			MemoryUtil.memCopy(base, base + (long) slot * slotBytes, staticBytes);
		}
	}

	/**
	 * Walks every tile of {@code layer} plane-by-plane (zone-major,
	 * tile-minor within a zone), invoking {@code body} once per tile and
	 * once per bridge tile underneath. Vertex deltas are recorded into
	 * the roof-skip table via {@link #recordRoofRange}. Bridge recursion
	 * picks up stacked structures (arches, decks).
	 */
	private void captureLayer(Layer layer,
		Tile[][][] tiles, int planes, int sceneSize,
		int[][][] roofs, int roofOffset,
		TileCapture body)
	{
		final int L = layer.ordinal();
		for (int p = 0; p < planes; p++)
		{
			for (int zx = 0; zx < ZONES_PER_SIDE; zx++)
			{
				for (int zy = 0; zy < ZONES_PER_SIDE; zy++)
				{
					int zoneStart = vertexCount;
					int x0 = zx * ZONE_SIZE, x1 = Math.min(x0 + ZONE_SIZE, sceneSize);
					int y0 = zy * ZONE_SIZE, y1 = Math.min(y0 + ZONE_SIZE, sceneSize);
					for (int sx = x0; sx < x1; sx++)
					{
						for (int sy = y0; sy < y1; sy++)
						{
							Tile t = tiles[p][sx][sy];
							if (t == null) continue;
							int roofId = tileRoofIdAt(roofs, p, sx + roofOffset, sy + roofOffset);
							Tile cur = t;
							while (cur != null)
							{
								int before = vertexCount;
								body.capture(cur, p, sx, sy);
								if (roofId != 0 && vertexCount > before)
									recordRoofRange(roofId, before, vertexCount - before);
								cur = (cur == t) ? t.getBridge() : null;
							}
						}
					}
					int zoneIdx = zx * ZONES_PER_SIDE + zy;
					zoneVertexStart[L][p][zoneIdx] = zoneStart;
					zoneVertexCount[L][p][zoneIdx] = vertexCount - zoneStart;
				}
			}
			planeEnds[L][p] = vertexCount;
		}
		regionEnds[L] = vertexCount;
	}

	/** GameObjects emit only on the sceneMinLocation tile (multi-tile
	 *  objects naturally dedupe), and each gets its own roof-range entry
	 *  instead of a merged-per-tile range. */
	private void captureGameObjectsLayer(Tile[][][] tiles, int planes, int sceneSize,
		int[][][] roofs, int roofOffset)
	{
		final int L = Layer.GAME_OBJECTS.ordinal();
		for (int p = 0; p < planes; p++)
		{
			for (int zx = 0; zx < ZONES_PER_SIDE; zx++)
			{
				for (int zy = 0; zy < ZONES_PER_SIDE; zy++)
				{
					int zoneStart = vertexCount;
					int x0 = zx * ZONE_SIZE, x1 = Math.min(x0 + ZONE_SIZE, sceneSize);
					int y0 = zy * ZONE_SIZE, y1 = Math.min(y0 + ZONE_SIZE, sceneSize);
					for (int sx = x0; sx < x1; sx++)
					{
						for (int sy = y0; sy < y1; sy++)
						{
							Tile t = tiles[p][sx][sy];
							if (t == null) continue;
							int roofId = tileRoofIdAt(roofs, p, sx + roofOffset, sy + roofOffset);
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
									if (roofId != 0 && vertexCount > before)
										recordRoofRange(roofId, before, vertexCount - before);
								}
								cur = (cur == t) ? t.getBridge() : null;
							}
						}
					}
					int zoneIdx = zx * ZONES_PER_SIDE + zy;
					zoneVertexStart[L][p][zoneIdx] = zoneStart;
					zoneVertexCount[L][p][zoneIdx] = vertexCount - zoneStart;
				}
			}
			planeEnds[L][p] = vertexCount;
		}
		regionEnds[L] = vertexCount;
	}

	private void captureRenderable(Renderable r, int orient, int x, int y, int z)
	{
		if (r == null) return;
		Model m = resolveModel(r);
		if (m == null)
		{
			// Model not streamed yet — queue for per-frame retry in
			// captureDynamicPending, no static-buffer rewrite needed.
			if (pendingRenderables.size() < MAX_PENDING)
			{
				pendingRenderables.add(new PendingRenderable(r, orient, x, y, z));
			}
			return;
		}
		captureModel(m, orient, x, y, z);
	}

	/** DynamicObject.getModelZbuf is thread-safe and returns the
	 *  non-animated baseline; plain getModel() can return a half-animated
	 *  model or null mid-update. */
	private static Model resolveModel(Renderable r)
	{
		if (r instanceof Model) return (Model) r;
		if (r instanceof net.runelite.api.DynamicObject) return ((net.runelite.api.DynamicObject) r).getModelZbuf();
		return r.getModel();
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
		if (pendingRenderables.isEmpty()) return;
		for (int i = 0, n = pendingRenderables.size(); i < n; i++)
		{
			PendingRenderable pr = pendingRenderables.get(i);
			Model m = resolveModel(pr.r);
			if (m == null) continue;
			captureModel(m, pr.orient, pr.x, pr.y, pr.z);
		}
	}

	private void captureTilePaint(Scene scene, SceneTilePaint paint, int plane, int sx, int sy)
	{
		int neColor = paint.getNeColor();
		if (neColor == HSL_HIDDEN) return;

		int swColor = paint.getSwColor();
		int seColor = paint.getSeColor();
		int nwColor = paint.getNwColor();

		int[][][] tileHeights = scene.getTileHeights();
		int ex = sx + SCENE_OFFSET;
		int ey = sy + SCENE_OFFSET;
		int swH = tileHeights[plane][ex    ][ey    ];
		int seH = tileHeights[plane][ex + 1][ey    ];
		int neH = tileHeights[plane][ex + 1][ey + 1];
		int nwH = tileHeights[plane][ex    ][ey + 1];

		int x0 = sx << 7, x1 = x0 + 128;
		int z0 = sy << 7, z1 = z0 + 128;

		// paint.getTexture() = -1 for no-texture, otherwise OSRS texture
		// id. Texture array layer 0 is white, so +1 maps cleanly.
		int texLayer = paint.getTexture() + 1;

		if (vertexCount + 6 > MAX_VERTICES) { overflow(); return; }

		writeHslVert(x0, swH, z0, swColor, 0f, 0f, texLayer);
		writeHslVert(x1, seH, z0, seColor, 1f, 0f, texLayer);
		writeHslVert(x1, neH, z1, neColor, 1f, 1f, texLayer);

		writeHslVert(x0, swH, z0, swColor, 0f, 0f, texLayer);
		writeHslVert(x1, neH, z1, neColor, 1f, 1f, texLayer);
		writeHslVert(x0, nwH, z1, nwColor, 0f, 1f, texLayer);

		vertexCount += 6;
	}

	private void captureTileModel(SceneTileModel model, int sx, int sy)
	{
		int[] faceX = model.getFaceX();
		int[] faceY = model.getFaceY();
		int[] faceZ = model.getFaceZ();
		if (faceX == null) return;

		int[] vertexX = model.getVertexX();
		int[] vertexY = model.getVertexY();
		int[] vertexZ = model.getVertexZ();
		int[] colorA = model.getTriangleColorA();
		int[] colorB = model.getTriangleColorB();
		int[] colorC = model.getTriangleColorC();
		int[] triangleTextures = model.getTriangleTextureId();

		// UV = local_offset / 128 (one tile = 128 OSRS units).
		float lx = sx << 7;
		float lz = sy << 7;

		int faces = faceX.length;
		if (vertexCount + faces * 3 > MAX_VERTICES) { overflow(); return; }

		for (int i = 0; i < faces; i++)
		{
			int a = colorA[i];
			if (a == HSL_HIDDEN) continue;
			int b = colorB[i], c = colorC[i];
			int v0 = faceX[i], v1 = faceY[i], v2 = faceZ[i];
			int texLayer = triangleTextures != null && triangleTextures[i] != -1
				? triangleTextures[i] + 1 : 0;
			float u0 = (vertexX[v0] - lx) / 128f, w0 = (vertexZ[v0] - lz) / 128f;
			float u1 = (vertexX[v1] - lx) / 128f, w1 = (vertexZ[v1] - lz) / 128f;
			float u2 = (vertexX[v2] - lx) / 128f, w2 = (vertexZ[v2] - lz) / 128f;
			writeHslVert(vertexX[v0], vertexY[v0], vertexZ[v0], a, u0, w0, texLayer);
			writeHslVert(vertexX[v1], vertexY[v1], vertexZ[v1], b, u1, w1, texLayer);
			writeHslVert(vertexX[v2], vertexY[v2], vertexZ[v2], c, u2, w2, texLayer);
			vertexCount += 3;
		}
	}

	void captureModel(Model m, int orient, int worldX, int worldY, int worldZ)
	{
		if (m == null || !markCaptureSeen(m, worldX, worldZ)) return;
		captureModelUnsorted(m, orient, worldX, worldY, worldZ);
	}

	private boolean markCaptureSeen(Model m, int worldX, int worldZ)
	{
		long key = ((long) System.identityHashCode(m) & 0xFFFFFFFFL)
			| ((long) (worldX & 0xFFFF) << 32)
			| ((long) (worldZ & 0xFFFF) << 48);
		return seenCaptures.add(key);
	}

	private void captureModelUnsorted(Model m, int orient, int worldX, int worldY, int worldZ)
	{
		long emitStart = System.nanoTime();
		float[] vx = m.getVerticesX();
		float[] vy = m.getVerticesY();
		float[] vz = m.getVerticesZ();
		int[] fa = m.getFaceIndices1();
		int[] fb = m.getFaceIndices2();
		int[] fc = m.getFaceIndices3();
		if (fa == null || fb == null || fc == null) return;

		int[] c1 = m.getFaceColors1();
		int[] c2 = m.getFaceColors2();
		int[] c3 = m.getFaceColors3();

		short[] faceTextures   = m.getFaceTextures();
		byte[]  textureFaces   = m.getTextureFaces();
		int[]   texIndicesA    = m.getTexIndices1();
		int[]   texIndicesB    = m.getTexIndices2();
		int[]   texIndicesC    = m.getTexIndices3();
		byte[]  faceTransparencies = m.getFaceTransparencies();
		byte[]  faceBias = m.getFaceBias();

		final byte overrideAmount = m.getOverrideAmount();
		final byte overrideHue    = m.getOverrideHue();
		final byte overrideSat    = m.getOverrideSaturation();
		final byte overrideLum    = m.getOverrideLuminance();
		final boolean hasOverride = (overrideAmount & 0xFF) != 0;

		float cos = Perspective.COSINE[orient & 0x7FF] / 65536f;
		float sin = Perspective.SINE[orient & 0x7FF] / 65536f;

		// LANDMINE: Mesh.getFaceCount(), NOT fa.length. Engine over-allocates
		// face index arrays for assembled actor models (player composition,
		// reused NPC bodies); fa.length reads stale trailing data.
		int faces = m.getFaceCount();
		if (vertexCount + faces * 3 > MAX_VERTICES) { overflow(); return; }

		float[] uv = uvScratch;
		int wrote = 0;
		for (int f = 0; f < faces; f++)
		{
			int col1 = c1 != null ? c1[f] : 0;
			int col2 = col1, col3 = col1;
			if (c3 != null)
			{
				int raw3 = c3[f];
				if (raw3 == -2) continue;
				if (raw3 != -1)
				{
					col2 = c2[f];
					col3 = raw3;
				}
			}

			// Don't skip on transparency here — cloth drapes / glass /
			// water need to render even without a real alpha pass. The
			// engine's invisible sentinel (255) is handled in scene.frag.

			int texLayer = 0;
			float u0 = 0, v0 = 0, u1 = 0, v1 = 0, u2 = 0, v2 = 0;
			if (faceTextures != null && faceTextures[f] != -1)
			{
				texLayer = (faceTextures[f] & 0xFFFF) + 1;
				computeFaceUvs(uv, vx, vy, vz, fa[f], fb[f], fc[f],
					textureFaces, texIndicesA, texIndicesB, texIndicesC, f);
				u0 = uv[0]; v0 = uv[1];
				u1 = uv[2]; v1 = uv[3];
				u2 = uv[4]; v2 = uv[5];
			}

			// HSL override: per-vertex so smooth-banding interpolation
			// picks up the tint across the face. Untextured only —
			// mirrors stock SceneUploader.uploadTempModel scoping.
			if (hasOverride && texLayer == 0)
			{
				col1 = applyHslOverride(col1, overrideHue, overrideSat, overrideLum, overrideAmount);
				col2 = applyHslOverride(col2, overrideHue, overrideSat, overrideLum, overrideAmount);
				col3 = applyHslOverride(col3, overrideHue, overrideSat, overrideLum, overrideAmount);
			}

			// Pack [texLayer:16 | bias:8 | trans:8] = stock's alphaBias.
			int bias = faceBias != null ? (faceBias[f] & 0xFF) : 0;
			int trans = faceTransparencies != null ? (faceTransparencies[f] & 0xFF) : 0;
			int packedTexLayer = texLayer | (bias << 16) | (trans << 24);

			writeRotatedVertex(vx[fa[f]], vy[fa[f]], vz[fa[f]], cos, sin, worldX, worldY, worldZ, col1, u0, v0, packedTexLayer);
			writeRotatedVertex(vx[fb[f]], vy[fb[f]], vz[fb[f]], cos, sin, worldX, worldY, worldZ, col2, u1, v1, packedTexLayer);
			writeRotatedVertex(vx[fc[f]], vy[fc[f]], vz[fc[f]], cos, sin, worldX, worldY, worldZ, col3, u2, v2, packedTexLayer);
			wrote += 3;
		}
		vertexCount += wrote;
		stats.unsortedModels.incrementAndGet();
		stats.unsortedFaces.addAndGet(wrote / 3);
		stats.addNanos(stats.modelEmitNanos, emitStart);
	}

	/**
	 * Sorted variant of {@link #captureModel} — runs the model through
	 * {@link ModelSorter} (camera-space depth bucketing + back-face cull),
	 * then emits the model's triangles back-to-front. If sorting rejects the
	 * model or emits no faces, this falls back to the unsorted path so
	 * transient effects don't disappear.
	 * Use this from the engine's {@code drawDynamic} / {@code drawTemp}
	 * callbacks where a {@link Projection} is supplied; the existing
	 * unsorted {@link #captureModel} stays in place for the actor walk,
	 * which has no Projection of its own.
	 *
	 * <p>Same dedupe behavior as {@code captureModel} (Model identity +
	 * world XZ).
	 */
	void captureModelSorted(Projection proj, Model m, int orient, int worldX, int worldY, int worldZ)
	{
		captureModelSorted(proj, m, orient, worldX, worldY, worldZ, Renderable.RENDERMODE_DEFAULT);
	}

	void captureModelSorted(Projection proj, Model m, int orient, int worldX, int worldY, int worldZ, int renderMode)
	{
		if (m == null || proj == null) return;
		if (!markCaptureSeen(m, worldX, worldZ)) return;

		boolean needsFaceSort = renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH;
		if (!needsFaceSort && m.getFaceCount() <= OPAQUE_UNSORTED_FACE_THRESHOLD)
		{
			captureModelUnsorted(m, orient, worldX, worldY, worldZ);
			return;
		}

		long sortStart = System.nanoTime();
		boolean sorted = needsFaceSort
			? sorter.sort(proj, m, orient, worldX, worldY, worldZ)
			: sorter.cullOnly(proj, m, orient, worldX, worldY, worldZ);
		stats.addNanos(needsFaceSort ? stats.modelFullSortNanos : stats.modelCullOnlyNanos, sortStart);
		stats.addNanos(stats.modelSortNanos, sortStart);
		if (!sorted)
		{
			// Sorting is a quality pass, not a visibility gate. Some transient
			// renderables (projectiles / spotanims) have bounds that make the
			// sorter reject them; keep them visible by falling back to the basic
			// model emitter.
			stats.sortFallbackModels.incrementAndGet();
			captureModelUnsorted(m, orient, worldX, worldY, worldZ);
			return;
		}

		int faces = sorter.sortedCount;
		if (faces == 0)
		{
			if (!needsFaceSort)
			{
				return;
			}
			stats.sortFallbackModels.incrementAndGet();
			captureModelUnsorted(m, orient, worldX, worldY, worldZ);
			return;
		}
		if (vertexCount + faces * 3 > MAX_VERTICES) { overflow(); return; }

		// Vertex positions are read by computeFaceUvs only; emit uses the
		// sorter's already-rotated localX/Y/Z.
		float[] vxs = m.getVerticesX();
		float[] vys = m.getVerticesY();
		float[] vzs = m.getVerticesZ();
		int[] fa = m.getFaceIndices1();
		int[] fb = m.getFaceIndices2();
		int[] fc = m.getFaceIndices3();

		int[] c1 = m.getFaceColors1();
		int[] c2 = m.getFaceColors2();
		int[] c3 = m.getFaceColors3();

		short[] faceTextures   = m.getFaceTextures();
		byte[]  textureFaces   = m.getTextureFaces();
		int[]   texIndicesA    = m.getTexIndices1();
		int[]   texIndicesB    = m.getTexIndices2();
		int[]   texIndicesC    = m.getTexIndices3();
		byte[]  faceTransparencies = m.getFaceTransparencies();
		byte[]  faceBiasArr = m.getFaceBias();

		final byte overrideAmount = m.getOverrideAmount();
		final byte overrideHue    = m.getOverrideHue();
		final byte overrideSat    = m.getOverrideSaturation();
		final byte overrideLum    = m.getOverrideLuminance();
		final boolean hasOverride = (overrideAmount & 0xFF) != 0;

		float[] uv = uvScratch;

		// Sorter applies orientation + worldXYZ; positions are world-space.
		float[] lx = sorter.localX;
		float[] ly = sorter.localY;
		float[] lz = sorter.localZ;

		int wrote = 0;
		long emitStart = System.nanoTime();
		for (int i = 0; i < faces; i++)
		{
			int f = sorter.sortedFaces[i];

			// Sorter has already dropped c3 == -2 faces, so raw3 is
			// never the skip sentinel here.
			int col1 = c1 != null ? c1[f] : 0;
			int col2 = col1, col3 = col1;
			if (c3 != null)
			{
				int raw3 = c3[f];
				if (raw3 != -1)
				{
					col2 = c2[f];
					col3 = raw3;
				}
			}

			int texLayer = 0;
			float u0 = 0, v0 = 0, u1 = 0, v1 = 0, u2 = 0, v2 = 0;
			if (faceTextures != null && faceTextures[f] != -1)
			{
				texLayer = (faceTextures[f] & 0xFFFF) + 1;
				computeFaceUvs(uv, vxs, vys, vzs, fa[f], fb[f], fc[f],
					textureFaces, texIndicesA, texIndicesB, texIndicesC, f);
				u0 = uv[0]; v0 = uv[1];
				u1 = uv[2]; v1 = uv[3];
				u2 = uv[4]; v2 = uv[5];
			}

			if (hasOverride && texLayer == 0)
			{
				col1 = applyHslOverride(col1, overrideHue, overrideSat, overrideLum, overrideAmount);
				col2 = applyHslOverride(col2, overrideHue, overrideSat, overrideLum, overrideAmount);
				col3 = applyHslOverride(col3, overrideHue, overrideSat, overrideLum, overrideAmount);
			}

			int bias = faceBiasArr != null ? (faceBiasArr[f] & 0xFF) : 0;
			int trans = faceTransparencies != null ? (faceTransparencies[f] & 0xFF) : 0;
			int packedTexLayer = texLayer | (bias << 16) | (trans << 24);

			// writeHslVert (no rotation) — positions already world-space.
			writeHslVert(lx[fa[f]], ly[fa[f]], lz[fa[f]], col1, u0, v0, packedTexLayer);
			writeHslVert(lx[fb[f]], ly[fb[f]], lz[fb[f]], col2, u1, v1, packedTexLayer);
			writeHslVert(lx[fc[f]], ly[fc[f]], lz[fc[f]], col3, u2, v2, packedTexLayer);
			wrote += 3;
		}
		vertexCount += wrote;
		stats.sortedModels.incrementAndGet();
		if (needsFaceSort)
		{
			stats.fullSortModels.incrementAndGet();
			stats.fullSortTransparentFaces.addAndGet(countTransparentFaces(m));
		}
		else
		{
			stats.cullOnlyModels.incrementAndGet();
		}
		stats.sortedFaces.addAndGet(wrote / 3);
		stats.addNanos(stats.modelEmitNanos, emitStart);
	}

	private static int countTransparentFaces(Model m)
	{
		byte[] transparencies = m.getFaceTransparencies();
		if (transparencies == null)
		{
			return 0;
		}
		int transparent = 0;
		int faces = Math.min(m.getFaceCount(), transparencies.length);
		for (int i = 0; i < faces; i++)
		{
			if (transparencies[i] != 0)
			{
				transparent++;
			}
		}
		return transparent;
	}

	private final float[] uvScratch = new float[6];

	/**
	 * Port of stock {@code SceneUploader.computeFaceUvs}. Writes
	 * {@code [u0, v0, u1, v1, u2, v2]} into {@code out}. Falls back to a
	 * trivial mapping (0,0)/(1,0)/(0,1) when the face has no texture-face
	 * mapping.
	 */
	private static void computeFaceUvs(float[] out,
									   float[] vx, float[] vy, float[] vz,
									   int triA, int triB, int triC,
									   byte[] textureFaces,
									   int[] texIndicesA, int[] texIndicesB, int[] texIndicesC,
									   int face)
	{
		if (textureFaces == null || textureFaces[face] == -1
			|| texIndicesA == null || texIndicesB == null || texIndicesC == null)
		{
			out[0] = 0f; out[1] = 0f;
			out[2] = 1f; out[3] = 0f;
			out[4] = 0f; out[5] = 1f;
			return;
		}

		int tfaceIdx = textureFaces[face] & 0xff;
		int texA = texIndicesA[tfaceIdx];
		int texB = texIndicesB[tfaceIdx];
		int texC = texIndicesC[tfaceIdx];

		float v1x = vx[texA], v1y = vy[texA], v1z = vz[texA];
		float v2x = vx[texB] - v1x, v2y = vy[texB] - v1y, v2z = vz[texB] - v1z;
		float v3x = vx[texC] - v1x, v3y = vy[texC] - v1y, v3z = vz[texC] - v1z;

		float v4x = vx[triA] - v1x, v4y = vy[triA] - v1y, v4z = vz[triA] - v1z;
		float v5x = vx[triB] - v1x, v5y = vy[triB] - v1y, v5z = vz[triB] - v1z;
		float v6x = vx[triC] - v1x, v6y = vy[triC] - v1y, v6z = vz[triC] - v1z;

		float v7x = v2y * v3z - v2z * v3y;
		float v7y = v2z * v3x - v2x * v3z;
		float v7z = v2x * v3y - v2y * v3x;

		// u: project onto v3-perpendicular axis through v2
		float v8x = v3y * v7z - v3z * v7y;
		float v8y = v3z * v7x - v3x * v7z;
		float v8z = v3x * v7y - v3y * v7x;
		float f = 1f / (v8x * v2x + v8y * v2y + v8z * v2z);
		out[0] = (v8x * v4x + v8y * v4y + v8z * v4z) * f;
		out[2] = (v8x * v5x + v8y * v5y + v8z * v5z) * f;
		out[4] = (v8x * v6x + v8y * v6y + v8z * v6z) * f;

		// v: project onto v2-perpendicular axis through v3
		v8x = v2y * v7z - v2z * v7y;
		v8y = v2z * v7x - v2x * v7z;
		v8z = v2x * v7y - v2y * v7x;
		f = 1f / (v8x * v3x + v8y * v3y + v8z * v3z);
		out[1] = (v8x * v4x + v8y * v4y + v8z * v4z) * f;
		out[3] = (v8x * v5x + v8y * v5y + v8z * v5z) * f;
		out[5] = (v8x * v6x + v8y * v6y + v8z * v6z) * f;
	}

	void recordDraw(VkCommandBuffer cmd, float[] mvp, float brightness,
					float cameraX, float cameraZ, float drawDistanceTiles, float fogDepthTiles,
					float fogR, float fogG, float fogB,
					int tick, float textureLightMode,
					int colorBlindMode, float colorBlindIntensity,
					float smoothBanding)
	{
		if (vertexCount == 0) return;
		// LANDMINE: bind at offset 0 and shift via firstVertex rather than
		// byte-offset binding — MoltenVK's offset translation produces no
		// visible geometry at hundreds-of-MB offsets on Apple Silicon.
		final int slotFirstVertex = sync.currentFrame() * MAX_VERTICES;
		try (MemoryStack stack = stackPush())
		{
			vkCmdBindVertexBuffers(cmd, 0,
				stack.longs(vbuf.handle()),
				stack.longs(0L));

			// Vertex push (96 bytes): mat4 mvp + vec4 fogVtx + ivec4 (tick + pad).
			ByteBuffer vertPush = stack.malloc(96);
			Mat4Ops.writeTo(vertPush, mvp);
			vertPush.position(64);
			vertPush.putFloat(cameraX);
			vertPush.putFloat(cameraZ);
			vertPush.putFloat(drawDistanceTiles * 128f);
			vertPush.putFloat(fogDepthTiles * 128f);
			vertPush.putInt(tick).putInt(0).putInt(0).putInt(0);
			vertPush.flip();

			// Fragment push (32 bytes at offset 96):
			//   vec4 fogFrag     = (fogR, fogG, fogB, brightness)
			//   vec4 fragExtras  = (textureLightMode, colorBlindMode, colorBlindIntensity, smoothBanding)
			ByteBuffer fragPush = stack.malloc(32);
			fragPush.putFloat(fogR).putFloat(fogG).putFloat(fogB).putFloat(brightness);
			fragPush.putFloat(textureLightMode);
			fragPush.putFloat((float) colorBlindMode);
			fragPush.putFloat(colorBlindIntensity);
			fragPush.putFloat(smoothBanding);
			fragPush.flip();

			// Skip-list of tile-roof ranges the engine wants hidden.
			// recordRoofRange is called in captureScene order, so
			// tileRoofStarts is already sorted by start.
			int skipPairs = 0;
			java.util.Set<Integer> hr = hideRoofIds;
			if (!hr.isEmpty() && tileRoofCount > 0)
			{
				for (int r = 0; r < tileRoofCount; r++)
				{
					if (hr.contains(tileRoofIds[r]))
					{
						if (skipPairs * 2 + 2 > skipScratch.length)
						{
							skipScratch = java.util.Arrays.copyOf(skipScratch, skipScratch.length * 2);
						}
						skipScratch[skipPairs * 2]     = tileRoofStarts[r];
						skipScratch[skipPairs * 2 + 1] = tileRoofStarts[r] + tileRoofCounts[r];
						skipPairs++;
					}
				}
			}

			ScenePipeline boundPipeline = null;
			final int loMin = minPlane;
			final int loMax = maxPlane;
			int layerStart = 0;
			for (int i = 0; i < LAYER_COUNT; i++)
			{
				int regionEnd = i == LAYER_COUNT - 1 ? vertexCount : regionEnds[i];
				// Static layers emit plane-major, so
				// [planeEnds[i][loMin-1], planeEnds[i][loMax]) is the run for
				// planes [loMin..loMax]. DYNAMIC renders whole.
				int drawStart, drawEnd;
				if (LAYERS[i] == Layer.DYNAMIC)
				{
					drawStart = layerStart;
					drawEnd = regionEnd;
				}
				else
				{
					int layerLo = (loMin == 0) ? layerStart : planeEnds[i][loMin - 1];
					int layerHi = planeEnds[i][loMax];
					drawStart = layerLo;
					drawEnd = layerHi;
				}
				int count = drawEnd - drawStart;
				if (count > 0)
				{
					ScenePipeline want = (wireframe[i] && linePipeline != null) ? linePipeline : fillPipeline;
					if (want != boundPipeline)
					{
						vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, want.handle());
						vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
							want.layout(), 0, stack.longs(descriptorSet), null);
						boundPipeline = want;
					}
					// LANDMINE (MoltenVK #2483): re-push every draw. Push
					// constants can go undefined across consecutive draws
					// without it, producing macOS-only stale MVP glitches.
					vkCmdPushConstants(cmd, want.layout(), VK_SHADER_STAGE_VERTEX_BIT,   0,  vertPush);
					vkCmdPushConstants(cmd, want.layout(), VK_SHADER_STAGE_FRAGMENT_BIT, 96, fragPush);
					if (skipPairs > 0)
					{
						drawWithSkips(cmd, drawStart, drawEnd, skipScratch, skipPairs, slotFirstVertex,
							want.layout(), vertPush, fragPush);
					}
					else
					{
						vkCmdDraw(cmd, count, 1, slotFirstVertex + drawStart, 0);
					}
				}
				layerStart = regionEnd;
			}
		}
	}

	private static int tileRoofIdAt(int[][][] roofs, int p, int sx, int sy)
	{
		if (roofs == null || p >= roofs.length) return 0;
		int[][] plane = roofs[p];
		if (plane == null || sx >= plane.length) return 0;
		int[] row = plane[sx];
		if (row == null || sy >= row.length) return 0;
		return row[sy];
	}

	private void recordRoofRange(int roofId, int vertexStart, int vertexCount)
	{
		if (tileRoofCount == tileRoofIds.length)
		{
			int newSize = tileRoofIds.length * 2;
			tileRoofIds    = java.util.Arrays.copyOf(tileRoofIds,    newSize);
			tileRoofStarts = java.util.Arrays.copyOf(tileRoofStarts, newSize);
			tileRoofCounts = java.util.Arrays.copyOf(tileRoofCounts, newSize);
		}
		tileRoofIds   [tileRoofCount] = roofId;
		tileRoofStarts[tileRoofCount] = vertexStart;
		tileRoofCounts[tileRoofCount] = vertexCount;
		tileRoofCount++;
	}

	/** Issue {@code vkCmdDraw} calls covering [start, end) but skipping each
	 *  [skips[2k], skips[2k+1]) sub-range. {@code skips} must be sorted by
	 *  start. Overlapping skip ranges are merged on the fly. */
	private static void drawWithSkips(VkCommandBuffer cmd, int start, int end, int[] skips, int pairCount, int slotFirstVertex,
									  long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		int cursor = start;
		for (int k = 0; k < pairCount; k++)
		{
			int s = skips[k * 2];
			int e = skips[k * 2 + 1];
			if (e <= cursor) continue;
			if (s > cursor)
			{
				int n = Math.min(s, end) - cursor;
				if (n > 0)
				{
					vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,   0,  vertPush);
					vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 96, fragPush);
					vkCmdDraw(cmd, n, 1, slotFirstVertex + cursor, 0);
				}
			}
			cursor = Math.max(cursor, e);
			if (cursor >= end) return;
		}
		if (cursor < end)
		{
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,   0,  vertPush);
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 96, fragPush);
			vkCmdDraw(cmd, end - cursor, 1, slotFirstVertex + cursor, 0);
		}
	}

	int vertexCount() { return vertexCount; }

	@Override
	public void close()
	{
		vkDeviceWaitIdle(device.handle());
		vbuf.close();
		fillPipeline.close();
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

	private void writeHslVert(float x, float y, float z, int hsl16, float u, float v, int texLayer)
	{
		writePackedVertex(x, y, z, hsl16, u, v, texLayer);
	}

	private void writePackedVertex(float x, float y, float z, int hsl16, float u, float v, int texLayer)
	{
		int rgbOffset = (hsl16 & 0xFFFF) * 3;
		// Raw HSL int as float: the frag shader re-decodes per-pixel for
		// the banded look (smoothBanding=0); vColor is the pre-decoded
		// RGB for the smooth look (smoothBanding=1). Vertex layout is
		// padded to vec4 alignment for MoltenVK — see ScenePipeline.VERTEX_STRIDE.
		float light = (float) (hsl16 & 0xFFFF);
		long p = writePtr;
		MemoryUtil.memPutFloat(p, x);
		MemoryUtil.memPutFloat(p + 4, y);
		MemoryUtil.memPutFloat(p + 8, z);
		MemoryUtil.memPutFloat(p + 12, 0f);
		MemoryUtil.memPutFloat(p + 16, HSL_RGB[rgbOffset]);
		MemoryUtil.memPutFloat(p + 20, HSL_RGB[rgbOffset + 1]);
		MemoryUtil.memPutFloat(p + 24, HSL_RGB[rgbOffset + 2]);
		MemoryUtil.memPutFloat(p + 28, 0f);
		MemoryUtil.memPutFloat(p + 32, light);
		MemoryUtil.memPutFloat(p + 36, u);
		MemoryUtil.memPutFloat(p + 40, v);
		MemoryUtil.memPutInt(p + 44, texLayer);
		writePtr = p + ScenePipeline.VERTEX_STRIDE;
	}

	/**
	 * Per-component HSL lerp toward an override target. Mirrors stock
	 * {@code SceneUploader.interpolateHSL}. Override byte -1 keeps that
	 * component unchanged. Result clamped to each component's bit width.
	 */
	private static int applyHslOverride(int hsl16, byte oH, byte oS, byte oL, byte oA)
	{
		int amount = oA & 0xFF;
		int h = (hsl16 >> 10) & 0x3F;
		int s = (hsl16 >>  7) & 0x07;
		int l =  hsl16        & 0x7F;
		if (oH != -1)
		{
			int blended = h + ((amount * ((oH & 0xFF) - h)) >> 7);
			h = blended < 0 ? 0 : (blended > 0x3F ? 0x3F : blended);
		}
		if (oS != -1)
		{
			int blended = s + ((amount * ((oS & 0xFF) - s)) >> 7);
			s = blended < 0 ? 0 : (blended > 0x07 ? 0x07 : blended);
		}
		if (oL != -1)
		{
			int blended = l + ((amount * ((oL & 0xFF) - l)) >> 7);
			l = blended < 0 ? 0 : (blended > 0x7F ? 0x7F : blended);
		}
		return (h << 10) | (s << 7) | l;
	}

	private static float[] buildHslRgbTable()
	{
		float[] table = new float[0x10000 * 3];
		for (int hsl16 = 0; hsl16 < 0x10000; hsl16++)
		{
			int h = (hsl16 >> 10) & 0x3F;
			int s = (hsl16 >>  7) & 0x07;
			int l =  hsl16        & 0x7F;
			float hue = h / 64f + 0.0078125f;
			float sat = s / 8f  + 0.0625f;
			float lum = l / 128f;
			float q = lum < 0.5f ? lum * (1f + sat) : lum + sat - lum * sat;
			float p = 2f * lum - q;
			int offset = hsl16 * 3;
			table[offset] = hueToChannel(p, q, hue + 1f / 3f);
			table[offset + 1] = hueToChannel(p, q, hue);
			table[offset + 2] = hueToChannel(p, q, hue - 1f / 3f);
		}
		return table;
	}

	private static float hueToChannel(float p, float q, float t)
	{
		if (t > 1f) t -= 1f;
		else if (t < 0f) t += 1f;
		if (6f * t < 1f) return p + (q - p) * 6f * t;
		if (2f * t < 1f) return q;
		if (3f * t < 2f) return p + (q - p) * (2f / 3f - t) * 6f;
		return p;
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
