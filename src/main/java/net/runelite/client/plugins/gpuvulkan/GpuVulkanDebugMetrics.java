package net.runelite.client.plugins.gpuvulkan;

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
	long modelMeshBytes;
	long modelMeshCapacityBytes;
	int modelInstances;
	int modelInstanceMax;
	int modelInstanceOverflows;
	boolean overflowed;
	long sceneBufferBytes;
}
