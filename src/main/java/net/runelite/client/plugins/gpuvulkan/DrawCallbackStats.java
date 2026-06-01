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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Model;

/**
 * Flight recorder for the Vulkan draw path. The counters are deliberately
 * cheap and opt-in so renderer changes can explain which contract surface
 * changed: callbacks, CPU timings, draw submission shape, uploads, readbacks,
 * and model emission.
 *
 * <p>All counters are atomic because the OSRS scene renderer can spin up
 * {@code DrawCallbacks.RENDER_THREADS_MASK} threads.
 */
@Slf4j
final class DrawCallbackStats
{
	private final boolean statsEnabled = Boolean.parseBoolean(System.getProperty("vkgpu.stats", "false"));
	private volatile boolean detailedModelStats;
	private volatile boolean overlayStatsEnabled;

	// Per-method call counters
	final Counter drawScene = new Counter();
	final Counter preSceneDraw = new Counter();
	final Counter postDrawScene = new Counter();
	volatile int lastHideRoofSize;
	final Counter swapScene = new Counter();
	final Counter loadScene = new Counter();
	final Counter drawScenePaint = new Counter();
	final Counter drawSceneTileModel = new Counter();
	final Counter drawZoneOpaque = new Counter();
	final Counter drawZoneAlpha = new Counter();
	final Counter drawDynamic = new Counter();
	final Counter drawTemp = new Counter();
	final Counter drawPass = new Counter();
	final Counter drawSingle = new Counter();   // draw(Projection, Scene, Renderable, ...)
	final Counter animate = new Counter();

	// Aggregate model stats (for the methods carrying a Model)
	final Counter totalDynamicVerts = new Counter();
	final Counter totalDynamicFaces = new Counter();
	final AtomicInteger maxDynamicFaces = new AtomicInteger();

	// CPU timing buckets. Aggregated once per stats log interval.
	final Counter frames = new Counter();
	final Counter drawFrameNanos = new Counter();
	final Counter fenceWaitNanos = new Counter();
	final Counter uiUploadNanos = new Counter();
	final Counter acquireNanos = new Counter();
	final Counter commandRecordNanos = new Counter();
	final Counter beforeRenderPassNanos = new Counter();
	final Counter renderPassNanos = new Counter();
	final Counter submitNanos = new Counter();
	final Counter presentNanos = new Counter();
	final Counter customDrawableNanos = new Counter();
	final Counter beginFrameNanos = new Counter();
	final Counter pendingCaptureNanos = new Counter();
	final Counter sceneCaptureNanos = new Counter();
	final Counter modelSortNanos = new Counter();
	final Counter modelFullSortNanos = new Counter();
	final Counter modelCullOnlyNanos = new Counter();
	final Counter modelEmitNanos = new Counter();
	final Counter modelSortedEmitNanos = new Counter();
	final Counter modelUnsortedEmitNanos = new Counter();
	final Counter modelUvNanos = new Counter();
	final Counter sortedModels = new Counter();
	final Counter fullSortModels = new Counter();
	final Counter cullOnlyModels = new Counter();
	final Counter unsortedModels = new Counter();
	final Counter sortFallbackModels = new Counter();
	final Counter sortedFaces = new Counter();
	final Counter unsortedFaces = new Counter();
	final Counter fullSortTransparentFaces = new Counter();
	final Counter texturedEmitFaces = new Counter();
	final Counter overrideEmitFaces = new Counter();
	final Counter sceneDrawCalls = new Counter();
	final Counter sceneDrawVertices = new Counter();
	final Counter scenePushConstants = new Counter();
	final Counter roofSkipPairs = new Counter();
	final Counter overlayDirtyZones = new Counter();
	final Counter uiUploadBytes = new Counter();
	final Counter screenshotReadbackBytes = new Counter();

	// Sample fields — last-write-wins, fine for "what's the camera at"
	volatile double lastCamX;
	volatile double lastCamY;
	volatile double lastCamZ;
	volatile int lastCamPlane;

	private long nextLogNanos = System.nanoTime() + 1_000_000_000L;

	boolean isEnabled()
	{
		return statsEnabled || detailedModelStats || overlayStatsEnabled;
	}

	boolean isDetailedModelStats()
	{
		return detailedModelStats;
	}

	void setDetailedModelStats(boolean enabled)
	{
		if (detailedModelStats == enabled)
		{
			return;
		}
		detailedModelStats = enabled;
		if (!enabled)
		{
			resetDetailedModelStats();
		}
	}

	void setOverlayStatsEnabled(boolean enabled)
	{
		if (overlayStatsEnabled == enabled)
		{
			return;
		}
		overlayStatsEnabled = enabled;
	}

