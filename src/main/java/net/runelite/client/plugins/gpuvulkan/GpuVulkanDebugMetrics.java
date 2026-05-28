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
	long modelComputeOutputBytes;
	boolean modelComputeDebugDraw;
	int modelComputeDebugFaces;
	int modelComputeClippedFaces;
	int modelComputeTrackedFaces;
	int modelComputeCandidateFaces;
	int modelComputeSortedFaces;
	int modelComputeTexturedFaces;
	int modelComputeBiasedFaces;
	int modelComputeOverrideFaces;
	boolean overflowed;
	long sceneBufferBytes;
}
