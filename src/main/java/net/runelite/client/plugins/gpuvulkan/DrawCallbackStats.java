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

	void maybeLog()
	{
		long now = System.nanoTime();
		if (now < nextLogNanos) return;
		nextLogNanos = now + 1_000_000_000L;
		log.info(String.format(
			"recon | scene=%d preSD=%d postSD=%d swap=%d load=%d | paint=%d tileModel=%d | zoneOpq=%d zoneAlpha=%d | dyn=%d temp=%d pass=%d single=%d | dynVerts=%d dynFaces=%d maxF=%d | cam=(%.1f, %.1f, %.1f) plane=%d | anim=%d",
			drawScene.getAndSet(0),
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
			animate.getAndSet(0)
		));
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
}
