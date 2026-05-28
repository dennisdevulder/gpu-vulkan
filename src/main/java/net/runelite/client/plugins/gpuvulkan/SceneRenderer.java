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
 * {@code [posX posY posZ pad colorR colorG colorB pad light u v texLayer]}.
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

	private static final int MAX_STATIC_VERTICES = 5_000_000;
	private static final int MAX_FRAME_VERTICES = 3_000_000;
	private static final int MAX_LOGICAL_VERTICES = MAX_STATIC_VERTICES + MAX_FRAME_VERTICES;
	private static final long STATIC_BUFFER_BYTES = (long) MAX_STATIC_VERTICES * ScenePipeline.VERTEX_STRIDE;
	private static final long FRAME_BUFFER_BYTES = (long) MAX_FRAME_VERTICES * ScenePipeline.VERTEX_STRIDE;
	private static final long TOTAL_BUFFER_BYTES = STATIC_BUFFER_BYTES + FRAME_BUFFER_BYTES * FrameSync.FRAMES_IN_FLIGHT;
	private static final int HSL_HIDDEN = 12345678;
	private static final int OPAQUE_UNSORTED_FACE_THRESHOLD = 24;
	private static final int MAX_MODEL_CACHE_ENTRIES = 8192;
	private static final int MODEL_CACHE_BUCKETS = 16384;
	private static final int MODEL_CACHE_BUCKET_MASK = MODEL_CACHE_BUCKETS - 1;
	private static final long MODEL_MESH_ARENA_BYTES = 64L * 1024L * 1024L;
	private static final int MAX_MODEL_INSTANCES = 65_536;
	private static final int MODEL_INSTANCE_STRIDE = 40;
	private static final int MODEL_MESH_HEADER_INTS = 16;
	private static final int MODEL_MESH_HEADER_BYTES = MODEL_MESH_HEADER_INTS * Integer.BYTES;
	private static final long MODEL_COMPUTE_OUTPUT_FRAME_BYTES =
		(long) MAX_MODEL_INSTANCES * 3L * ScenePipeline.VERTEX_STRIDE;
	private static final boolean ENABLE_MODEL_COMPUTE_PROTOTYPE = true;
	private static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
	private static final float[] HSL_RGB = buildHslRgbTable();

	private final VulkanDevice device;
	private final FrameSync sync;
	private final ScenePipeline fillPipeline;
	private final ScenePipeline linePipeline;
	private final ScenePipeline alphaPipeline;
	private final ScenePipeline priorityColorPipeline;
	private final ScenePipeline priorityDepthPipeline;
	private final SceneVertexBuffer vbuf;
	private final ByteBuffer mapped;
	private final long descriptorSet;
	private final int slotBytes;
	private final DrawCallbackStats stats;
	private long writePtr;
	private long writeBasePtr;
	private int writeBaseVertex;
	private int writeVertexLimit = MAX_STATIC_VERTICES;
	private final float[] cullLocalX = new float[ModelSorter.MAX_VERTEX_COUNT];
	private final float[] cullLocalY = new float[ModelSorter.MAX_VERTEX_COUNT];
	private final float[] cullLocalZ = new float[ModelSorter.MAX_VERTEX_COUNT];
	private final float[] cullProjX = new float[ModelSorter.MAX_VERTEX_COUNT];
	private final float[] cullProjY = new float[ModelSorter.MAX_VERTEX_COUNT];
	private final float[] cullProjectScratch = new float[3];
	private final ModelCacheEntry[] modelCacheBuckets = new ModelCacheEntry[MODEL_CACHE_BUCKETS];
	private final ModelMeshArena modelMeshArena;
	private final ModelInstanceBuffer modelInstanceBuffer;
	private final ModelComputeOutputBuffer modelComputeOutputBuffer;
	private final ModelComputePipeline modelComputePipeline;
	private int modelCacheSize;
	private int modelInstanceCount;
	private int modelInstanceOverflowCount;
	private boolean modelInstanceFrameActive;
	private long modelCacheHits;
	private long modelCacheMisses;

	private static final int MAX_PLANES = 4;

	/** OSRS engine convention: SCENE_SIZE / ZONE_SIZE = 13 zones per side. */
	static final int ZONE_SIZE = 8;
	static final int ZONES_PER_SIDE = Constants.SCENE_SIZE / ZONE_SIZE;
	static final int ZONE_COUNT = ZONES_PER_SIDE * ZONES_PER_SIDE;
	private static final int TOPLEVEL_ZONE_OFFSET = SCENE_OFFSET / ZONE_SIZE;

	private final int[] regionEnds = new int[LAYER_COUNT];
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
	private int[] priorityRangeStarts = new int[1024];
	private int[] priorityRangeEnds = new int[1024];
	private int[] prioritySkipPairs = new int[2048];
	private int priorityRangeCount;
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
	private int[] tileRoofIds      = new int[2048];
	private int[] tileRoofStarts   = new int[2048];
	private int[] tileRoofCounts   = new int[2048];
	private int   tileRoofCount;
	private boolean rebuildingOverlayZone;
	/** Sorted [start, end) pairs of vertex sub-ranges to skip this frame. */
	private int[] skipScratch = new int[256];
	private final boolean[] dirtyZones = new boolean[ZONE_COUNT];
	private final int[] dirtyZoneSlotMask = new int[ZONE_COUNT];
	private int dirtyZoneCount;

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
		Model model;

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
		java.util.Arrays.fill(dirtyZones, false);
		java.util.Arrays.fill(dirtyZoneSlotMask, 0);
		dirtyZoneCount = 0;
		pendingRenderables.clear();
		clearModelCache();
		for (int i = 0; i < LAYER_COUNT; i++)
		{
			regionEnds[i] = 0;
			for (int p = 0; p < MAX_PLANES; p++) planeEnds[i][p] = 0;
		}
		clearOverlayRanges();
	}

	void invalidateZone(Scene scene, int zx, int zz)
	{
		if (scene != null && scene.getWorldViewId() == net.runelite.api.WorldView.TOPLEVEL)
		{
			zx -= TOPLEVEL_ZONE_OFFSET;
			zz -= TOPLEVEL_ZONE_OFFSET;
		}
		if (zx < 0 || zz < 0 || zx >= ZONES_PER_SIDE || zz >= ZONES_PER_SIDE)
		{
			return;
		}
		int zone = zx * ZONES_PER_SIDE + zz;
		if (!dirtyZones[zone])
		{
			dirtyZones[zone] = true;
			dirtyZoneCount++;
		}
		dirtyZoneSlotMask[zone] = 0;
	}

	/** Single instance reused across captures — all captures run on the
	 *  Client thread. */
	private final ModelSorter sorter = new ModelSorter();

	SceneRenderer(VulkanDevice device, FrameSync sync,
		RenderPass renderPass, TextureArray textureArray,
		DrawCallbackStats stats, boolean alphaToCoverage)
	{
		this.device = device;
		this.sync = sync;
		this.stats = stats;
		this.fillPipeline = new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL, true,
			renderPass.samples(), alphaToCoverage);
		this.alphaPipeline = new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL,
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
		this.vbuf = new SceneVertexBuffer(device, TOTAL_BUFFER_BYTES, FRAME_BUFFER_BYTES,
			fillPipeline.descriptorSetLayout(), textureArray);
		this.mapped = vbuf.mapped();
		this.descriptorSet = vbuf.descriptorSet();
		this.slotBytes = (int) vbuf.slotBytes();
		this.modelMeshArena = new ModelMeshArena(device, MODEL_MESH_ARENA_BYTES);
		this.modelInstanceBuffer = new ModelInstanceBuffer(device,
			(long) MAX_MODEL_INSTANCES * MODEL_INSTANCE_STRIDE * FrameSync.FRAMES_IN_FLIGHT);
		this.modelComputeOutputBuffer = ENABLE_MODEL_COMPUTE_PROTOTYPE
			? new ModelComputeOutputBuffer(device, MODEL_COMPUTE_OUTPUT_FRAME_BYTES * FrameSync.FRAMES_IN_FLIGHT)
			: null;
		this.modelComputePipeline = ENABLE_MODEL_COMPUTE_PROTOTYPE
			? new ModelComputePipeline(device,
				modelMeshArena.handle(), modelMeshArena.capacityBytes(),
				modelInstanceBuffer.handle(), modelInstanceBuffer.frameBytes(),
				modelComputeOutputBuffer.handle(), modelComputeOutputBuffer.frameBytes())
			: null;
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
		metrics.maxVertices += MAX_LOGICAL_VERTICES;
		metrics.roofRanges += tileRoofCount;
		metrics.pendingRenderables += pendingRenderables.size();
		metrics.dirtyZones += dirtyZoneCount;
		metrics.modelCacheEntries += modelCacheSize;
		metrics.modelCacheHits += modelCacheHits;
		metrics.modelCacheMisses += modelCacheMisses;
		metrics.modelMeshBytes += modelMeshArena.usedBytes();
		metrics.modelMeshCapacityBytes += modelMeshArena.capacityBytes();
		metrics.modelInstances += modelInstanceCount;
		metrics.modelInstanceMax += MAX_MODEL_INSTANCES;
		metrics.modelInstanceOverflows += modelInstanceOverflowCount;
		metrics.modelComputeOutputBytes += modelComputeOutputBuffer == null ? 0L : modelComputeOutputBuffer.totalBytes();
		metrics.overflowed |= overflowed;
		metrics.sceneBufferBytes += TOTAL_BUFFER_BYTES;
	}

	private int staticVertexCount()
	{
		return regionEnds[LAST_STATIC.ordinal()];
	}

	private void useStaticWriteArena()
	{
		writeBaseVertex = 0;
		writeVertexLimit = MAX_STATIC_VERTICES;
		writeBasePtr = MemoryUtil.memAddress(mapped);
		writePtr = writeBasePtr;
	}

	private void useFrameWriteArena(int slot, int logicalVertex)
	{
		writeBaseVertex = staticVertexCount();
		writeVertexLimit = writeBaseVertex + MAX_FRAME_VERTICES;
		writeBasePtr = MemoryUtil.memAddress(mapped) + STATIC_BUFFER_BYTES + (long) slot * slotBytes;
		setWriteCursor(logicalVertex);
	}

	private void setWriteCursor(int logicalVertex)
	{
		writePtr = writeBasePtr + (long) (logicalVertex - writeBaseVertex) * ScenePipeline.VERTEX_STRIDE;
	}

	private boolean reserveVertices(int vertices)
	{
		if (vertices < 0 || vertexCount > writeVertexLimit - vertices)
		{
			overflow();
			return false;
		}
		return true;
	}

	private ModelCacheEntry modelInfo(Model model)
	{
		int faceCount = model.getFaceCount();
		int vertexCount = model.getVerticesCount();
		Object verticesX = model.getVerticesX();
		Object verticesY = model.getVerticesY();
		Object verticesZ = model.getVerticesZ();
		Object faceIndices1 = model.getFaceIndices1();
		Object faceIndices2 = model.getFaceIndices2();
		Object faceIndices3 = model.getFaceIndices3();
		Object faceColors1 = model.getFaceColors1();
		Object faceColors2 = model.getFaceColors2();
		Object faceColors3 = model.getFaceColors3();
		Object faceTextures = model.getFaceTextures();
		Object faceTransparencies = model.getFaceTransparencies();
		Object faceBias = model.getFaceBias();
		Object textureFaces = model.getTextureFaces();
		Object texIndices1 = model.getTexIndices1();
		Object texIndices2 = model.getTexIndices2();
		Object texIndices3 = model.getTexIndices3();

		int hash = 31 * faceCount + vertexCount;
		hash = mix(hash, verticesX);
		hash = mix(hash, verticesY);
		hash = mix(hash, verticesZ);
		hash = mix(hash, faceIndices1);
		hash = mix(hash, faceIndices2);
		hash = mix(hash, faceIndices3);
		hash = mix(hash, faceColors1);
		hash = mix(hash, faceColors2);
		hash = mix(hash, faceColors3);
		hash = mix(hash, faceTextures);
		hash = mix(hash, faceTransparencies);
		hash = mix(hash, faceBias);
		hash = mix(hash, textureFaces);
		hash = mix(hash, texIndices1);
		hash = mix(hash, texIndices2);
		hash = mix(hash, texIndices3);

		int bucket = hash & MODEL_CACHE_BUCKET_MASK;
		for (ModelCacheEntry entry = modelCacheBuckets[bucket]; entry != null; entry = entry.next)
		{
			if (entry.matches(hash, faceCount, vertexCount,
				verticesX, verticesY, verticesZ,
				faceIndices1, faceIndices2, faceIndices3,
				faceColors1, faceColors2, faceColors3,
				faceTextures, faceTransparencies, faceBias,
				textureFaces, texIndices1, texIndices2, texIndices3))
			{
				modelCacheHits++;
				return entry;
			}
		}

		modelCacheMisses++;
		if (modelCacheSize >= MAX_MODEL_CACHE_ENTRIES)
		{
			clearModelCache();
			bucket = hash & MODEL_CACHE_BUCKET_MASK;
		}
		ModelCacheEntry cached = new ModelCacheEntry(hash, faceCount, vertexCount,
			verticesX, verticesY, verticesZ,
			faceIndices1, faceIndices2, faceIndices3,
			faceColors1, faceColors2, faceColors3,
			faceTextures, faceTransparencies, faceBias,
			textureFaces, texIndices1, texIndices2, texIndices3,
			modelCacheBuckets[bucket]);
		cached.uploadMesh(modelMeshArena);
		modelCacheBuckets[bucket] = cached;
		modelCacheSize++;
		return cached;
	}

	private void clearModelCache()
	{
		java.util.Arrays.fill(modelCacheBuckets, null);
		modelCacheSize = 0;
		modelMeshArena.reset();
	}

	private void recordModelInstance(ModelCacheEntry modelInfo, int orient, int worldX, int worldY, int worldZ, int renderMode)
	{
		if (modelInfo.meshOffset < 0L)
		{
			return;
		}
		if (!modelInstanceFrameActive)
		{
			modelInstanceOverflowCount++;
			return;
		}
		if (modelInstanceCount >= MAX_MODEL_INSTANCES)
		{
			modelInstanceOverflowCount++;
			return;
		}
		modelInstanceBuffer.write(modelInstanceCount, modelInfo, orient, worldX, worldY, worldZ, renderMode);
		modelInstanceCount++;
	}

	private static int mix(int hash, Object ref)
	{
		return hash * 31 + System.identityHashCode(ref);
	}

	private static int arrayLength(Object array)
	{
		if (array instanceof int[])
		{
			return ((int[]) array).length;
		}
		if (array instanceof byte[])
		{
			return ((byte[]) array).length;
		}
		return 0;
	}

	private static int align4(int value)
	{
		return (value + 3) & ~3;
	}

	private static long align4(long value)
	{
		return (value + 3L) & ~3L;
	}

	private static int addAligned(int offset, int bytes)
	{
		return align4(offset + bytes);
	}

	private static int floatBytes(Object array, int count)
	{
		return array == null ? 0 : count * Float.BYTES;
	}

	private static int intBytes(Object array, int count)
	{
		return array == null ? 0 : count * Integer.BYTES;
	}

	private static int shortBytes(Object array, int count)
	{
		return array == null ? 0 : count * Short.BYTES;
	}

	private static int byteBytes(Object array, int count)
	{
		return array == null ? 0 : count;
	}

	private static long writeFloatArray(long p, float[] values, int count)
	{
		if (values == null)
		{
			return p;
		}
		int n = Math.min(count, values.length);
		for (int i = 0; i < n; i++)
		{
			MemoryUtil.memPutFloat(p, values[i]);
			p += Float.BYTES;
		}
		return p;
	}

	private static long writeIntArray(long p, int[] values, int count)
	{
		if (values == null)
		{
			return p;
		}
		int n = Math.min(count, values.length);
		for (int i = 0; i < n; i++)
		{
			MemoryUtil.memPutInt(p, values[i]);
			p += Integer.BYTES;
		}
		return p;
	}

	private static long writeShortArray(long p, short[] values, int count)
	{
		if (values == null)
		{
			return p;
		}
		int n = Math.min(count, values.length);
		for (int i = 0; i < n; i++)
		{
			MemoryUtil.memPutShort(p, values[i]);
			p += Short.BYTES;
		}
		return p;
	}

	private static long writeByteArray(long p, byte[] values, int count)
	{
		if (values == null)
		{
			return p;
		}
		int n = Math.min(count, values.length);
		for (int i = 0; i < n; i++)
		{
			MemoryUtil.memPutByte(p++, values[i]);
		}
		return p;
	}

	private static int writeModelMeshSectionOffset(long header, int headerIndex, int currentOffset)
	{
		currentOffset = align4(currentOffset);
		MemoryUtil.memPutInt(header + (long) headerIndex * Integer.BYTES, currentOffset);
		return currentOffset;
	}

	private static final class ModelCacheEntry
	{
		ModelCacheEntry next;
		final int hash;
		final int faceCount;
		final int vertexCount;
		final Object verticesX, verticesY, verticesZ;
		final Object faceIndices1, faceIndices2, faceIndices3;
		final Object faceColors1, faceColors2, faceColors3;
		final Object faceTextures, faceTransparencies, faceBias;
		final Object textureFaces, texIndices1, texIndices2, texIndices3;
		long meshOffset = -1L;
		int meshBytes;
		final int transparentFaces;
		final boolean hasTransparentFaces;

		ModelCacheEntry(int hash, int faceCount, int vertexCount,
			Object verticesX, Object verticesY, Object verticesZ,
			Object faceIndices1, Object faceIndices2, Object faceIndices3,
			Object faceColors1, Object faceColors2, Object faceColors3,
			Object faceTextures, Object faceTransparencies, Object faceBias,
			Object textureFaces, Object texIndices1, Object texIndices2, Object texIndices3,
			ModelCacheEntry next)
		{
			this.next = next;
			this.hash = hash;
			this.faceCount = faceCount;
			this.vertexCount = vertexCount;
			this.verticesX = verticesX;
			this.verticesY = verticesY;
			this.verticesZ = verticesZ;
			this.faceIndices1 = faceIndices1;
			this.faceIndices2 = faceIndices2;
			this.faceIndices3 = faceIndices3;
			this.faceColors1 = faceColors1;
			this.faceColors2 = faceColors2;
			this.faceColors3 = faceColors3;
			this.faceTextures = faceTextures;
			this.faceTransparencies = faceTransparencies;
			this.faceBias = faceBias;
			this.textureFaces = textureFaces;
			this.texIndices1 = texIndices1;
			this.texIndices2 = texIndices2;
			this.texIndices3 = texIndices3;
			this.transparentFaces = countTransparentFaces(faceCount, (byte[]) faceTransparencies);
			this.hasTransparentFaces = transparentFaces > 0;
		}

		void uploadMesh(ModelMeshArena arena)
		{
			int bytes = meshBytes();
			long offset = arena.allocate(bytes);
			if (offset < 0L)
			{
				arena.reset();
				offset = arena.allocate(bytes);
				if (offset < 0L)
				{
					return;
				}
			}
			long p = arena.address() + offset;
			long header = p;
			int sectionOffset = MODEL_MESH_HEADER_BYTES;
			sectionOffset = writeModelMeshSectionOffset(header, 0, sectionOffset);
			sectionOffset += floatBytes(verticesX, vertexCount);
			sectionOffset = writeModelMeshSectionOffset(header, 1, sectionOffset);
			sectionOffset += floatBytes(verticesY, vertexCount);
			sectionOffset = writeModelMeshSectionOffset(header, 2, sectionOffset);
			sectionOffset += floatBytes(verticesZ, vertexCount);
			sectionOffset = writeModelMeshSectionOffset(header, 3, sectionOffset);
			sectionOffset += intBytes(faceIndices1, faceCount);
			sectionOffset = writeModelMeshSectionOffset(header, 4, sectionOffset);
			sectionOffset += intBytes(faceIndices2, faceCount);
			sectionOffset = writeModelMeshSectionOffset(header, 5, sectionOffset);
			sectionOffset += intBytes(faceIndices3, faceCount);
			sectionOffset = writeModelMeshSectionOffset(header, 6, sectionOffset);
			sectionOffset += intBytes(faceColors1, faceCount);
			sectionOffset = writeModelMeshSectionOffset(header, 7, sectionOffset);
			sectionOffset += intBytes(faceColors2, faceCount);
			sectionOffset = writeModelMeshSectionOffset(header, 8, sectionOffset);
			sectionOffset += intBytes(faceColors3, faceCount);
			sectionOffset = writeModelMeshSectionOffset(header, 9, sectionOffset);
			sectionOffset += shortBytes(faceTextures, faceCount);
			sectionOffset = writeModelMeshSectionOffset(header, 10, sectionOffset);
			sectionOffset += byteBytes(faceTransparencies, faceCount);
			sectionOffset = writeModelMeshSectionOffset(header, 11, sectionOffset);
			sectionOffset += byteBytes(faceBias, faceCount);
			sectionOffset = writeModelMeshSectionOffset(header, 12, sectionOffset);
			sectionOffset += byteBytes(textureFaces, arrayLength(textureFaces));
			sectionOffset = writeModelMeshSectionOffset(header, 13, sectionOffset);
			sectionOffset += intBytes(texIndices1, arrayLength(texIndices1));
			sectionOffset = writeModelMeshSectionOffset(header, 14, sectionOffset);
			sectionOffset += intBytes(texIndices2, arrayLength(texIndices2));
			sectionOffset = writeModelMeshSectionOffset(header, 15, sectionOffset);
			p += MODEL_MESH_HEADER_BYTES;
			p = writeFloatArray(p, (float[]) verticesX, vertexCount);
			p = align4(p);
			p = writeFloatArray(p, (float[]) verticesY, vertexCount);
			p = align4(p);
			p = writeFloatArray(p, (float[]) verticesZ, vertexCount);
			p = align4(p);
			p = writeIntArray(p, (int[]) faceIndices1, faceCount);
			p = align4(p);
			p = writeIntArray(p, (int[]) faceIndices2, faceCount);
			p = align4(p);
			p = writeIntArray(p, (int[]) faceIndices3, faceCount);
			p = align4(p);
			p = writeIntArray(p, (int[]) faceColors1, faceCount);
			p = align4(p);
			p = writeIntArray(p, (int[]) faceColors2, faceCount);
			p = align4(p);
			p = writeIntArray(p, (int[]) faceColors3, faceCount);
			p = align4(p);
			p = writeShortArray(p, (short[]) faceTextures, faceCount);
			p = align4(p);
			p = writeByteArray(p, (byte[]) faceTransparencies, faceCount);
			p = align4(p);
			p = writeByteArray(p, (byte[]) faceBias, faceCount);
			p = align4(p);
			p = writeByteArray(p, (byte[]) textureFaces, arrayLength(textureFaces));
			p = align4(p);
			p = writeIntArray(p, (int[]) texIndices1, arrayLength(texIndices1));
			p = align4(p);
			p = writeIntArray(p, (int[]) texIndices2, arrayLength(texIndices2));
			p = align4(p);
			writeIntArray(p, (int[]) texIndices3, arrayLength(texIndices3));
			arena.flush(offset, bytes);
			meshOffset = offset;
			meshBytes = bytes;
		}

		private int meshBytes()
		{
			int bytes = MODEL_MESH_HEADER_BYTES;
			bytes = addAligned(bytes, floatBytes(verticesX, vertexCount));
			bytes = addAligned(bytes, floatBytes(verticesY, vertexCount));
			bytes = addAligned(bytes, floatBytes(verticesZ, vertexCount));
			bytes = addAligned(bytes, intBytes(faceIndices1, faceCount));
			bytes = addAligned(bytes, intBytes(faceIndices2, faceCount));
			bytes = addAligned(bytes, intBytes(faceIndices3, faceCount));
			bytes = addAligned(bytes, intBytes(faceColors1, faceCount));
			bytes = addAligned(bytes, intBytes(faceColors2, faceCount));
			bytes = addAligned(bytes, intBytes(faceColors3, faceCount));
			bytes = addAligned(bytes, shortBytes(faceTextures, faceCount));
			bytes = addAligned(bytes, byteBytes(faceTransparencies, faceCount));
			bytes = addAligned(bytes, byteBytes(faceBias, faceCount));
			bytes = addAligned(bytes, byteBytes(textureFaces, arrayLength(textureFaces)));
			bytes = addAligned(bytes, intBytes(texIndices1, arrayLength(texIndices1)));
			bytes = addAligned(bytes, intBytes(texIndices2, arrayLength(texIndices2)));
			bytes = addAligned(bytes, intBytes(texIndices3, arrayLength(texIndices3)));
			return bytes;
		}

		boolean matches(int hash, int faceCount, int vertexCount,
			Object verticesX, Object verticesY, Object verticesZ,
			Object faceIndices1, Object faceIndices2, Object faceIndices3,
			Object faceColors1, Object faceColors2, Object faceColors3,
			Object faceTextures, Object faceTransparencies, Object faceBias,
			Object textureFaces, Object texIndices1, Object texIndices2, Object texIndices3)
		{
			return this.hash == hash
				&& this.faceCount == faceCount
				&& this.vertexCount == vertexCount
				&& this.verticesX == verticesX
				&& this.verticesY == verticesY
				&& this.verticesZ == verticesZ
				&& this.faceIndices1 == faceIndices1
				&& this.faceIndices2 == faceIndices2
				&& this.faceIndices3 == faceIndices3
				&& this.faceColors1 == faceColors1
				&& this.faceColors2 == faceColors2
				&& this.faceColors3 == faceColors3
				&& this.faceTextures == faceTextures
				&& this.faceTransparencies == faceTransparencies
				&& this.faceBias == faceBias
				&& this.textureFaces == textureFaces
				&& this.texIndices1 == texIndices1
				&& this.texIndices2 == texIndices2
				&& this.texIndices3 == texIndices3;
		}
	}

	private static final class ModelMeshArena implements AutoCloseable
	{
		private final Buffer buffer;
		private final long address;
		private final long capacity;
		private long used;

		ModelMeshArena(VulkanDevice device, long capacity)
		{
			this.capacity = capacity;
			this.buffer = new Buffer(device, capacity, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
				VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
			buffer.mapPersistent();
			this.address = MemoryUtil.memAddress(buffer.mappedByteBuffer());
		}

		long allocate(int bytes)
		{
			int aligned = align4(bytes);
			if (aligned <= 0 || used > capacity - aligned)
			{
				return -1L;
			}
			long offset = used;
			used += aligned;
			return offset;
		}

		void reset()
		{
			used = 0L;
		}

		void flush(long offset, int bytes)
		{
			buffer.flushRangeIfNeeded(offset, align4(bytes));
		}

		long address()
		{
			return address;
		}

		long usedBytes()
		{
			return used;
		}

		long capacityBytes()
		{
			return capacity;
		}

		long handle()
		{
			return buffer.handle();
		}

		@Override
		public void close()
		{
			buffer.close();
		}
	}

	private static final class ModelInstanceBuffer implements AutoCloseable
	{
		private final Buffer buffer;
		private final long address;
		private long frameAddress;

		ModelInstanceBuffer(VulkanDevice device, long bytes)
		{
			this.buffer = new Buffer(device, bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
				VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
			buffer.mapPersistent();
			this.address = MemoryUtil.memAddress(buffer.mappedByteBuffer());
		}

		void beginFrame(int slot)
		{
			frameAddress = address + (long) slot * MAX_MODEL_INSTANCES * MODEL_INSTANCE_STRIDE;
		}

		long frameBytes()
		{
			return (long) MAX_MODEL_INSTANCES * MODEL_INSTANCE_STRIDE;
		}

		long handle()
		{
			return buffer.handle();
		}

		void write(int index, ModelCacheEntry model, int orient, int worldX, int worldY, int worldZ, int renderMode)
		{
			long p = frameAddress + (long) index * MODEL_INSTANCE_STRIDE;
			MemoryUtil.memPutLong(p, model.meshOffset);
			MemoryUtil.memPutInt(p + 8, model.meshBytes);
			MemoryUtil.memPutInt(p + 12, model.faceCount);
			MemoryUtil.memPutInt(p + 16, model.vertexCount);
			MemoryUtil.memPutInt(p + 20, orient);
			MemoryUtil.memPutInt(p + 24, worldX);
			MemoryUtil.memPutInt(p + 28, worldY);
			MemoryUtil.memPutInt(p + 32, worldZ);
			MemoryUtil.memPutInt(p + 36, renderMode);
		}

		@Override
		public void close()
		{
			buffer.close();
		}
	}

	private static final class ModelComputeOutputBuffer implements AutoCloseable
	{
		private final Buffer buffer;
		private final long bytes;

		ModelComputeOutputBuffer(VulkanDevice device, long bytes)
		{
			this.bytes = bytes;
			this.buffer = new Buffer(device, bytes,
				VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
				VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		}

		long frameBytes()
		{
			return MODEL_COMPUTE_OUTPUT_FRAME_BYTES;
		}

		long totalBytes()
		{
			return bytes;
		}

		long handle()
		{
			return buffer.handle();
		}

		@Override
		public void close()
		{
			buffer.close();
		}
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
			vkWaitForFences(device.handle(), stack.longs(sync.inFlightFence()), true, Long.MAX_VALUE);
		}
		int slot = sync.currentFrame();
		vertexCount = overlayNextVertex[slot];
		useFrameWriteArena(slot, vertexCount);
		modelInstanceBuffer.beginFrame(slot);
		modelInstanceCount = 0;
		modelInstanceOverflowCount = 0;
		modelInstanceFrameActive = true;
		overflowed = false;
		priorityRangeCount = 0;
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
		if (scene == null || dirtyZoneCount == 0)
		{
			return;
		}

		Tile[][][] tiles = scene.getTiles();
		if (tiles == null)
		{
			return;
		}

		int slot = sync.currentFrame();
		int slotBit = 1 << slot;
		int allSlots = (1 << FrameSync.FRAMES_IN_FLIGHT) - 1;
		vertexCount = overlayNextVertex[slot];
		useFrameWriteArena(slot, vertexCount);

		final int planes = Math.min(tiles.length, MAX_PLANES);
		final int sceneSize = Constants.SCENE_SIZE;
		final int[][][] roofs = scene.getRoofs();
		final byte[][][] tileSettings = scene.getExtendedTileSettings();
		final int roofOffset = scene.getWorldViewId() == net.runelite.api.WorldView.TOPLEVEL
			? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 : 0;

		for (int zone = 0; zone < ZONE_COUNT; zone++)
		{
			if (!dirtyZones[zone] || (dirtyZoneSlotMask[zone] & slotBit) != 0)
			{
				continue;
			}

			clearOverlayZone(slot, zone);
			rebuildingOverlayZone = true;
			try
			{
				captureDirtyZone(scene, tiles, planes, sceneSize, roofs, tileSettings, roofOffset, slot, zone);
			}
			finally
			{
				rebuildingOverlayZone = false;
			}
			overlayZoneValid[slot][zone] = true;
			overlaySlotHasZones[slot] = true;
			dirtyZoneSlotMask[zone] |= slotBit;
			if (dirtyZoneSlotMask[zone] == allSlots)
			{
				dirtyZones[zone] = false;
				dirtyZoneCount--;
			}
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
		useStaticWriteArena();
		overflowed = false;
		tileRoofCount = 0;
		clearOverlayRanges();
		pendingRenderables.clear();
		clearModelCache();

		Tile[][][] tiles = scene.getTiles();
		if (tiles == null) return;
		final int planes = Math.min(tiles.length, MAX_PLANES);
		final int sceneSize = Constants.SCENE_SIZE;

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
		captureLayer(Layer.TERRAIN, tiles, planes, sceneSize, roofs, tileSettings, roofOffset,
			(cur, p, sx, sy) ->
			{
				SceneTilePaint paint = cur.getSceneTilePaint();
				if (paint != null) captureTilePaint(scene, paint, p, sx, sy);
				SceneTileModel m = cur.getSceneTileModel();
				if (m != null) captureTileModel(m, sx, sy);
			});

		captureLayer(Layer.WALLS, tiles, planes, sceneSize, roofs, tileSettings, roofOffset,
			(cur, p, sx, sy) ->
			{
				WallObject w = cur.getWallObject();
				if (w == null) return;
				captureRenderable(w.getRenderable1(), 0, w.getX(), w.getZ(), w.getY());
				captureRenderable(w.getRenderable2(), 0, w.getX(), w.getZ(), w.getY());
			});

		captureLayer(Layer.DECORATIVE, tiles, planes, sceneSize, roofs, tileSettings, roofOffset,
			(cur, p, sx, sy) ->
			{
				DecorativeObject d = cur.getDecorativeObject();
				if (d == null) return;
				captureRenderable(d.getRenderable(),  0,
					d.getX() + d.getXOffset(),  d.getZ(), d.getY() + d.getYOffset());
				captureRenderable(d.getRenderable2(), 0,
					d.getX() + d.getXOffset2(), d.getZ(), d.getY() + d.getYOffset2());
			});

		captureLayer(Layer.GROUND, tiles, planes, sceneSize, roofs, tileSettings, roofOffset,
			(cur, p, sx, sy) ->
			{
				GroundObject g = cur.getGroundObject();
				if (g == null) return;
				captureRenderable(g.getRenderable(), 0, g.getX(), g.getZ(), g.getY());
			});

		captureGameObjectsLayer(tiles, planes, sceneSize, roofs, tileSettings, roofOffset);

		// Pad planeEnds past `planes` so max-plane lookups still draw
		// everything if requested.
		for (int i = 0; i < LAYER_COUNT; i++)
			for (int p = planes; p < MAX_PLANES; p++)
				planeEnds[i][p] = regionEnds[i];

		for (int slot = 0; slot < FrameSync.FRAMES_IN_FLIGHT; slot++)
		{
			overlayNextVertex[slot] = vertexCount;
		}

		log.debug("captureScene: {} vertices, {} roof tiles", vertexCount, tileRoofCount);
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

	private void captureDirtyZone(Scene scene, Tile[][][] tiles, int planes, int sceneSize,
								  int[][][] roofs, byte[][][] tileSettings, int roofOffset,
								  int slot, int zone)
	{
		int zx = zone / ZONES_PER_SIDE;
		int zy = zone % ZONES_PER_SIDE;
		captureOverlayLayer(Layer.TERRAIN, tiles, planes, sceneSize,
			roofs, tileSettings, roofOffset, slot, zone, zx, zy,
			(cur, p, sx, sy) ->
			{
				SceneTilePaint paint = cur.getSceneTilePaint();
				if (paint != null) captureTilePaint(scene, paint, p, sx, sy);
				SceneTileModel m = cur.getSceneTileModel();
				if (m != null) captureTileModel(m, sx, sy);
			});

		captureOverlayLayer(Layer.WALLS, tiles, planes, sceneSize,
			roofs, tileSettings, roofOffset, slot, zone, zx, zy,
			(cur, p, sx, sy) ->
			{
				WallObject w = cur.getWallObject();
				if (w == null) return;
				captureRenderable(w.getRenderable1(), 0, w.getX(), w.getZ(), w.getY());
				captureRenderable(w.getRenderable2(), 0, w.getX(), w.getZ(), w.getY());
			});

		captureOverlayLayer(Layer.DECORATIVE, tiles, planes, sceneSize,
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

		captureOverlayLayer(Layer.GROUND, tiles, planes, sceneSize,
			roofs, tileSettings, roofOffset, slot, zone, zx, zy,
			(cur, p, sx, sy) ->
			{
				GroundObject g = cur.getGroundObject();
				if (g == null) return;
				captureRenderable(g.getRenderable(), 0, g.getX(), g.getZ(), g.getY());
			});

		captureOverlayGameObjectsLayer(tiles, planes, sceneSize,
			roofs, tileSettings, roofOffset, slot, zone, zx, zy);
	}

	private void captureOverlayLayer(Layer layer,
		Tile[][][] tiles, int planes, int sceneSize,
		int[][][] roofs, byte[][][] tileSettings, int roofOffset,
		int slot, int zone, int zx, int zy, TileCapture body)
	{
		final int L = layer.ordinal();
		int x0 = zx * ZONE_SIZE, x1 = Math.min(x0 + ZONE_SIZE, sceneSize);
		int y0 = zy * ZONE_SIZE, y1 = Math.min(y0 + ZONE_SIZE, sceneSize);
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

	private void captureOverlayGameObjectsLayer(Tile[][][] tiles, int planes, int sceneSize,
		int[][][] roofs, byte[][][] tileSettings, int roofOffset,
		int slot, int zone, int zx, int zy)
	{
		final int L = Layer.GAME_OBJECTS.ordinal();
		int x0 = zx * ZONE_SIZE, x1 = Math.min(x0 + ZONE_SIZE, sceneSize);
		int y0 = zy * ZONE_SIZE, y1 = Math.min(y0 + ZONE_SIZE, sceneSize);
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
		Tile[][][] tiles, int planes, int sceneSize,
		int[][][] roofs, byte[][][] tileSettings, int roofOffset,
		TileCapture body)
	{
		final int L = layer.ordinal();
		for (int outputLevel = 0; outputLevel < planes; outputLevel++)
		{
			for (int zx = 0; zx < ZONES_PER_SIDE; zx++)
			{
				for (int zy = 0; zy < ZONES_PER_SIDE; zy++)
				{
					int zoneStart = vertexCount;
					int x0 = zx * ZONE_SIZE, x1 = Math.min(x0 + ZONE_SIZE, sceneSize);
					int y0 = zy * ZONE_SIZE, y1 = Math.min(y0 + ZONE_SIZE, sceneSize);
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
				RoofInfo roofInfo = roofInfoForTile(roofs, tileSettings, sourceLevel, msx, msy);
				if (roofInfo.visbelow != visbelow) continue;

				Tile t = tiles[sourceLevel][sx][sy];
				if (t == null) continue;
				Tile cur = t;
				while (cur != null)
				{
					int renderLevel = renderLevel(cur, sourceLevel);
					int before = vertexCount;
					body.capture(cur, renderLevel, sx, sy);
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
	private void captureGameObjectsLayer(Tile[][][] tiles, int planes, int sceneSize,
		int[][][] roofs, byte[][][] tileSettings, int roofOffset)
	{
		final int L = Layer.GAME_OBJECTS.ordinal();
		for (int outputLevel = 0; outputLevel < planes; outputLevel++)
		{
			for (int zx = 0; zx < ZONES_PER_SIDE; zx++)
			{
				for (int zy = 0; zy < ZONES_PER_SIDE; zy++)
				{
					int zoneStart = vertexCount;
					int x0 = zx * ZONE_SIZE, x1 = Math.min(x0 + ZONE_SIZE, sceneSize);
					int y0 = zy * ZONE_SIZE, y1 = Math.min(y0 + ZONE_SIZE, sceneSize);
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
				RoofInfo roofInfo = roofInfoForTile(roofs, tileSettings, sourceLevel, msx, msy);
				if (roofInfo.visbelow != visbelow) continue;

				Tile t = tiles[sourceLevel][sx][sy];
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

	private void captureRenderable(Renderable r, int orient, int x, int y, int z)
	{
		if (r == null) return;
		if (r instanceof net.runelite.api.Actor) return;
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
			Model m = pr.model;
			if (m == null)
			{
				if (pr.r instanceof net.runelite.api.Actor) continue;
				m = resolveModel(pr.r);
				if (m == null) continue;
				pr.model = m;
			}
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

		if (!reserveVertices(6)) return;

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
		if (!reserveVertices(faces * 3)) return;

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
		if (m == null) return;
		captureModelUnsorted(m, orient, worldX, worldY, worldZ);
	}

	private void captureModelUnsorted(Model m, int orient, int worldX, int worldY, int worldZ)
	{
		boolean detailedStats = stats.isDetailedModelStats();
		long emitStart = detailedStats ? System.nanoTime() : 0L;
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
		if (!reserveVertices(faces * 3)) return;

		float[] uv = uvScratch;
		int wrote = 0;
		int texturedFaces = 0;
		int overrideFaces = 0;
		long uvNanos = 0;
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
				long uvStart = detailedStats ? System.nanoTime() : 0L;
				texLayer = (faceTextures[f] & 0xFFFF) + 1;
				computeFaceUvs(uv, vx, vy, vz, fa[f], fb[f], fc[f],
					textureFaces, texIndicesA, texIndicesB, texIndicesC, f);
				if (detailedStats)
				{
					uvNanos += System.nanoTime() - uvStart;
				}
				texturedFaces++;
				u0 = uv[0]; v0 = uv[1];
				u1 = uv[2]; v1 = uv[3];
				u2 = uv[4]; v2 = uv[5];
			}

			// HSL override: per-vertex so smooth-banding interpolation
			// picks up the tint across the face. Untextured only —
			// mirrors stock SceneUploader.uploadTempModel scoping.
			if (hasOverride && texLayer == 0)
			{
				overrideFaces++;
				col1 = applyHslOverride(col1, overrideHue, overrideSat, overrideLum, overrideAmount);
				col2 = applyHslOverride(col2, overrideHue, overrideSat, overrideLum, overrideAmount);
				col3 = applyHslOverride(col3, overrideHue, overrideSat, overrideLum, overrideAmount);
			}

			// Pack [texLayer:16 | bias:8 | trans:8] = stock's alphaBias.
			int bias = faceBias != null ? (faceBias[f] & 0xFF) : 0;
			int trans = faceTransparencies != null ? (faceTransparencies[f] & 0xFF) : 0;
			int packedTexLayer = texLayer | (bias << 16) | (trans << 24);
			boolean noUv = texLayer == 0;

			int ia = fa[f];
			int ib = fb[f];
			int ic = fc[f];
			if (col1 == col2 && col1 == col3)
			{
				int rgbOffset = (col1 & 0xFFFF) * 3;
				float light = (float) (col1 & 0xFFFF);
				float r = HSL_RGB[rgbOffset];
				float g = HSL_RGB[rgbOffset + 1];
				float b = HSL_RGB[rgbOffset + 2];
				if (noUv)
				{
					writeRotatedVertexRgbNoUv(vx[ia], vy[ia], vz[ia], cos, sin, worldX, worldY, worldZ, light, r, g, b, packedTexLayer);
					writeRotatedVertexRgbNoUv(vx[ib], vy[ib], vz[ib], cos, sin, worldX, worldY, worldZ, light, r, g, b, packedTexLayer);
					writeRotatedVertexRgbNoUv(vx[ic], vy[ic], vz[ic], cos, sin, worldX, worldY, worldZ, light, r, g, b, packedTexLayer);
				}
				else
				{
					writeRotatedVertexRgb(vx[ia], vy[ia], vz[ia], cos, sin, worldX, worldY, worldZ, light, r, g, b, u0, v0, packedTexLayer);
					writeRotatedVertexRgb(vx[ib], vy[ib], vz[ib], cos, sin, worldX, worldY, worldZ, light, r, g, b, u1, v1, packedTexLayer);
					writeRotatedVertexRgb(vx[ic], vy[ic], vz[ic], cos, sin, worldX, worldY, worldZ, light, r, g, b, u2, v2, packedTexLayer);
				}
			}
			else
			{
				int rgbOffset1 = (col1 & 0xFFFF) * 3;
				int rgbOffset2 = (col2 & 0xFFFF) * 3;
				int rgbOffset3 = (col3 & 0xFFFF) * 3;
				if (noUv)
				{
					writeRotatedVertexRgbNoUv(vx[ia], vy[ia], vz[ia], cos, sin, worldX, worldY, worldZ,
						(float) (col1 & 0xFFFF), HSL_RGB[rgbOffset1], HSL_RGB[rgbOffset1 + 1], HSL_RGB[rgbOffset1 + 2], packedTexLayer);
					writeRotatedVertexRgbNoUv(vx[ib], vy[ib], vz[ib], cos, sin, worldX, worldY, worldZ,
						(float) (col2 & 0xFFFF), HSL_RGB[rgbOffset2], HSL_RGB[rgbOffset2 + 1], HSL_RGB[rgbOffset2 + 2], packedTexLayer);
					writeRotatedVertexRgbNoUv(vx[ic], vy[ic], vz[ic], cos, sin, worldX, worldY, worldZ,
						(float) (col3 & 0xFFFF), HSL_RGB[rgbOffset3], HSL_RGB[rgbOffset3 + 1], HSL_RGB[rgbOffset3 + 2], packedTexLayer);
				}
				else
				{
					writeRotatedVertexRgb(vx[ia], vy[ia], vz[ia], cos, sin, worldX, worldY, worldZ,
						(float) (col1 & 0xFFFF), HSL_RGB[rgbOffset1], HSL_RGB[rgbOffset1 + 1], HSL_RGB[rgbOffset1 + 2], u0, v0, packedTexLayer);
					writeRotatedVertexRgb(vx[ib], vy[ib], vz[ib], cos, sin, worldX, worldY, worldZ,
						(float) (col2 & 0xFFFF), HSL_RGB[rgbOffset2], HSL_RGB[rgbOffset2 + 1], HSL_RGB[rgbOffset2 + 2], u1, v1, packedTexLayer);
					writeRotatedVertexRgb(vx[ic], vy[ic], vz[ic], cos, sin, worldX, worldY, worldZ,
						(float) (col3 & 0xFFFF), HSL_RGB[rgbOffset3], HSL_RGB[rgbOffset3 + 1], HSL_RGB[rgbOffset3 + 2], u2, v2, packedTexLayer);
				}
			}
			wrote += 3;
		}
		vertexCount += wrote;
		if (detailedStats)
		{
			stats.unsortedModels.incrementAndGet();
			stats.unsortedFaces.addAndGet(wrote / 3);
			long emitNanos = System.nanoTime() - emitStart;
			stats.modelEmitNanos.addAndGet(emitNanos);
			stats.modelUnsortedEmitNanos.addAndGet(emitNanos);
			stats.modelUvNanos.addAndGet(uvNanos);
			stats.texturedEmitFaces.addAndGet(texturedFaces);
			stats.overrideEmitFaces.addAndGet(overrideFaces);
		}
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
	 * <p>Stock GPU uploads every dynamic/temp callback independently, so this
	 * path intentionally does not dedupe by model identity.
	 */
	void captureModelSorted(Projection proj, Model m, int orient, int worldX, int worldY, int worldZ)
	{
		captureModelSorted(proj, m, orient, worldX, worldY, worldZ, Renderable.RENDERMODE_DEFAULT);
	}

	void captureModelSorted(Projection proj, Model m, int orient, int worldX, int worldY, int worldZ, int renderMode)
	{
		if (m == null || proj == null) return;

		boolean detailedStats = stats.isDetailedModelStats();
		boolean prioritySort = renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH;
		int priorityStart = prioritySort ? vertexCount : -1;
		ModelCacheEntry modelInfo = modelInfo(m);
		recordModelInstance(modelInfo, orient, worldX, worldY, worldZ, renderMode);
		boolean needsFaceSort = prioritySort || modelInfo.hasTransparentFaces;
		if (!needsFaceSort && modelInfo.faceCount <= OPAQUE_UNSORTED_FACE_THRESHOLD)
		{
			captureModelUnsorted(m, orient, worldX, worldY, worldZ);
			recordPriorityRange(priorityStart);
			return;
		}

		if (!needsFaceSort)
		{
			if (!captureModelCullOnlyFused(proj, m, orient, worldX, worldY, worldZ))
			{
				if (detailedStats)
				{
					stats.sortFallbackModels.incrementAndGet();
				}
				captureModelUnsorted(m, orient, worldX, worldY, worldZ);
			}
			recordPriorityRange(priorityStart);
			return;
		}

		long sortStart = detailedStats ? System.nanoTime() : 0L;
		boolean sorted = sorter.sort(proj, m, orient, worldX, worldY, worldZ, prioritySort);
		if (detailedStats)
		{
			stats.addNanos(stats.modelFullSortNanos, sortStart);
			stats.addNanos(stats.modelSortNanos, sortStart);
		}
		if (!sorted)
		{
			// Sorting is a quality pass, not a visibility gate. Some transient
			// renderables (projectiles / spotanims) have bounds that make the
			// sorter reject them; keep them visible by falling back to the basic
			// model emitter.
			if (detailedStats)
			{
				stats.sortFallbackModels.incrementAndGet();
			}
			captureModelUnsorted(m, orient, worldX, worldY, worldZ);
			recordPriorityRange(priorityStart);
			return;
		}

		int faces = sorter.sortedCount;
		if (faces == 0)
		{
			if (detailedStats)
			{
				stats.sortFallbackModels.incrementAndGet();
			}
			captureModelUnsorted(m, orient, worldX, worldY, worldZ);
			recordPriorityRange(priorityStart);
			return;
		}
		if (!reserveVertices(faces * 3)) return;

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
		long emitStart = detailedStats ? System.nanoTime() : 0L;
		int texturedFaces = 0;
		int overrideFaces = 0;
		long uvNanos = 0;
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
				long uvStart = detailedStats ? System.nanoTime() : 0L;
				texLayer = (faceTextures[f] & 0xFFFF) + 1;
				computeFaceUvs(uv, vxs, vys, vzs, fa[f], fb[f], fc[f],
					textureFaces, texIndicesA, texIndicesB, texIndicesC, f);
				if (detailedStats)
				{
					uvNanos += System.nanoTime() - uvStart;
				}
				texturedFaces++;
				u0 = uv[0]; v0 = uv[1];
				u1 = uv[2]; v1 = uv[3];
				u2 = uv[4]; v2 = uv[5];
			}

			if (hasOverride && texLayer == 0)
			{
				overrideFaces++;
				col1 = applyHslOverride(col1, overrideHue, overrideSat, overrideLum, overrideAmount);
				col2 = applyHslOverride(col2, overrideHue, overrideSat, overrideLum, overrideAmount);
				col3 = applyHslOverride(col3, overrideHue, overrideSat, overrideLum, overrideAmount);
			}

			int bias = faceBiasArr != null ? (faceBiasArr[f] & 0xFF) : 0;
			int trans = faceTransparencies != null ? (faceTransparencies[f] & 0xFF) : 0;
			int packedTexLayer = texLayer | (bias << 16) | (trans << 24);
			boolean noUv = texLayer == 0;

			int ia = fa[f];
			int ib = fb[f];
			int ic = fc[f];
			if (col1 == col2 && col1 == col3)
			{
				int rgbOffset = (col1 & 0xFFFF) * 3;
				float light = (float) (col1 & 0xFFFF);
				float r = HSL_RGB[rgbOffset];
				float g = HSL_RGB[rgbOffset + 1];
				float b = HSL_RGB[rgbOffset + 2];
				if (noUv)
				{
					writePackedTriangleRgbNoUv(
						lx[ia], ly[ia], lz[ia],
						lx[ib], ly[ib], lz[ib],
						lx[ic], ly[ic], lz[ic],
						light, r, g, b, packedTexLayer);
				}
				else
				{
					writePackedVertexRgb(lx[ia], ly[ia], lz[ia], light, r, g, b, u0, v0, packedTexLayer);
					writePackedVertexRgb(lx[ib], ly[ib], lz[ib], light, r, g, b, u1, v1, packedTexLayer);
					writePackedVertexRgb(lx[ic], ly[ic], lz[ic], light, r, g, b, u2, v2, packedTexLayer);
				}
			}
			else
			{
				int rgbOffset1 = (col1 & 0xFFFF) * 3;
				int rgbOffset2 = (col2 & 0xFFFF) * 3;
				int rgbOffset3 = (col3 & 0xFFFF) * 3;
				if (noUv)
				{
					writePackedVertexRgbNoUv(lx[ia], ly[ia], lz[ia],
						(float) (col1 & 0xFFFF), HSL_RGB[rgbOffset1], HSL_RGB[rgbOffset1 + 1], HSL_RGB[rgbOffset1 + 2], packedTexLayer);
					writePackedVertexRgbNoUv(lx[ib], ly[ib], lz[ib],
						(float) (col2 & 0xFFFF), HSL_RGB[rgbOffset2], HSL_RGB[rgbOffset2 + 1], HSL_RGB[rgbOffset2 + 2], packedTexLayer);
					writePackedVertexRgbNoUv(lx[ic], ly[ic], lz[ic],
						(float) (col3 & 0xFFFF), HSL_RGB[rgbOffset3], HSL_RGB[rgbOffset3 + 1], HSL_RGB[rgbOffset3 + 2], packedTexLayer);
				}
				else
				{
					writePackedVertexRgb(lx[ia], ly[ia], lz[ia],
						(float) (col1 & 0xFFFF), HSL_RGB[rgbOffset1], HSL_RGB[rgbOffset1 + 1], HSL_RGB[rgbOffset1 + 2], u0, v0, packedTexLayer);
					writePackedVertexRgb(lx[ib], ly[ib], lz[ib],
						(float) (col2 & 0xFFFF), HSL_RGB[rgbOffset2], HSL_RGB[rgbOffset2 + 1], HSL_RGB[rgbOffset2 + 2], u1, v1, packedTexLayer);
					writePackedVertexRgb(lx[ic], ly[ic], lz[ic],
						(float) (col3 & 0xFFFF), HSL_RGB[rgbOffset3], HSL_RGB[rgbOffset3 + 1], HSL_RGB[rgbOffset3 + 2], u2, v2, packedTexLayer);
				}
			}
			wrote += 3;
		}
		vertexCount += wrote;
		if (detailedStats)
		{
			stats.sortedModels.incrementAndGet();
			if (needsFaceSort)
			{
				stats.fullSortModels.incrementAndGet();
				stats.fullSortTransparentFaces.addAndGet(modelInfo.transparentFaces);
			}
			else
			{
				stats.cullOnlyModels.incrementAndGet();
			}
			stats.sortedFaces.addAndGet(wrote / 3);
			long emitNanos = System.nanoTime() - emitStart;
			stats.modelEmitNanos.addAndGet(emitNanos);
			stats.modelSortedEmitNanos.addAndGet(emitNanos);
			stats.modelUvNanos.addAndGet(uvNanos);
			stats.texturedEmitFaces.addAndGet(texturedFaces);
			stats.overrideEmitFaces.addAndGet(overrideFaces);
		}
		recordPriorityRange(priorityStart);
	}

	private void recordPriorityRange(int start)
	{
		if (start < 0 || vertexCount <= start)
		{
			return;
		}
		if (priorityRangeCount == priorityRangeStarts.length)
		{
			int newSize = priorityRangeStarts.length * 2;
			priorityRangeStarts = java.util.Arrays.copyOf(priorityRangeStarts, newSize);
			priorityRangeEnds = java.util.Arrays.copyOf(priorityRangeEnds, newSize);
			prioritySkipPairs = java.util.Arrays.copyOf(prioritySkipPairs, newSize * 2);
		}
		priorityRangeStarts[priorityRangeCount] = start;
		priorityRangeEnds[priorityRangeCount] = vertexCount;
		prioritySkipPairs[priorityRangeCount * 2] = start;
		prioritySkipPairs[priorityRangeCount * 2 + 1] = vertexCount;
		priorityRangeCount++;
	}

	private boolean captureModelCullOnlyFused(Projection proj, Model m, int orientation, int wx, int wy, int wz)
	{
		boolean detailedStats = stats.isDetailedModelStats();
		final int modelVertexCount = m.getVerticesCount();
		if (modelVertexCount > ModelSorter.MAX_VERTEX_COUNT)
		{
			return false;
		}

		final float[] vxs = m.getVerticesX();
		final float[] vys = m.getVerticesY();
		final float[] vzs = m.getVerticesZ();
		if (vxs == null || vys == null || vzs == null)
		{
			return false;
		}

		final int faceCount = Math.min(m.getFaceCount(), ModelSorter.MAX_FACE_COUNT);
		final int[] fa = m.getFaceIndices1();
		final int[] fb = m.getFaceIndices2();
		final int[] fc = m.getFaceIndices3();
		if (fa == null || fb == null || fc == null)
		{
			return false;
		}

		float orientSine = 0f;
		float orientCosine = 0f;
		if (orientation != 0)
		{
			orientSine = Perspective.SINE[orientation & 0x7FF] / 65536f;
			orientCosine = Perspective.COSINE[orientation & 0x7FF] / 65536f;
		}

		long cullStart = detailedStats ? System.nanoTime() : 0L;
		for (int v = 0; v < modelVertexCount; v++)
		{
			float vx = vxs[v];
			float vy = vys[v];
			float vz = vzs[v];

			if (orientation != 0)
			{
				float x0 = vx;
				vx = vz * orientSine + x0 * orientCosine;
				vz = vz * orientCosine - x0 * orientSine;
			}

			vx += wx;
			vy += wy;
			vz += wz;

			cullLocalX[v] = vx;
			cullLocalY[v] = vy;
			cullLocalZ[v] = vz;

			float[] p = proj.project(vx, vy, vz, cullProjectScratch);
			if (p[2] < 50f)
			{
				return false;
			}

			cullProjX[v] = p[0] / p[2];
			cullProjY[v] = p[1] / p[2];
		}
		if (detailedStats)
		{
			long cullNanos = System.nanoTime() - cullStart;
			stats.modelCullOnlyNanos.addAndGet(cullNanos);
			stats.modelSortNanos.addAndGet(cullNanos);
		}

		int[] c1 = m.getFaceColors1();
		int[] c2 = m.getFaceColors2();
		int[] c3 = m.getFaceColors3();
		short[] faceTextures = m.getFaceTextures();
		byte[] textureFaces = m.getTextureFaces();
		int[] texIndicesA = m.getTexIndices1();
		int[] texIndicesB = m.getTexIndices2();
		int[] texIndicesC = m.getTexIndices3();
		byte[] faceTransparencies = m.getFaceTransparencies();
		byte[] faceBiasArr = m.getFaceBias();

		final byte overrideAmount = m.getOverrideAmount();
		final byte overrideHue = m.getOverrideHue();
		final byte overrideSat = m.getOverrideSaturation();
		final byte overrideLum = m.getOverrideLuminance();
		final boolean hasOverride = (overrideAmount & 0xFF) != 0;
		float[] uv = uvScratch;

		int wrote = 0;
		int texturedFaces = 0;
		int overrideFaces = 0;
		long uvNanos = 0;
		long emitStart = detailedStats ? System.nanoTime() : 0L;
		for (int f = 0; f < faceCount; f++)
		{
			if (c3 != null && c3[f] == -2)
			{
				continue;
			}

			final int ia = fa[f];
			final int ib = fb[f];
			final int ic = fc[f];

			final float aX = cullProjX[ia], aY = cullProjY[ia];
			final float bX = cullProjX[ib], bY = cullProjY[ib];
			final float cX = cullProjX[ic], cY = cullProjY[ic];

			if ((aX - bX) * (cY - bY) - (cX - bX) * (aY - bY) <= 0)
			{
				continue;
			}

			if (vertexCount + wrote > writeVertexLimit - 3)
			{
				overflow();
				break;
			}

			int col1 = c1 != null ? c1[f] : 0;
			int col2 = col1;
			int col3 = col1;
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
				long uvStart = detailedStats ? System.nanoTime() : 0L;
				texLayer = (faceTextures[f] & 0xFFFF) + 1;
				computeFaceUvs(uv, vxs, vys, vzs, ia, ib, ic, textureFaces, texIndicesA, texIndicesB, texIndicesC, f);
				if (detailedStats)
				{
					uvNanos += System.nanoTime() - uvStart;
				}
				texturedFaces++;
				u0 = uv[0]; v0 = uv[1];
				u1 = uv[2]; v1 = uv[3];
				u2 = uv[4]; v2 = uv[5];
			}

			if (hasOverride && texLayer == 0)
			{
				overrideFaces++;
				col1 = applyHslOverride(col1, overrideHue, overrideSat, overrideLum, overrideAmount);
				col2 = applyHslOverride(col2, overrideHue, overrideSat, overrideLum, overrideAmount);
				col3 = applyHslOverride(col3, overrideHue, overrideSat, overrideLum, overrideAmount);
			}

			int bias = faceBiasArr != null ? (faceBiasArr[f] & 0xFF) : 0;
			int trans = faceTransparencies != null ? (faceTransparencies[f] & 0xFF) : 0;
			int packedTexLayer = texLayer | (bias << 16) | (trans << 24);
			boolean noUv = texLayer == 0;

			if (col1 == col2 && col1 == col3)
			{
				int rgbOffset = (col1 & 0xFFFF) * 3;
				float light = (float) (col1 & 0xFFFF);
				float r = HSL_RGB[rgbOffset];
				float g = HSL_RGB[rgbOffset + 1];
				float b = HSL_RGB[rgbOffset + 2];
				if (noUv)
				{
					writePackedTriangleRgbNoUv(
						cullLocalX[ia], cullLocalY[ia], cullLocalZ[ia],
						cullLocalX[ib], cullLocalY[ib], cullLocalZ[ib],
						cullLocalX[ic], cullLocalY[ic], cullLocalZ[ic],
						light, r, g, b, packedTexLayer);
				}
				else
				{
					writePackedVertexRgb(cullLocalX[ia], cullLocalY[ia], cullLocalZ[ia], light, r, g, b, u0, v0, packedTexLayer);
					writePackedVertexRgb(cullLocalX[ib], cullLocalY[ib], cullLocalZ[ib], light, r, g, b, u1, v1, packedTexLayer);
					writePackedVertexRgb(cullLocalX[ic], cullLocalY[ic], cullLocalZ[ic], light, r, g, b, u2, v2, packedTexLayer);
				}
			}
			else
			{
				int rgbOffset1 = (col1 & 0xFFFF) * 3;
				int rgbOffset2 = (col2 & 0xFFFF) * 3;
				int rgbOffset3 = (col3 & 0xFFFF) * 3;
				if (noUv)
				{
					writePackedVertexRgbNoUv(cullLocalX[ia], cullLocalY[ia], cullLocalZ[ia],
						(float) (col1 & 0xFFFF), HSL_RGB[rgbOffset1], HSL_RGB[rgbOffset1 + 1], HSL_RGB[rgbOffset1 + 2], packedTexLayer);
					writePackedVertexRgbNoUv(cullLocalX[ib], cullLocalY[ib], cullLocalZ[ib],
						(float) (col2 & 0xFFFF), HSL_RGB[rgbOffset2], HSL_RGB[rgbOffset2 + 1], HSL_RGB[rgbOffset2 + 2], packedTexLayer);
					writePackedVertexRgbNoUv(cullLocalX[ic], cullLocalY[ic], cullLocalZ[ic],
						(float) (col3 & 0xFFFF), HSL_RGB[rgbOffset3], HSL_RGB[rgbOffset3 + 1], HSL_RGB[rgbOffset3 + 2], packedTexLayer);
				}
				else
				{
					writePackedVertexRgb(cullLocalX[ia], cullLocalY[ia], cullLocalZ[ia],
						(float) (col1 & 0xFFFF), HSL_RGB[rgbOffset1], HSL_RGB[rgbOffset1 + 1], HSL_RGB[rgbOffset1 + 2], u0, v0, packedTexLayer);
					writePackedVertexRgb(cullLocalX[ib], cullLocalY[ib], cullLocalZ[ib],
						(float) (col2 & 0xFFFF), HSL_RGB[rgbOffset2], HSL_RGB[rgbOffset2 + 1], HSL_RGB[rgbOffset2 + 2], u1, v1, packedTexLayer);
					writePackedVertexRgb(cullLocalX[ic], cullLocalY[ic], cullLocalZ[ic],
						(float) (col3 & 0xFFFF), HSL_RGB[rgbOffset3], HSL_RGB[rgbOffset3 + 1], HSL_RGB[rgbOffset3 + 2], u2, v2, packedTexLayer);
				}
			}
			wrote += 3;
		}

		if (wrote == 0)
		{
			return true;
		}

		vertexCount += wrote;
		if (detailedStats)
		{
			stats.sortedModels.incrementAndGet();
			stats.cullOnlyModels.incrementAndGet();
			stats.sortedFaces.addAndGet(wrote / 3);
			long emitNanos = System.nanoTime() - emitStart;
			stats.modelEmitNanos.addAndGet(emitNanos);
			stats.modelSortedEmitNanos.addAndGet(emitNanos);
			stats.modelUvNanos.addAndGet(uvNanos);
			stats.texturedEmitFaces.addAndGet(texturedFaces);
			stats.overrideEmitFaces.addAndGet(overrideFaces);
		}
		return true;
	}

	private static int countTransparentFaces(int faceCount, byte[] transparencies)
	{
		if (transparencies == null)
		{
			return 0;
		}
		int transparent = 0;
		int faces = Math.min(faceCount, transparencies.length);
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
		final int slot = sync.currentFrame();
		final int slotFirstVertex = MAX_STATIC_VERTICES + slot * MAX_FRAME_VERTICES - staticVertexCount();
		final int staticFirstVertex = 0;
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

			ByteBuffer alphaFragPush = stack.malloc(32);
			alphaFragPush.putFloat(fogR).putFloat(fogG).putFloat(fogB).putFloat(brightness);
			alphaFragPush.putFloat(textureLightMode);
			alphaFragPush.putFloat((float) colorBlindMode);
			alphaFragPush.putFloat(colorBlindIntensity);
			alphaFragPush.putFloat(10f + smoothBanding);
			alphaFragPush.flip();

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
			final int loCur = currentPlane;
			final int loMax = maxPlane;
			int radiusTiles = (int) Math.ceil(drawDistanceTiles + fogDepthTiles + 2f);
			int radiusZones = Math.max(1, (radiusTiles + ZONE_SIZE - 1) / ZONE_SIZE);
			boolean fullZoneRange = radiusZones >= ZONES_PER_SIDE;
			int camTileX = clamp((int) Math.floor(cameraX / 128f), 0, Constants.SCENE_SIZE - 1);
			int camTileZ = clamp((int) Math.floor(cameraZ / 128f), 0, Constants.SCENE_SIZE - 1);
			int camZoneX = camTileX / ZONE_SIZE;
			int camZoneZ = camTileZ / ZONE_SIZE;
			int minZoneX = fullZoneRange ? 0 : Math.max(0, camZoneX - radiusZones);
			int maxZoneX = fullZoneRange ? ZONES_PER_SIDE - 1 : Math.min(ZONES_PER_SIDE - 1, camZoneX + radiusZones);
			int minZoneZ = fullZoneRange ? 0 : Math.max(0, camZoneZ - radiusZones);
			int maxZoneZ = fullZoneRange ? ZONES_PER_SIDE - 1 : Math.min(ZONES_PER_SIDE - 1, camZoneZ + radiusZones);
			int layerStart = 0;
			for (int i = 0; i < LAYER_COUNT; i++)
			{
				int regionEnd = i == LAYER_COUNT - 1 ? vertexCount : regionEnds[i];
				if (regionEnd <= layerStart)
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
					boundPipeline = want;
				}

				if (LAYERS[i] == Layer.DYNAMIC)
				{
					int dynamicStart = overlayNextVertex[slot];
					int dynamicEnd = dynamicOpaqueEnd >= dynamicStart ? Math.min(dynamicOpaqueEnd, regionEnd) : regionEnd;
					drawRange(cmd, dynamicStart, dynamicEnd, prioritySkipPairs, priorityRangeCount, priorityRangeCount > 0, slotFirstVertex,
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
						drawStaticPlane(cmd, i, p, layerStart,
							fullZoneRange, minZoneX, maxZoneX, minZoneZ, maxZoneZ,
							skipScratch, skipPairs, p > loCur,
							slot, staticFirstVertex, want.layout(), vertPush, fragPush);
						drawOverlayPlane(cmd, i, p, minZoneX, maxZoneX, minZoneZ, maxZoneZ,
							skipScratch, skipPairs, p > loCur,
							slot, slotFirstVertex, want.layout(), vertPush, fragPush);
					}
				}
				layerStart = regionEnd;
			}

			if (priorityRangeCount > 0)
			{
				vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, priorityColorPipeline.handle());
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
					priorityColorPipeline.layout(), 0, stack.longs(descriptorSet), null);
				drawPriorityRanges(cmd, slotFirstVertex, priorityColorPipeline.layout(), vertPush, fragPush);

				vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, priorityDepthPipeline.handle());
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
					priorityDepthPipeline.layout(), 0, stack.longs(descriptorSet), null);
				drawPriorityRanges(cmd, slotFirstVertex, priorityDepthPipeline.layout(), vertPush, fragPush);
			}

			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, alphaPipeline.handle());
			vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
				alphaPipeline.layout(), 0, stack.longs(descriptorSet), null);
			drawAlphaPass(cmd, loMin, loMax, fullZoneRange, minZoneX, maxZoneX, minZoneZ, maxZoneZ,
				skipScratch, skipPairs, loCur,
				slot, staticFirstVertex, slotFirstVertex, alphaPipeline.layout(), vertPush, alphaFragPush);
		}
	}

	void recordBeforeRenderPass(VkCommandBuffer cmd)
	{
		if (modelComputePipeline == null)
		{
			return;
		}
		modelComputePipeline.recordDispatch(cmd, sync.currentFrame(), modelInstanceCount);
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
			int layerStart = layerStartFor(i);
			int regionEnd = i == LAYER_COUNT - 1 ? vertexCount : regionEnds[i];
			if (regionEnd <= layerStart)
			{
				continue;
			}
			if (LAYERS[i] == Layer.DYNAMIC)
			{
				int dynamicStart = overlayNextVertex[slot];
				int dynamicEnd = dynamicOpaqueEnd >= dynamicStart ? Math.min(dynamicOpaqueEnd, regionEnd) : regionEnd;
				drawRange(cmd, dynamicStart, dynamicEnd, prioritySkipPairs, priorityRangeCount,
					false, slotFirstVertex, pipelineLayout, vertPush, fragPush);
			}
			else
			{
				for (int p = loMin; p <= loMax; p++)
				{
					drawStaticPlane(cmd, i, p, layerStart,
						fullZoneRange, minZoneX, maxZoneX, minZoneZ, maxZoneZ,
						skips, skipPairs, p > currentPlane,
						slot, staticFirstVertex, pipelineLayout, vertPush, fragPush);
					drawOverlayPlane(cmd, i, p, minZoneX, maxZoneX, minZoneZ, maxZoneZ,
						skips, skipPairs, p > currentPlane,
						slot, slotFirstVertex, pipelineLayout, vertPush, fragPush);
				}
			}
		}
	}

	private void drawPriorityRanges(VkCommandBuffer cmd, int slotFirstVertex,
									long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		for (int i = 0; i < priorityRangeCount; i++)
		{
			int start = priorityRangeStarts[i];
			int end = priorityRangeEnds[i];
			if (end <= start)
			{
				continue;
			}
			drawRange(cmd, start, end, skipScratch, 0, false,
				slotFirstVertex, pipelineLayout, vertPush, fragPush);
		}
	}

	private void drawStaticPlane(VkCommandBuffer cmd, int layer, int plane, int layerStart,
								 boolean fullZoneRange, int minZoneX, int maxZoneX, int minZoneZ, int maxZoneZ,
								 int[] skips, int skipPairs, boolean applySkips,
								 int slot, int slotFirstVertex, long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		int planeStart = plane == 0 ? layerStart : planeEnds[layer][plane - 1];
		int planeEnd = planeEnds[layer][plane];
		if (planeEnd <= planeStart)
		{
			return;
		}

		if (fullZoneRange)
		{
			if (overlaySlotHasZones[slot] && (!applySkips || skipPairs == 0))
			{
				int overlayPairs = 0;
				for (int zoneIdx = 0; zoneIdx < ZONE_COUNT; zoneIdx++)
				{
					if (!overlayZoneValid[slot][zoneIdx])
					{
						continue;
					}
					int count = zoneVertexCount[layer][plane][zoneIdx];
					if (count <= 0)
					{
						continue;
					}
					if (overlayPairs * 2 + 2 > skips.length)
					{
						skipScratch = java.util.Arrays.copyOf(skips, skips.length * 2);
						skips = skipScratch;
					}
					int start = zoneVertexStart[layer][plane][zoneIdx];
					skips[overlayPairs * 2] = start;
					skips[overlayPairs * 2 + 1] = start + count;
					overlayPairs++;
				}
				drawRange(cmd, planeStart, planeEnd, skips, overlayPairs, overlayPairs > 0,
					slotFirstVertex, pipelineLayout, vertPush, fragPush);
				return;
			}
			if (overlaySlotHasZones[slot])
			{
				// When roof skips are active, keep the simple per-zone path so
				// roof and overlay skip lists cannot fight each other.
			}
			else
			{
				drawRange(cmd, planeStart, planeEnd, skips, skipPairs, applySkips,
					slotFirstVertex, pipelineLayout, vertPush, fragPush);
				return;
			}
		}

		for (int zx = minZoneX; zx <= maxZoneX; zx++)
		{
			for (int zz = minZoneZ; zz <= maxZoneZ; zz++)
			{
				int zoneIdx = zx * ZONES_PER_SIDE + zz;
				if (overlayZoneValid[slot][zoneIdx])
				{
					continue;
				}
				int count = zoneVertexCount[layer][plane][zoneIdx];
				if (count <= 0)
				{
					continue;
				}
				int start = zoneVertexStart[layer][plane][zoneIdx];
				drawRange(cmd, start, start + count, skips, skipPairs, applySkips,
					slotFirstVertex, pipelineLayout, vertPush, fragPush);
			}
		}
	}

	private void drawOverlayPlane(VkCommandBuffer cmd, int layer, int plane,
								  int minZoneX, int maxZoneX, int minZoneZ, int maxZoneZ,
								  int[] skips, int skipPairs, boolean applySkips,
								  int slot, int slotFirstVertex, long pipelineLayout,
								  ByteBuffer vertPush, ByteBuffer fragPush)
	{
		if (!overlaySlotHasZones[slot])
		{
			return;
		}

		for (int zx = minZoneX; zx <= maxZoneX; zx++)
		{
			for (int zz = minZoneZ; zz <= maxZoneZ; zz++)
			{
				int zoneIdx = zx * ZONES_PER_SIDE + zz;
				if (!overlayZoneValid[slot][zoneIdx])
				{
					continue;
				}
				int count = overlayZoneCount[slot][layer][plane][zoneIdx];
				if (count <= 0)
				{
					continue;
				}
				int start = overlayZoneStart[slot][layer][plane][zoneIdx];
				drawRange(cmd, start, start + count, skips, skipPairs, applySkips,
					slotFirstVertex, pipelineLayout, vertPush, fragPush);
			}
		}
	}

	private static int clamp(int value, int min, int max)
	{
		return value < min ? min : (value > max ? max : value);
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

	private static final class RoofInfo
	{
		final boolean visbelow;
		final int roofId;

		private RoofInfo(boolean visbelow, int roofId)
		{
			this.visbelow = visbelow;
			this.roofId = roofId;
		}
	}

	private static RoofInfo roofInfoForTile(int[][][] roofs, byte[][][] tileSettings,
											int sourceLevel, int msx, int msy)
	{
		int mapLevel = sourceLevel;
		if (isBridge(tileSettings, msx, msy))
		{
			mapLevel++;
		}

		boolean visbelow = mapLevel < MAX_PLANES && hasTileFlag(tileSettings, mapLevel, msx, msy,
			Constants.TILE_FLAG_VIS_BELOW);
		int roofId = visbelow || mapLevel == 0 ? 0 : tileRoofIdAt(roofs, mapLevel - 1, msx, msy);
		return new RoofInfo(visbelow, roofId);
	}

	private static boolean isBridge(byte[][][] tileSettings, int msx, int msy)
	{
		return hasTileFlag(tileSettings, 1, msx, msy, Constants.TILE_FLAG_BRIDGE);
	}

	private static boolean hasTileFlag(byte[][][] tileSettings, int plane, int x, int y, int flag)
	{
		if (tileSettings == null || plane < 0 || plane >= tileSettings.length) return false;
		byte[][] planeSettings = tileSettings[plane];
		if (planeSettings == null || x < 0 || x >= planeSettings.length) return false;
		byte[] row = planeSettings[x];
		return row != null && y >= 0 && y < row.length && (row[y] & flag) != 0;
	}

	private static int renderLevel(Tile tile, int fallbackLevel)
	{
		if (tile == null) return fallbackLevel;
		return Math.max(0, Math.min(MAX_PLANES - 1, tile.getRenderLevel()));
	}

	private void recordRoofRange(int roofId, int vertexStart, int vertexCount)
	{
		if (rebuildingOverlayZone)
		{
			return;
		}
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

	private static void drawRange(VkCommandBuffer cmd, int start, int end, int[] skips, int pairCount,
								  boolean applySkips, int slotFirstVertex,
								  long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		if (end <= start) return;
		if (applySkips && pairCount > 0)
		{
			drawWithSkips(cmd, start, end, skips, pairCount, slotFirstVertex,
				pipelineLayout, vertPush, fragPush);
			return;
		}

		// LANDMINE (MoltenVK #2483): re-push every draw. Push constants can
		// go undefined across consecutive draws without it.
		vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,   0,  vertPush);
		vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 96, fragPush);
		vkCmdDraw(cmd, end - start, 1, slotFirstVertex + start, 0);
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
		if (modelComputePipeline != null) modelComputePipeline.close();
		if (modelComputeOutputBuffer != null) modelComputeOutputBuffer.close();
		modelInstanceBuffer.close();
		modelMeshArena.close();
		vbuf.close();
		fillPipeline.close();
		alphaPipeline.close();
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

	private void writeRotatedVertexRgb(float lx, float ly, float lz,
									   float cos, float sin,
									   int wx, int wy, int wz,
									   float light, float r, float g, float b,
									   float u, float v, int texLayer)
	{
		float rx = lx * cos + lz * sin;
		float rz = -lx * sin + lz * cos;
		writePackedVertexRgb(rx + wx, ly + wy, rz + wz, light, r, g, b, u, v, texLayer);
	}

	private void writeRotatedVertexRgbNoUv(float lx, float ly, float lz,
										   float cos, float sin,
										   int wx, int wy, int wz,
										   float light, float r, float g, float b,
										   int texLayer)
	{
		float rx = lx * cos + lz * sin;
		float rz = -lx * sin + lz * cos;
		writePackedVertexRgbNoUv(rx + wx, ly + wy, rz + wz, light, r, g, b, texLayer);
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
		writePackedVertexRgb(x, y, z, light, HSL_RGB[rgbOffset], HSL_RGB[rgbOffset + 1], HSL_RGB[rgbOffset + 2], u, v, texLayer);
	}

	private void writePackedVertexRgb(float x, float y, float z,
									  float light, float r, float g, float b,
									  float u, float v, int texLayer)
	{
		long p = writePtr;
		MemoryUtil.memPutFloat(p, x);
		MemoryUtil.memPutFloat(p + 4, y);
		MemoryUtil.memPutFloat(p + 8, z);
		MemoryUtil.memPutFloat(p + 12, 0f);
		MemoryUtil.memPutFloat(p + 16, r);
		MemoryUtil.memPutFloat(p + 20, g);
		MemoryUtil.memPutFloat(p + 24, b);
		MemoryUtil.memPutFloat(p + 28, 0f);
		MemoryUtil.memPutFloat(p + 32, light);
		MemoryUtil.memPutFloat(p + 36, u);
		MemoryUtil.memPutFloat(p + 40, v);
		MemoryUtil.memPutInt(p + 44, texLayer);
		writePtr = p + ScenePipeline.VERTEX_STRIDE;
	}

	private void writePackedVertexRgbNoUv(float x, float y, float z,
										  float light, float r, float g, float b,
										  int texLayer)
	{
		long p = writePtr;
		MemoryUtil.memPutFloat(p, x);
		MemoryUtil.memPutFloat(p + 4, y);
		MemoryUtil.memPutFloat(p + 8, z);
		MemoryUtil.memPutFloat(p + 12, 0f);
		MemoryUtil.memPutFloat(p + 16, r);
		MemoryUtil.memPutFloat(p + 20, g);
		MemoryUtil.memPutFloat(p + 24, b);
		MemoryUtil.memPutFloat(p + 28, 0f);
		MemoryUtil.memPutFloat(p + 32, light);
		MemoryUtil.memPutFloat(p + 36, 0f);
		MemoryUtil.memPutFloat(p + 40, 0f);
		MemoryUtil.memPutInt(p + 44, texLayer);
		writePtr = p + ScenePipeline.VERTEX_STRIDE;
	}

	private void writePackedTriangleRgbNoUv(float x0, float y0, float z0,
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
		MemoryUtil.memPutFloat(p, x);
		MemoryUtil.memPutFloat(p + 4, y);
		MemoryUtil.memPutFloat(p + 8, z);
		MemoryUtil.memPutFloat(p + 12, 0f);
		MemoryUtil.memPutFloat(p + 16, r);
		MemoryUtil.memPutFloat(p + 20, g);
		MemoryUtil.memPutFloat(p + 24, b);
		MemoryUtil.memPutFloat(p + 28, 0f);
		MemoryUtil.memPutFloat(p + 32, light);
		MemoryUtil.memPutFloat(p + 36, 0f);
		MemoryUtil.memPutFloat(p + 40, 0f);
		MemoryUtil.memPutInt(p + 44, texLayer);
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
