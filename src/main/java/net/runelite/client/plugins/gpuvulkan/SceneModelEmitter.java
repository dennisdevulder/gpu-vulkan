/*
 * Dynamic model emission for SceneRenderer. Scene capture and draw scheduling
 * stay in SceneRenderer; this class owns model sort/cull/cache scratch.
 */
package net.runelite.client.plugins.gpuvulkan;

import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;

final class SceneModelEmitter
{
	private static final int OPAQUE_UNSORTED_FACE_THRESHOLD =
		intProperty("vkgpu.opaqueUnsortedFaceThreshold", 64, 0, 256);
	private static final float[] HSL_RGB = HslColor.RGB_TABLE;

	private final DrawCallbackStats stats;
	private final PriorityRangeSet priorityRanges;
	private final VertexSink sink;
	private final ModelFaceCache modelFaceCache = new ModelFaceCache();
	private final ModelSorter sorter = new ModelSorter();
	private final float[] cullLocalX = new float[ModelSorter.MAX_VERTEX_COUNT];
	private final float[] cullLocalY = new float[ModelSorter.MAX_VERTEX_COUNT];
	private final float[] cullLocalZ = new float[ModelSorter.MAX_VERTEX_COUNT];
	private final float[] cullProjX = new float[ModelSorter.MAX_VERTEX_COUNT];
	private final float[] cullProjY = new float[ModelSorter.MAX_VERTEX_COUNT];
	private final float[] cullProjectScratch = new float[3];
	private final float[] uvScratch = new float[6];

	SceneModelEmitter(DrawCallbackStats stats, PriorityRangeSet priorityRanges, VertexSink sink)
	{
		this.stats = stats;
		this.priorityRanges = priorityRanges;
		this.sink = sink;
	}

	/** Face filters for the static capture sweeps. The blended alpha pass
	 *  draws a dedicated STATIC_ALPHA region instead of replaying the whole
	 *  scene, so statics are captured twice: once keeping only opaque faces,
	 *  once keeping only translucent ones. Dynamics always use ALL. */
	static final int FILTER_ALL = 0;
	static final int FILTER_OPAQUE = 1;
	static final int FILTER_ALPHA = 2;
	private int staticFaceFilter = FILTER_ALL;

	void setStaticFaceFilter(int filter)
	{
		staticFaceFilter = filter;
	}

	void clearCache()
	{
		modelFaceCache.clear();
	}

	void collectDebugMetrics(GpuVulkanDebugMetrics metrics)
	{
		modelFaceCache.collectDebugMetrics(metrics);
	}

	void captureModel(Model m, int orient, int worldX, int worldY, int worldZ)
	{
		if (m == null) return;
		captureModelUnsorted(m, orient, worldX, worldY, worldZ);
	}

	/** Per-model array snapshot, reused across captures (no allocation). */
	private static final class ModelArrays
	{
		float[] vx, vy, vz;
		int[] fa, fb, fc;
		int[] c1, c2, c3;
		short[] faceTextures;
		byte[] textureFaces;
		int[] texA, texB, texC;
		byte[] trans, bias;
		byte overrideHue, overrideSat, overrideLum, overrideAmount;
		boolean hasOverride;

		boolean load(Model m)
		{
			vx = m.getVerticesX();
			vy = m.getVerticesY();
			vz = m.getVerticesZ();
			fa = m.getFaceIndices1();
			fb = m.getFaceIndices2();
			fc = m.getFaceIndices3();
			c1 = m.getFaceColors1();
			c2 = m.getFaceColors2();
			c3 = m.getFaceColors3();
			faceTextures = m.getFaceTextures();
			textureFaces = m.getTextureFaces();
			texA = m.getTexIndices1();
			texB = m.getTexIndices2();
			texC = m.getTexIndices3();
			trans = m.getFaceTransparencies();
			bias = m.getFaceBias();
			overrideAmount = m.getOverrideAmount();
			overrideHue = m.getOverrideHue();
			overrideSat = m.getOverrideSaturation();
			overrideLum = m.getOverrideLuminance();
			hasOverride = (overrideAmount & 0xFF) != 0;
			return vx != null && vy != null && vz != null && fa != null && fb != null && fc != null;
		}
	}

	private final ModelArrays arrays = new ModelArrays();