	void recordModel(Model m)
	{
		if (!detailedModelStats || m == null) return;
		int verts = m.getVerticesCount();
		int[] fa = m.getFaceIndices1();
		int faces = fa == null ? 0 : fa.length;
		totalDynamicVerts.addAndGet(verts);
		totalDynamicFaces.addAndGet(faces);
		// CAS loop to maintain the running max without locks.
		int prev;
		do { prev = maxDynamicFaces.get(); } while (faces > prev && !maxDynamicFaces.compareAndSet(prev, faces));
	}

	void addNanos(Counter bucket, long startNanos)
	{
		if (startNanos == 0L)
		{
			return;
		}
		bucket.addAndGet(System.nanoTime() - startNanos);
	}

	long startNanos()
	{
		return isEnabled() ? System.nanoTime() : 0L;
	}

	private static double avgMs(long nanos, long count)
	{
		return count == 0 ? 0.0 : (nanos / 1_000_000.0) / count;
	}

	private static double totalMs(long nanos)
	{
		return nanos / 1_000_000.0;
	}

	private static double maxMs(long nanos)
	{
		return nanos / 1_000_000.0;
	}

	void maybeLog()
	{
		if (!isEnabled())
		{
			return;
		}
		long now = System.nanoTime();
		if (now < nextLogNanos) return;
		nextLogNanos = now + 1_000_000_000L;

		long frameCount = frames.getAndSet(0);
		long drawFrame = drawFrameNanos.getAndSet(0);
		long maxDrawFrame = drawFrameNanos.getMaxAndReset();
		long fenceWait = fenceWaitNanos.getAndSet(0);
		long maxFenceWait = fenceWaitNanos.getMaxAndReset();
		long uiUpload = uiUploadNanos.getAndSet(0);
		long maxUiUpload = uiUploadNanos.getMaxAndReset();
		long acquire = acquireNanos.getAndSet(0);
		long maxAcquire = acquireNanos.getMaxAndReset();
		long commandRecord = commandRecordNanos.getAndSet(0);
		long maxCommandRecord = commandRecordNanos.getMaxAndReset();
		long beforePass = beforeRenderPassNanos.getAndSet(0);
		long maxBeforePass = beforeRenderPassNanos.getMaxAndReset();
		long renderPass = renderPassNanos.getAndSet(0);
		long maxRenderPass = renderPassNanos.getMaxAndReset();
		long submit = submitNanos.getAndSet(0);
		long maxSubmit = submitNanos.getMaxAndReset();
		long present = presentNanos.getAndSet(0);
		long maxPresent = presentNanos.getMaxAndReset();
		long customDrawable = customDrawableNanos.getAndSet(0);
		long maxCustomDrawable = customDrawableNanos.getMaxAndReset();
		long drawSceneCount = drawScene.getAndSet(0);
		long beginFrame = beginFrameNanos.getAndSet(0);
		long maxBeginFrame = beginFrameNanos.getMaxAndReset();
		long pendingCapture = pendingCaptureNanos.getAndSet(0);
		long maxPendingCapture = pendingCaptureNanos.getMaxAndReset();
		long sceneCapture = sceneCaptureNanos.getAndSet(0);
		long maxSceneCapture = sceneCaptureNanos.getMaxAndReset();
		long sceneDrawCallCount = sceneDrawCalls.getAndSet(0);
		long sceneDrawVertexCount = sceneDrawVertices.getAndSet(0);
		long scenePushConstantCount = scenePushConstants.getAndSet(0);
		long roofSkipPairCount = roofSkipPairs.getAndSet(0);
		long overlayDirtyZoneCount = overlayDirtyZones.getAndSet(0);
		long uiBytes = uiUploadBytes.getAndSet(0);
		long screenshotBytes = screenshotReadbackBytes.getAndSet(0);
		if (!detailedModelStats)
		{
			log.info(String.format(
				"recon | scene=%d preSD=%d postSD=%d swap=%d load=%d | paint=%d tileModel=%d | zoneOpq=%d zoneAlpha=%d | dyn=%d temp=%d pass=%d single=%d | cam=(%.1f, %.1f, %.1f) plane=%d | anim=%d | submit: draws=%d drawVerts=%d pushes=%d roofSkips=%d overlayZones=%d uiBytes=%d readbackBytes=%d | cpu/frame avg ms: draw=%.2f fence=%.2f ui=%.2f acquire=%.2f record=%.2f beforePass=%.2f pass=%.2f submit=%.2f present=%.2f drawable=%.2f | cpu max ms: draw=%.2f fence=%.2f ui=%.2f acquire=%.2f record=%.2f beforePass=%.2f pass=%.2f submit=%.2f present=%.2f drawable=%.2f | scene avg/max ms: begin=%.2f/%.2f pending=%.2f/%.2f staticCaptureTotal=%.2f max=%.2f",
				drawSceneCount,
				preSceneDraw.getAndSet(0),
				postDrawScene.getAndSet(0),
				swapScene.getAndSet(0),
				loadScene.getAndSet(0),
				drawScenePaint.getAndSet(0),
				drawSceneTileModel.getAndSet(0),
				drawZoneOpaque.getAndSet(0),
				drawZoneAlpha.getAndSet(0),
				drawDynamic.getAndSet(0),
				drawTemp.getAndSet(0),
				drawPass.getAndSet(0),
				drawSingle.getAndSet(0),
				lastCamX, lastCamY, lastCamZ, lastCamPlane,
				animate.getAndSet(0),
				sceneDrawCallCount,
				sceneDrawVertexCount,
				scenePushConstantCount,
				roofSkipPairCount,
				overlayDirtyZoneCount,
				uiBytes,
				screenshotBytes,
				avgMs(drawFrame, frameCount),
				avgMs(fenceWait, frameCount),
				avgMs(uiUpload, frameCount),
				avgMs(acquire, frameCount),
				avgMs(commandRecord, frameCount),
				avgMs(beforePass, frameCount),
				avgMs(renderPass, frameCount),
				avgMs(submit, frameCount),
				avgMs(present, frameCount),
				avgMs(customDrawable, frameCount),
				maxMs(maxDrawFrame),
				maxMs(maxFenceWait),
				maxMs(maxUiUpload),
				maxMs(maxAcquire),
				maxMs(maxCommandRecord),
				maxMs(maxBeforePass),
				maxMs(maxRenderPass),
				maxMs(maxSubmit),
				maxMs(maxPresent),
				maxMs(maxCustomDrawable),
				avgMs(beginFrame, drawSceneCount),
				maxMs(maxBeginFrame),
				avgMs(pendingCapture, drawSceneCount),
				maxMs(maxPendingCapture),
				totalMs(sceneCapture),
				maxMs(maxSceneCapture)
			));
			return;
		}

		long sort = modelSortNanos.getAndSet(0);
		long fullSort = modelFullSortNanos.getAndSet(0);
		long cullOnly = modelCullOnlyNanos.getAndSet(0);
		long emit = modelEmitNanos.getAndSet(0);
		long sortedEmit = modelSortedEmitNanos.getAndSet(0);
		long unsortedEmit = modelUnsortedEmitNanos.getAndSet(0);
		long uv = modelUvNanos.getAndSet(0);
		long sortedModelCount = sortedModels.getAndSet(0);
		long fullSortModelCount = fullSortModels.getAndSet(0);
		long cullOnlyModelCount = cullOnlyModels.getAndSet(0);
		long unsortedModelCount = unsortedModels.getAndSet(0);
		long fallbackModelCount = sortFallbackModels.getAndSet(0);
		long sortedFaceCount = sortedFaces.getAndSet(0);
		long unsortedFaceCount = unsortedFaces.getAndSet(0);
		long transparentFaceCount = fullSortTransparentFaces.getAndSet(0);
		long texturedFaceCount = texturedEmitFaces.getAndSet(0);
		long overrideFaceCount = overrideEmitFaces.getAndSet(0);

			log.info(String.format(
				"recon | scene=%d preSD=%d postSD=%d swap=%d load=%d | paint=%d tileModel=%d | zoneOpq=%d zoneAlpha=%d | dyn=%d temp=%d pass=%d single=%d | dynVerts=%d dynFaces=%d maxF=%d | cam=(%.1f, %.1f, %.1f) plane=%d | anim=%d | submit: draws=%d drawVerts=%d pushes=%d roofSkips=%d overlayZones=%d uiBytes=%d readbackBytes=%d | cpu/frame avg ms: draw=%.2f fence=%.2f ui=%.2f acquire=%.2f record=%.2f beforePass=%.2f pass=%.2f submit=%.2f present=%.2f drawable=%.2f | cpu max ms: draw=%.2f fence=%.2f ui=%.2f acquire=%.2f record=%.2f beforePass=%.2f pass=%.2f submit=%.2f present=%.2f drawable=%.2f | scene avg/max ms: begin=%.2f/%.2f pending=%.2f/%.2f staticCaptureTotal=%.2f max=%.2f | model ms: sort=%.2f full=%.2f cull=%.2f emit=%.2f sortedEmit=%.2f unsortedEmit=%.2f uv=%.2f | models sorted=%d full=%d cull=%d unsorted=%d fallback=%d | faces sorted=%d unsorted=%d tex=%d override=%d fullTrans=%d",
			drawSceneCount,
			preSceneDraw.getAndSet(0),
			postDrawScene.getAndSet(0),
			swapScene.getAndSet(0),
			loadScene.getAndSet(0),
			drawScenePaint.getAndSet(0),
			drawSceneTileModel.getAndSet(0),
			drawZoneOpaque.getAndSet(0),
			drawZoneAlpha.getAndSet(0),
			drawDynamic.getAndSet(0),
			drawTemp.getAndSet(0),
			drawPass.getAndSet(0),
			drawSingle.getAndSet(0),
			totalDynamicVerts.getAndSet(0),
			totalDynamicFaces.getAndSet(0),
			maxDynamicFaces.getAndSet(0),
			lastCamX, lastCamY, lastCamZ, lastCamPlane,
			animate.getAndSet(0),
			sceneDrawCallCount,
			sceneDrawVertexCount,
			scenePushConstantCount,
			roofSkipPairCount,
			overlayDirtyZoneCount,
			uiBytes,
			screenshotBytes,
			avgMs(drawFrame, frameCount),
			avgMs(fenceWait, frameCount),
			avgMs(uiUpload, frameCount),
			avgMs(acquire, frameCount),
			avgMs(commandRecord, frameCount),
			avgMs(beforePass, frameCount),
			avgMs(renderPass, frameCount),
			avgMs(submit, frameCount),
				avgMs(present, frameCount),
				avgMs(customDrawable, frameCount),
				maxMs(maxDrawFrame),
				maxMs(maxFenceWait),
				maxMs(maxUiUpload),
				maxMs(maxAcquire),
				maxMs(maxCommandRecord),
				maxMs(maxBeforePass),
				maxMs(maxRenderPass),
				maxMs(maxSubmit),
				maxMs(maxPresent),
				maxMs(maxCustomDrawable),
				avgMs(beginFrame, drawSceneCount),
				maxMs(maxBeginFrame),
				avgMs(pendingCapture, drawSceneCount),
				maxMs(maxPendingCapture),
				totalMs(sceneCapture),
				maxMs(maxSceneCapture),
			totalMs(sort),
			totalMs(fullSort),
			totalMs(cullOnly),
			totalMs(emit),
			totalMs(sortedEmit),
			totalMs(unsortedEmit),
			totalMs(uv),
			sortedModelCount,
			fullSortModelCount,
			cullOnlyModelCount,
			unsortedModelCount,
			fallbackModelCount,
			sortedFaceCount,
			unsortedFaceCount,
			texturedFaceCount,
			overrideFaceCount,
			transparentFaceCount
		));
	}

