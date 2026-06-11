/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

final class SwapchainRebuildGate
{
	private static final int STABLE_FRAMES = 2;

	private boolean stale;
	private int pendingWidth = -1;
	private int pendingHeight = -1;
	private int stableFrames;

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
		if (pendingWidth != width || pendingHeight != height)
		{
			pendingWidth = width;
			pendingHeight = height;
			stableFrames = 1;
			return false;
		}
		stableFrames++;
		return stableFrames >= STABLE_FRAMES;
	}

	void markRebuilt()
	{
		stale = false;
		pendingWidth = -1;
		pendingHeight = -1;
		stableFrames = 0;
	}
}
