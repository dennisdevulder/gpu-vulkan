package net.runelite.client.plugins.gpuvulkan;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Model;

/**
 * Throwaway recon for M6 — counts every {@code DrawCallbacks} invocation and
 * aggregates Model vertex/face totals so we can see what shape the OSRS
 * client's per-frame data actually has. Logs a compact one-line summary
 * once per second from {@link #maybeLog}.
 *
 * <p>All counters are atomic because the OSRS scene compute can spin up
 * {@code DrawCallbacks.RENDER_THREADS_MASK} threads.
 *
 * <p>Will be deleted when M7 starts rendering scene geometry for real.
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
		long fenceWait = fenceWaitNanos.getAndSet(0);
		long uiUpload = uiUploadNanos.getAndSet(0);
		long acquire = acquireNanos.getAndSet(0);
		long commandRecord = commandRecordNanos.getAndSet(0);
		long beforePass = beforeRenderPassNanos.getAndSet(0);
		long renderPass = renderPassNanos.getAndSet(0);
		long submit = submitNanos.getAndSet(0);
		long present = presentNanos.getAndSet(0);
		long customDrawable = customDrawableNanos.getAndSet(0);
		long drawSceneCount = drawScene.getAndSet(0);
		long beginFrame = beginFrameNanos.getAndSet(0);
		long pendingCapture = pendingCaptureNanos.getAndSet(0);
		long sceneCapture = sceneCaptureNanos.getAndSet(0);
		if (!detailedModelStats)
		{
			log.info(String.format(
				"recon | scene=%d preSD=%d postSD=%d swap=%d load=%d | paint=%d tileModel=%d | zoneOpq=%d zoneAlpha=%d | dyn=%d temp=%d pass=%d single=%d | cam=(%.1f, %.1f, %.1f) plane=%d | anim=%d | cpu/frame avg ms: draw=%.2f fence=%.2f ui=%.2f acquire=%.2f record=%.2f beforePass=%.2f pass=%.2f submit=%.2f present=%.2f drawable=%.2f | scene avg ms: begin=%.2f pending=%.2f staticCaptureTotal=%.2f",
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
				avgMs(beginFrame, drawSceneCount),
				avgMs(pendingCapture, drawSceneCount),
				totalMs(sceneCapture)
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
				"recon | scene=%d preSD=%d postSD=%d swap=%d load=%d | paint=%d tileModel=%d | zoneOpq=%d zoneAlpha=%d | dyn=%d temp=%d pass=%d single=%d | dynVerts=%d dynFaces=%d maxF=%d | cam=(%.1f, %.1f, %.1f) plane=%d | anim=%d | cpu/frame avg ms: draw=%.2f fence=%.2f ui=%.2f acquire=%.2f record=%.2f beforePass=%.2f pass=%.2f submit=%.2f present=%.2f drawable=%.2f | scene avg ms: begin=%.2f pending=%.2f staticCaptureTotal=%.2f | model ms: sort=%.2f full=%.2f cull=%.2f emit=%.2f sortedEmit=%.2f unsortedEmit=%.2f uv=%.2f | models sorted=%d full=%d cull=%d unsorted=%d fallback=%d | faces sorted=%d unsorted=%d tex=%d override=%d fullTrans=%d",
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
				avgMs(beginFrame, drawSceneCount),
				avgMs(pendingCapture, drawSceneCount),
				totalMs(sceneCapture),
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
	}
}