	// Per-model counters written by the emit loops, read by the callers'
	// stats tails. Single-threaded scratch.
	private int emitWrote;
	private int emitTexturedFaces;
	private int emitOverrideFaces;
	private long emitUvNanos;

	private void captureModelUnsorted(Model m, int orient, int worldX, int worldY, int worldZ)
	{
		boolean detailedStats = stats.isDetailedModelStats();
		long emitStart = detailedStats ? System.nanoTime() : 0L;
		ModelArrays a = arrays;
		if (!a.load(m)) return;

		float cos = Perspective.COSINE[orient & 0x7FF] / 65536f;
		float sin = Perspective.SINE[orient & 0x7FF] / 65536f;

		// LANDMINE: Mesh.getFaceCount(), NOT fa.length. Engine over-allocates
		// face index arrays for assembled actor models (player composition,
		// reused NPC bodies); fa.length reads stale trailing data.
		int faces = m.getFaceCount();
		if (!sink.reserveVertices(faces * 3)) return;

		emitUnsortedFaces(a, faces, cos, sin, worldX, worldY, worldZ, detailedStats);

		sink.addVertices(emitWrote);
		if (detailedStats)
		{
			stats.unsortedModels.incrementAndGet();
			stats.unsortedFaces.addAndGet(emitWrote / 3);
			long emitNanos = System.nanoTime() - emitStart;
			stats.modelEmitNanos.addAndGet(emitNanos);
			stats.modelUnsortedEmitNanos.addAndGet(emitNanos);
			stats.modelUvNanos.addAndGet(emitUvNanos);
			stats.texturedEmitFaces.addAndGet(emitTexturedFaces);
			stats.overrideEmitFaces.addAndGet(emitOverrideFaces);
		}
	}

