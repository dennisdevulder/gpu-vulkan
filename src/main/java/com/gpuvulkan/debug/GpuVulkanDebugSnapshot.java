/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import java.util.ArrayList;
import java.util.List;

final class GpuVulkanDebugSnapshot
{
	private GpuVulkanDebugSnapshot()
	{
	}

	static List<String> build(VulkanDevice device, Swapchain swapchain,
		RenderExtensions renderExtensions, DrawCallbackStats stats)
	{
		ArrayList<String> lines = new ArrayList<>(24);
		Runtime rt = Runtime.getRuntime();
		long heapUsed = rt.totalMemory() - rt.freeMemory();
		long heapMax = rt.maxMemory();
		GpuVulkanDebugMetrics metrics = new GpuVulkanDebugMetrics();
		if (renderExtensions != null)
		{
			renderExtensions.collectDebugMetrics(metrics);
		}
		metrics.uiUploadBytes += stats.uiUploadBytes.get();
		metrics.screenshotReadbackBytes += stats.screenshotReadbackBytes.get();

		lines.add("GPU Vulkan");
		lines.add("device: " + compactDeviceName(device == null ? "not ready" : device.deviceName()));
		lines.add("swap: " + (swapchain == null ? "-" :
			swapchain.width() + "x" + swapchain.height() + " x" + swapchain.imageCount()));
		lines.add("heap: " + mib(heapUsed) + " / " + mib(heapMax) + " MiB");
		lines.add("scene buf: " + mib(metrics.sceneBufferBytes) + " MiB native");
		lines.add("verts: " + compactCount(metrics.totalVertices) + " / " + compactCount(metrics.maxVertices));
		lines.add("static: " + compactCount(metrics.sceneVertices));
		lines.add("roofs: " + metrics.roofRanges);
		lines.add("dirty zones: " + metrics.dirtyZones);
		lines.add("pending: " + metrics.pendingRenderables);
		lines.add("model cache: " + compactCount(metrics.modelCacheEntries)
			+ " h/m " + compactCount(metrics.modelCacheHits) + "/" + compactCount(metrics.modelCacheMisses));
		lines.add("vk draws: " + compactCount(metrics.sceneDrawCalls)
			+ " v=" + compactCount(metrics.sceneDrawVertices)
			+ " pc=" + compactCount(metrics.scenePushConstants));
		lines.add("roof/overlay: " + compactCount(metrics.roofSkipPairs)
			+ " / " + compactCount(metrics.overlayDirtyZones));
		lines.add("upload/read: " + mib(metrics.uiUploadBytes) + " / "
			+ mib(metrics.screenshotReadbackBytes) + " MiB");
		lines.add("overflow: " + (metrics.overflowed ? "yes" : "no"));
		lines.add("scene/pre/post: " + stats.drawSceneCount() + " / "
			+ stats.preSceneDrawCount() + " / " + stats.postDrawSceneCount());
		lines.add("dyn calls: " + stats.drawDynamicCount());
		lines.add("dyn temp/pass: " + stats.drawTempCount() + " / " + stats.drawPassCount());
		lines.add("dyn single: " + stats.drawSingleCount());
		lines.add("dyn faces: " + compactCount(stats.totalDynamicFacesCount()));
		lines.add("dyn max: " + stats.maxDynamicFacesCount());
		return List.copyOf(lines);
	}

	private static String compactDeviceName(String name)
	{
		return name.startsWith("Apple ") ? name.substring("Apple ".length()) : name;
	}

	private static String compactCount(long value)
	{
		if (value >= 1_000_000L)
		{
			long tenths = value / 100_000L;
			return (tenths / 10L) + "." + (tenths % 10L) + "M";
		}
		if (value >= 10_000L)
		{
			return (value / 1_000L) + "k";
		}
		return Long.toString(value);
	}

	private static long mib(long bytes)
	{
		return (bytes + 1024L * 1024L - 1L) / (1024L * 1024L);
	}
}