	private void resetDetailedModelStats()
	{
		totalDynamicVerts.set(0);
		totalDynamicFaces.set(0);
		maxDynamicFaces.set(0);
		modelSortNanos.set(0);
		modelFullSortNanos.set(0);
		modelCullOnlyNanos.set(0);
		modelEmitNanos.set(0);
		modelSortedEmitNanos.set(0);
		modelUnsortedEmitNanos.set(0);
		modelUvNanos.set(0);
		sortedModels.set(0);
		fullSortModels.set(0);
		cullOnlyModels.set(0);
		unsortedModels.set(0);
		sortFallbackModels.set(0);
		sortedFaces.set(0);
		unsortedFaces.set(0);
		fullSortTransparentFaces.set(0);
		texturedEmitFaces.set(0);
		overrideEmitFaces.set(0);
	}

	long drawSceneCount() { return drawScene.get(); }
	long preSceneDrawCount() { return preSceneDraw.get(); }
	long postDrawSceneCount() { return postDrawScene.get(); }
	long drawDynamicCount() { return drawDynamic.get(); }
	long drawTempCount() { return drawTemp.get(); }
	long drawPassCount() { return drawPass.get(); }
	long drawSingleCount() { return drawSingle.get(); }
	long totalDynamicVertsCount() { return totalDynamicVerts.get(); }
	long totalDynamicFacesCount() { return totalDynamicFaces.get(); }
	int maxDynamicFacesCount() { return maxDynamicFaces.get(); }

	final class Counter
	{
		private final LongAdder value = new LongAdder();
		private final AtomicLong max = new AtomicLong();

		void incrementAndGet()
		{
			if (isEnabled())
			{
				value.increment();
			}
		}

		void addAndGet(long delta)
		{
			if (isEnabled())
			{
				value.add(delta);
				updateMax(delta);
			}
		}

		long getAndSet(long next)
		{
			long current = value.sumThenReset();
			if (next != 0L && isEnabled())
			{
				value.add(next);
			}
			return current;
		}

		void set(long next)
		{
			value.reset();
			if (next != 0L && isEnabled())
			{
				value.add(next);
			}
		}

		long get()
		{
			return value.sum();
		}

		long getMaxAndReset()
		{
			return max.getAndSet(0L);
		}

		private void updateMax(long value)
		{
			long prev;
			do
			{
				prev = max.get();
				if (value <= prev)
				{
					return;
				}
			}
			while (!max.compareAndSet(prev, value));
		}
	}
}