	// Single atomic per-face emit sequence (flat/gouraud × uv/no-uv);
	// splitting it adds per-face dispatch to the hottest loop in the plugin.
	private void emitUnsortedFaces(ModelArrays a, int faces,
		float cos, float sin, int worldX, int worldY, int worldZ, boolean detailedStats)
	{
		float[] vx = a.vx, vy = a.vy, vz = a.vz;
		int[] fa = a.fa, fb = a.fb, fc = a.fc;
		int[] c1 = a.c1, c2 = a.c2, c3 = a.c3;
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

			int texLayer = 0;
			float u0 = 0, v0 = 0, u1 = 0, v1 = 0, u2 = 0, v2 = 0;
			if (a.faceTextures != null && a.faceTextures[f] != -1)
			{
				long uvStart = detailedStats ? System.nanoTime() : 0L;
				texLayer = (a.faceTextures[f] & 0xFFFF) + 1;
				ModelUvMapper.computeFaceUvs(uv, vx, vy, vz, fa[f], fb[f], fc[f],
					a.textureFaces, a.texA, a.texB, a.texC, f);
				if (detailedStats)
				{
					uvNanos += System.nanoTime() - uvStart;
				}
				texturedFaces++;
				u0 = uv[0]; v0 = uv[1];
				u1 = uv[2]; v1 = uv[3];
				u2 = uv[4]; v2 = uv[5];
			}

			if (a.hasOverride && texLayer == 0)
			{
				overrideFaces++;
				col1 = HslColor.applyOverride(col1, a.overrideHue, a.overrideSat, a.overrideLum, a.overrideAmount);
				col2 = HslColor.applyOverride(col2, a.overrideHue, a.overrideSat, a.overrideLum, a.overrideAmount);
				col3 = HslColor.applyOverride(col3, a.overrideHue, a.overrideSat, a.overrideLum, a.overrideAmount);
			}

			int bias = a.bias != null ? (a.bias[f] & 0xFF) : 0;
			int trans = a.trans != null ? (a.trans[f] & 0xFF) : 0;
			// Static sweeps split faces by transparency; 255 is the engine
			// invisibility sentinel and is dropped from static capture.
			if (staticFaceFilter == FILTER_OPAQUE && trans != 0) continue;
			if (staticFaceFilter == FILTER_ALPHA && (trans == 0 || trans == 255)) continue;
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
					sink.writeRotatedVertexRgbNoUv(vx[ia], vy[ia], vz[ia], cos, sin, worldX, worldY, worldZ, light, r, g, b, packedTexLayer);
					sink.writeRotatedVertexRgbNoUv(vx[ib], vy[ib], vz[ib], cos, sin, worldX, worldY, worldZ, light, r, g, b, packedTexLayer);
					sink.writeRotatedVertexRgbNoUv(vx[ic], vy[ic], vz[ic], cos, sin, worldX, worldY, worldZ, light, r, g, b, packedTexLayer);
				}
				else
				{
					sink.writeRotatedVertexRgb(vx[ia], vy[ia], vz[ia], cos, sin, worldX, worldY, worldZ, light, r, g, b, u0, v0, packedTexLayer);
					sink.writeRotatedVertexRgb(vx[ib], vy[ib], vz[ib], cos, sin, worldX, worldY, worldZ, light, r, g, b, u1, v1, packedTexLayer);
					sink.writeRotatedVertexRgb(vx[ic], vy[ic], vz[ic], cos, sin, worldX, worldY, worldZ, light, r, g, b, u2, v2, packedTexLayer);
				}
			}
			else
			{
				int rgbOffset1 = (col1 & 0xFFFF) * 3;
				int rgbOffset2 = (col2 & 0xFFFF) * 3;
				int rgbOffset3 = (col3 & 0xFFFF) * 3;
				if (noUv)
				{
					sink.writeRotatedVertexRgbNoUv(vx[ia], vy[ia], vz[ia], cos, sin, worldX, worldY, worldZ,
						(float) (col1 & 0xFFFF), HSL_RGB[rgbOffset1], HSL_RGB[rgbOffset1 + 1], HSL_RGB[rgbOffset1 + 2], packedTexLayer);
					sink.writeRotatedVertexRgbNoUv(vx[ib], vy[ib], vz[ib], cos, sin, worldX, worldY, worldZ,
						(float) (col2 & 0xFFFF), HSL_RGB[rgbOffset2], HSL_RGB[rgbOffset2 + 1], HSL_RGB[rgbOffset2 + 2], packedTexLayer);
					sink.writeRotatedVertexRgbNoUv(vx[ic], vy[ic], vz[ic], cos, sin, worldX, worldY, worldZ,
						(float) (col3 & 0xFFFF), HSL_RGB[rgbOffset3], HSL_RGB[rgbOffset3 + 1], HSL_RGB[rgbOffset3 + 2], packedTexLayer);
				}
				else
				{
					sink.writeRotatedVertexRgb(vx[ia], vy[ia], vz[ia], cos, sin, worldX, worldY, worldZ,
						(float) (col1 & 0xFFFF), HSL_RGB[rgbOffset1], HSL_RGB[rgbOffset1 + 1], HSL_RGB[rgbOffset1 + 2], u0, v0, packedTexLayer);
					sink.writeRotatedVertexRgb(vx[ib], vy[ib], vz[ib], cos, sin, worldX, worldY, worldZ,
						(float) (col2 & 0xFFFF), HSL_RGB[rgbOffset2], HSL_RGB[rgbOffset2 + 1], HSL_RGB[rgbOffset2 + 2], u1, v1, packedTexLayer);
					sink.writeRotatedVertexRgb(vx[ic], vy[ic], vz[ic], cos, sin, worldX, worldY, worldZ,
						(float) (col3 & 0xFFFF), HSL_RGB[rgbOffset3], HSL_RGB[rgbOffset3 + 1], HSL_RGB[rgbOffset3 + 2], u2, v2, packedTexLayer);
				}
			}
			wrote += 3;
		}
		emitWrote = wrote;
		emitTexturedFaces = texturedFaces;
		emitOverrideFaces = overrideFaces;
		emitUvNanos = uvNanos;
	}

	void captureModelSorted(Projection proj, Model m, int orient, int worldX, int worldY, int worldZ)
	{
		captureModelSorted(proj, m, orient, worldX, worldY, worldZ, Renderable.RENDERMODE_DEFAULT);
	}

	void captureModelSorted(Projection proj, Model m, int orient, int worldX, int worldY, int worldZ, int renderMode)
	{
		captureModelSorted(proj, m, orient, worldX, worldY, worldZ, renderMode, false);
	}

	void captureModelSorted(Projection proj, Model m, int orient, int worldX, int worldY, int worldZ, int renderMode,
		boolean actorModel)
	{
		if (m == null || proj == null) return;

		boolean detailedStats = stats.isDetailedModelStats();
		boolean prioritySort = renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH;
		int priorityStart = prioritySort ? sink.currentVertexCount() : -1;
		int faceCount = m.getFaceCount();
		byte[] faceTransparencies = m.getFaceTransparencies();
		if (!prioritySort && faceCount <= OPAQUE_UNSORTED_FACE_THRESHOLD
			&& ModelFaceCache.countTransparentFaces(faceCount, faceTransparencies) == 0)
		{
			captureModelUnsorted(m, orient, worldX, worldY, worldZ);
			return;
		}
		ModelFaceCache.Entry modelInfo = modelFaceCache.info(faceCount, faceTransparencies);
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
				fallBackToUnsorted(m, orient, worldX, worldY, worldZ, detailedStats);
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
		if (!sorted || sorter.sortedCount == 0)
		{
			fallBackToUnsorted(m, orient, worldX, worldY, worldZ, detailedStats);
			recordPriorityRange(priorityStart);
			return;
		}

		emitSortedModel(m, modelInfo, detailedStats);
		recordPriorityRange(priorityStart);
	}

	// Sort rejects must stay visible (projectiles, spotanims) — emit unsorted.
	private void fallBackToUnsorted(Model m, int orient, int worldX, int worldY, int worldZ, boolean detailedStats)
	{
		if (detailedStats)
		{
			stats.sortFallbackModels.incrementAndGet();
		}
		captureModelUnsorted(m, orient, worldX, worldY, worldZ);
	}

	private void emitSortedModel(Model m, ModelFaceCache.Entry modelInfo, boolean detailedStats)
	{
		int faces = sorter.sortedCount;
		if (!sink.reserveVertices(faces * 3)) return;
		ModelArrays a = arrays;
		if (!a.load(m)) return;

		long emitStart = detailedStats ? System.nanoTime() : 0L;
		emitSortedFaces(a, faces, detailedStats);

		sink.addVertices(emitWrote);
		if (detailedStats)
		{
			stats.sortedModels.incrementAndGet();
			stats.fullSortModels.incrementAndGet();
			stats.fullSortTransparentFaces.addAndGet(modelInfo.transparentFaces);
			stats.sortedFaces.addAndGet(emitWrote / 3);
			long emitNanos = System.nanoTime() - emitStart;
			stats.modelEmitNanos.addAndGet(emitNanos);
			stats.modelSortedEmitNanos.addAndGet(emitNanos);
			stats.modelUvNanos.addAndGet(emitUvNanos);
			stats.texturedEmitFaces.addAndGet(emitTexturedFaces);
			stats.overrideEmitFaces.addAndGet(emitOverrideFaces);
		}
	}

	// Single atomic per-face emit sequence over the sorter's back-to-front
	// order and pre-transformed local coords; see emitUnsortedFaces.
	private void emitSortedFaces(ModelArrays a, int faces, boolean detailedStats)
	{
		float[] vxs = a.vx, vys = a.vy, vzs = a.vz;
		int[] fa = a.fa, fb = a.fb, fc = a.fc;
		int[] c1 = a.c1, c2 = a.c2, c3 = a.c3;
		float[] uv = uvScratch;
		float[] lx = sorter.localX;
		float[] ly = sorter.localY;
		float[] lz = sorter.localZ;

		int wrote = 0;
		int texturedFaces = 0;
		int overrideFaces = 0;
		long uvNanos = 0;
		for (int i = 0; i < faces; i++)
		{
			int f = sorter.sortedFaces[i];

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
			if (a.faceTextures != null && a.faceTextures[f] != -1)
			{
				long uvStart = detailedStats ? System.nanoTime() : 0L;
				texLayer = (a.faceTextures[f] & 0xFFFF) + 1;
				ModelUvMapper.computeFaceUvs(uv, vxs, vys, vzs, fa[f], fb[f], fc[f],
					a.textureFaces, a.texA, a.texB, a.texC, f);
				if (detailedStats)
				{
					uvNanos += System.nanoTime() - uvStart;
				}
				texturedFaces++;
				u0 = uv[0]; v0 = uv[1];
				u1 = uv[2]; v1 = uv[3];
				u2 = uv[4]; v2 = uv[5];
			}

			if (a.hasOverride && texLayer == 0)
			{
				overrideFaces++;
				col1 = HslColor.applyOverride(col1, a.overrideHue, a.overrideSat, a.overrideLum, a.overrideAmount);
				col2 = HslColor.applyOverride(col2, a.overrideHue, a.overrideSat, a.overrideLum, a.overrideAmount);
				col3 = HslColor.applyOverride(col3, a.overrideHue, a.overrideSat, a.overrideLum, a.overrideAmount);
			}

			int bias = a.bias != null ? (a.bias[f] & 0xFF) : 0;
			int trans = a.trans != null ? (a.trans[f] & 0xFF) : 0;
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
					sink.writePackedTriangleRgbNoUv(
						lx[ia], ly[ia], lz[ia],
						lx[ib], ly[ib], lz[ib],
						lx[ic], ly[ic], lz[ic],
						light, r, g, b, packedTexLayer);
				}
				else
				{
					sink.writePackedVertexRgb(lx[ia], ly[ia], lz[ia], light, r, g, b, u0, v0, packedTexLayer);
					sink.writePackedVertexRgb(lx[ib], ly[ib], lz[ib], light, r, g, b, u1, v1, packedTexLayer);
					sink.writePackedVertexRgb(lx[ic], ly[ic], lz[ic], light, r, g, b, u2, v2, packedTexLayer);
				}
			}
			else
			{
				int rgbOffset1 = (col1 & 0xFFFF) * 3;
				int rgbOffset2 = (col2 & 0xFFFF) * 3;
				int rgbOffset3 = (col3 & 0xFFFF) * 3;
				if (noUv)
				{
					sink.writePackedVertexRgbNoUv(lx[ia], ly[ia], lz[ia],
						(float) (col1 & 0xFFFF), HSL_RGB[rgbOffset1], HSL_RGB[rgbOffset1 + 1], HSL_RGB[rgbOffset1 + 2], packedTexLayer);
					sink.writePackedVertexRgbNoUv(lx[ib], ly[ib], lz[ib],
						(float) (col2 & 0xFFFF), HSL_RGB[rgbOffset2], HSL_RGB[rgbOffset2 + 1], HSL_RGB[rgbOffset2 + 2], packedTexLayer);
					sink.writePackedVertexRgbNoUv(lx[ic], ly[ic], lz[ic],
						(float) (col3 & 0xFFFF), HSL_RGB[rgbOffset3], HSL_RGB[rgbOffset3 + 1], HSL_RGB[rgbOffset3 + 2], packedTexLayer);
				}
				else
				{
					sink.writePackedVertexRgb(lx[ia], ly[ia], lz[ia],
						(float) (col1 & 0xFFFF), HSL_RGB[rgbOffset1], HSL_RGB[rgbOffset1 + 1], HSL_RGB[rgbOffset1 + 2], u0, v0, packedTexLayer);
					sink.writePackedVertexRgb(lx[ib], ly[ib], lz[ib],
						(float) (col2 & 0xFFFF), HSL_RGB[rgbOffset2], HSL_RGB[rgbOffset2 + 1], HSL_RGB[rgbOffset2 + 2], u1, v1, packedTexLayer);
					sink.writePackedVertexRgb(lx[ic], ly[ic], lz[ic],
						(float) (col3 & 0xFFFF), HSL_RGB[rgbOffset3], HSL_RGB[rgbOffset3 + 1], HSL_RGB[rgbOffset3 + 2], u2, v2, packedTexLayer);
				}
			}
			wrote += 3;
		}
		emitWrote = wrote;
		emitTexturedFaces = texturedFaces;
		emitOverrideFaces = overrideFaces;
		emitUvNanos = uvNanos;
	}

	private void recordPriorityRange(int start)
	{
		priorityRanges.record(start, sink.currentVertexCount());
	}

	private boolean captureModelCullOnlyFused(Projection proj, Model m, int orientation, int wx, int wy, int wz)
	{
		boolean detailedStats = stats.isDetailedModelStats();
		final int modelVertexCount = m.getVerticesCount();
		if (modelVertexCount > ModelSorter.MAX_VERTEX_COUNT)
		{
			return false;
		}
		ModelArrays a = arrays;
		if (!a.load(m))
		{
			return false;
		}
		if (!transformVerticesFused(proj, a, modelVertexCount, orientation, wx, wy, wz, detailedStats))
		{
			return false;
		}

		final int faceCount = Math.min(m.getFaceCount(), ModelSorter.MAX_FACE_COUNT);
		long emitStart = detailedStats ? System.nanoTime() : 0L;
		emitCulledFaces(a, faceCount, detailedStats);

		if (emitWrote == 0)
		{
			return true;
		}
		sink.addVertices(emitWrote);
		if (detailedStats)
		{
			stats.sortedModels.incrementAndGet();
			stats.cullOnlyModels.incrementAndGet();
			stats.sortedFaces.addAndGet(emitWrote / 3);
			long emitNanos = System.nanoTime() - emitStart;
			stats.modelEmitNanos.addAndGet(emitNanos);
			stats.modelSortedEmitNanos.addAndGet(emitNanos);
			stats.modelUvNanos.addAndGet(emitUvNanos);
			stats.texturedEmitFaces.addAndGet(emitTexturedFaces);
			stats.overrideEmitFaces.addAndGet(emitOverrideFaces);
		}
		return true;
	}

	// Rotates/translates every vertex into cullLocal* and projects it into
	// cullProj*; false = a vertex landed inside the near plane (z < 50,
	// stock's whole-model reject) and the caller must fall back.
	private boolean transformVerticesFused(Projection proj, ModelArrays a, int modelVertexCount,
		int orientation, int wx, int wy, int wz, boolean detailedStats)
	{
		float[] vxs = a.vx, vys = a.vy, vzs = a.vz;
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
		return true;
	}

	// Single atomic per-face emit sequence with screen-space backface cull;
	// see emitUnsortedFaces.
	private void emitCulledFaces(ModelArrays a, int faceCount, boolean detailedStats)
	{
		float[] vxs = a.vx, vys = a.vy, vzs = a.vz;
		int[] fa = a.fa, fb = a.fb, fc = a.fc;
		int[] c1 = a.c1, c2 = a.c2, c3 = a.c3;
		float[] uv = uvScratch;

		int wrote = 0;
		int texturedFaces = 0;
		int overrideFaces = 0;
		long uvNanos = 0;
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

			if (!sink.canAppendVertices(wrote, 3))
			{
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
			if (a.faceTextures != null && a.faceTextures[f] != -1)
			{
				long uvStart = detailedStats ? System.nanoTime() : 0L;
				texLayer = (a.faceTextures[f] & 0xFFFF) + 1;
				ModelUvMapper.computeFaceUvs(uv, vxs, vys, vzs, ia, ib, ic, a.textureFaces, a.texA, a.texB, a.texC, f);
				if (detailedStats)
				{
					uvNanos += System.nanoTime() - uvStart;
				}
				texturedFaces++;
				u0 = uv[0]; v0 = uv[1];
				u1 = uv[2]; v1 = uv[3];
				u2 = uv[4]; v2 = uv[5];
			}

			if (a.hasOverride && texLayer == 0)
			{
				overrideFaces++;
				col1 = HslColor.applyOverride(col1, a.overrideHue, a.overrideSat, a.overrideLum, a.overrideAmount);
				col2 = HslColor.applyOverride(col2, a.overrideHue, a.overrideSat, a.overrideLum, a.overrideAmount);
				col3 = HslColor.applyOverride(col3, a.overrideHue, a.overrideSat, a.overrideLum, a.overrideAmount);
			}

			int bias = a.bias != null ? (a.bias[f] & 0xFF) : 0;
			int trans = a.trans != null ? (a.trans[f] & 0xFF) : 0;
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
					sink.writePackedTriangleRgbNoUv(
						cullLocalX[ia], cullLocalY[ia], cullLocalZ[ia],
						cullLocalX[ib], cullLocalY[ib], cullLocalZ[ib],
						cullLocalX[ic], cullLocalY[ic], cullLocalZ[ic],
						light, r, g, b, packedTexLayer);
				}
				else
				{
					sink.writePackedVertexRgb(cullLocalX[ia], cullLocalY[ia], cullLocalZ[ia], light, r, g, b, u0, v0, packedTexLayer);
					sink.writePackedVertexRgb(cullLocalX[ib], cullLocalY[ib], cullLocalZ[ib], light, r, g, b, u1, v1, packedTexLayer);
					sink.writePackedVertexRgb(cullLocalX[ic], cullLocalY[ic], cullLocalZ[ic], light, r, g, b, u2, v2, packedTexLayer);
				}
			}
			else
			{
				int rgbOffset1 = (col1 & 0xFFFF) * 3;
				int rgbOffset2 = (col2 & 0xFFFF) * 3;
				int rgbOffset3 = (col3 & 0xFFFF) * 3;
				if (noUv)
				{
					sink.writePackedVertexRgbNoUv(cullLocalX[ia], cullLocalY[ia], cullLocalZ[ia],
						(float) (col1 & 0xFFFF), HSL_RGB[rgbOffset1], HSL_RGB[rgbOffset1 + 1], HSL_RGB[rgbOffset1 + 2], packedTexLayer);
					sink.writePackedVertexRgbNoUv(cullLocalX[ib], cullLocalY[ib], cullLocalZ[ib],
						(float) (col2 & 0xFFFF), HSL_RGB[rgbOffset2], HSL_RGB[rgbOffset2 + 1], HSL_RGB[rgbOffset2 + 2], packedTexLayer);
					sink.writePackedVertexRgbNoUv(cullLocalX[ic], cullLocalY[ic], cullLocalZ[ic],
						(float) (col3 & 0xFFFF), HSL_RGB[rgbOffset3], HSL_RGB[rgbOffset3 + 1], HSL_RGB[rgbOffset3 + 2], packedTexLayer);
				}
				else
				{
					sink.writePackedVertexRgb(cullLocalX[ia], cullLocalY[ia], cullLocalZ[ia],
						(float) (col1 & 0xFFFF), HSL_RGB[rgbOffset1], HSL_RGB[rgbOffset1 + 1], HSL_RGB[rgbOffset1 + 2], u0, v0, packedTexLayer);
					sink.writePackedVertexRgb(cullLocalX[ib], cullLocalY[ib], cullLocalZ[ib],
						(float) (col2 & 0xFFFF), HSL_RGB[rgbOffset2], HSL_RGB[rgbOffset2 + 1], HSL_RGB[rgbOffset2 + 2], u1, v1, packedTexLayer);
					sink.writePackedVertexRgb(cullLocalX[ic], cullLocalY[ic], cullLocalZ[ic],
						(float) (col3 & 0xFFFF), HSL_RGB[rgbOffset3], HSL_RGB[rgbOffset3 + 1], HSL_RGB[rgbOffset3 + 2], u2, v2, packedTexLayer);
				}
			}
			wrote += 3;
		}
		emitWrote = wrote;
		emitTexturedFaces = texturedFaces;
		emitOverrideFaces = overrideFaces;
		emitUvNanos = uvNanos;
	}

	private static int intProperty(String name, int defaultValue, int min, int max)
	{
		int value = Integer.getInteger(name, defaultValue);
		return Math.max(min, Math.min(max, value));
	}

	interface VertexSink
	{
		boolean reserveVertices(int vertices);

		boolean canAppendVertices(int pendingVertices, int vertices);

		int currentVertexCount();

		void addVertices(int vertices);

		void writeRotatedVertexRgb(float lx, float ly, float lz,
								   float cos, float sin,
								   int wx, int wy, int wz,
								   float light, float r, float g, float b,
								   float u, float v, int texLayer);

		void writeRotatedVertexRgbNoUv(float lx, float ly, float lz,
									   float cos, float sin,
									   int wx, int wy, int wz,
									   float light, float r, float g, float b,
									   int texLayer);

		void writePackedVertexRgb(float x, float y, float z,
								  float light, float r, float g, float b,
								  float u, float v, int texLayer);

		void writePackedVertexRgbNoUv(float x, float y, float z,
									  float light, float r, float g, float b,
									  int texLayer);

		void writePackedTriangleRgbNoUv(float x0, float y0, float z0,
										float x1, float y1, float z1,
										float x2, float y2, float z2,
										float light, float r, float g, float b,
										int texLayer);
	}
}
