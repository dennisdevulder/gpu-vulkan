package net.runelite.client.plugins.gpuvulkan;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
	// Per-method call counters
	final AtomicLong drawScene = new AtomicLong();
	final AtomicLong preSceneDraw = new AtomicLong();
	final AtomicLong postDrawScene = new AtomicLong();
	volatile int lastHideRoofSize;
	final AtomicLong swapScene = new AtomicLong();
	final AtomicLong loadScene = new AtomicLong();
	final AtomicLong drawScenePaint = new AtomicLong();
	final AtomicLong drawSceneTileModel = new AtomicLong();
	final AtomicLong drawZoneOpaque = new AtomicLong();
	final AtomicLong drawZoneAlpha = new AtomicLong();
	final AtomicLong drawDynamic = new AtomicLong();
	final AtomicLong drawTemp = new AtomicLong();
	final AtomicLong drawPass = new AtomicLong();
	final AtomicLong drawSingle = new AtomicLong();   // draw(Projection, Scene, Renderable, ...)
	final AtomicLong animate = new AtomicLong();

	// Aggregate model stats (for the methods carrying a Model)
	final AtomicLong totalDynamicVerts = new AtomicLong();
	final AtomicLong totalDynamicFaces = new AtomicLong();
	final AtomicInteger maxDynamicFaces = new AtomicInteger();

	// CPU timing buckets. Aggregated once per stats log interval.
	final AtomicLong frames = new AtomicLong();
	final AtomicLong drawFrameNanos = new AtomicLong();
	final AtomicLong fenceWaitNanos = new AtomicLong();
	final AtomicLong uiUploadNanos = new AtomicLong();
	final AtomicLong acquireNanos = new AtomicLong();
	final AtomicLong commandRecordNanos = new AtomicLong();
	final AtomicLong beforeRenderPassNanos = new AtomicLong();
	final AtomicLong renderPassNanos = new AtomicLong();
	final AtomicLong submitNanos = new AtomicLong();
	final AtomicLong presentNanos = new AtomicLong();
	final AtomicLong customDrawableNanos = new AtomicLong();
	final AtomicLong beginFrameNanos = new AtomicLong();
	final AtomicLong actorCaptureNanos = new AtomicLong();
	final AtomicLong pendingCaptureNanos = new AtomicLong();
	final AtomicLong sceneCaptureNanos = new AtomicLong();
	final AtomicLong modelSortNanos = new AtomicLong();
	final AtomicLong modelEmitNanos = new AtomicLong();
	final AtomicLong sortedModels = new AtomicLong();
	final AtomicLong unsortedModels = new AtomicLong();
	final AtomicLong sortFallbackModels = new AtomicLong();
	final AtomicLong sortedFaces = new AtomicLong();
	final AtomicLong unsortedFaces = new AtomicLong();

	// Sample fields — last-write-wins, fine for "what's the camera at"
	volatile double lastCamX;
	volatile double lastCamY;
	volatile double lastCamZ;
	volatile int lastCamPlane;

	private long nextLogNanos = System.nanoTime() + 1_000_000_000L;

	void recordModel(Model m)
	{
		if (m == null) return;
		int verts = m.getVerticesCount();
		int[] fa = m.getFaceIndices1();
		int faces = fa == null ? 0 : fa.length;
		totalDynamicVerts.addAndGet(verts);
		totalDynamicFaces.addAndGet(faces);
		// CAS loop to maintain the running max without locks.
		int prev;
		do { prev = maxDynamicFaces.get(); } while (faces > prev && !maxDynamicFaces.compareAndSet(prev, faces));
	}

	void addNanos(AtomicLong bucket, long startNanos)
	{
		bucket.addAndGet(System.nanoTime() - startNanos);
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
		long actorCapture = actorCaptureNanos.getAndSet(0);
		long pendingCapture = pendingCaptureNanos.getAndSet(0);
		long sceneCapture = sceneCaptureNanos.getAndSet(0);
		long sort = modelSortNanos.getAndSet(0);
		long emit = modelEmitNanos.getAndSet(0);
		long sortedModelCount = sortedModels.getAndSet(0);
		long unsortedModelCount = unsortedModels.getAndSet(0);
		long fallbackModelCount = sortFallbackModels.getAndSet(0);
		long sortedFaceCount = sortedFaces.getAndSet(0);
		long unsortedFaceCount = unsortedFaces.getAndSet(0);

		log.info(String.format(
			"recon | scene=%d preSD=%d postSD=%d swap=%d load=%d | paint=%d tileModel=%d | zoneOpq=%d zoneAlpha=%d | dyn=%d temp=%d pass=%d single=%d | dynVerts=%d dynFaces=%d maxF=%d | cam=(%.1f, %.1f, %.1f) plane=%d | anim=%d | cpu/frame avg ms: draw=%.2f fence=%.2f ui=%.2f acquire=%.2f record=%.2f beforePass=%.2f pass=%.2f submit=%.2f present=%.2f drawable=%.2f | scene avg ms: begin=%.2f actors=%.2f pending=%.2f staticCaptureTotal=%.2f | model ms: sort=%.2f emit=%.2f | models sorted=%d unsorted=%d fallback=%d | faces sorted=%d unsorted=%d",
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
			avgMs(actorCapture, drawSceneCount),
			avgMs(pendingCapture, drawSceneCount),
			totalMs(sceneCapture),
			totalMs(sort),
			totalMs(emit),
			sortedModelCount,
			unsortedModelCount,
			fallbackModelCount,
			sortedFaceCount,
			unsortedFaceCount
		));
	}
}
