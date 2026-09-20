/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

public final class GpuVulkanDebugMetrics
{
	int sceneVertices;
	int totalVertices;
	int maxVertices;
	int roofRanges;
	int dirtyZones;
	int pendingRenderables;
	int modelCacheEntries;
	long modelCacheHits;
	long modelCacheMisses;
	long sceneDrawCalls;
	long sceneDrawVertices;
	long scenePushConstants;
	long roofSkipPairs;
	long overlayDirtyZones;
	long uiUploadBytes;
	long screenshotReadbackBytes;
	boolean overflowed;
	long sceneBufferBytes;
}
