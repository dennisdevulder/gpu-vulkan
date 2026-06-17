/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

final class SwapchainRebuildGate
{
	private static final int STABLE_FRAMES = 2;
	private static final long SETTLE_NANOS = 80_000_000L;

	private boolean stale;
	private int pendingWidth = -1;
	private int pendingHeight = -1;
	private int stableFrames;
	private long rebuildAfterNanos;

	void markStale()
	{
		stale = true;
	}

	boolean isStale()
	{
		return stale;
	}

	boolean targetStable(int width, int height)
	{
		long now = System.nanoTime();
		if (pendingWidth != width || pendingHeight != height)
		{
			pendingWidth = width;
			pendingHeight = height;
			stableFrames = 1;
			rebuildAfterNanos = now + SETTLE_NANOS;
			return false;
		}
		stableFrames++;
		return stableFrames >= STABLE_FRAMES && now >= rebuildAfterNanos;
	}

	void markRebuilt()
	{
		stale = false;
		pendingWidth = -1;
		pendingHeight = -1;
		stableFrames = 0;
		rebuildAfterNanos = 0L;
	}
}
