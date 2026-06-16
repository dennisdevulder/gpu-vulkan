/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import net.runelite.api.Client;
import net.runelite.api.hooks.DrawCallbacks;

final class ClientRuntimeConfig
{
	private final Client client;
	private final GpuVulkanPluginConfig config;
	private boolean fpsTouched;

	ClientRuntimeConfig(Client client, GpuVulkanPluginConfig config)
	{
		this.client = client;
		this.config = config;
	}

	void apply()
	{
		int gpuFlags = DrawCallbacks.GPU | DrawCallbacks.ZBUF;
		if (config.removeVertexSnapping())
		{
			gpuFlags |= DrawCallbacks.NO_VERTEX_SNAPPING;
		}
		client.setGpuFlags(gpuFlags);
		client.setExpandedMapLoading(config.expandedMapLoadingChunks());

		boolean unlockFps = config.fpsMode() == GpuVulkanPluginConfig.FpsMode.UNCAPPED;
		client.setUnlockedFps(unlockFps);
		if (unlockFps)
		{
			client.setUnlockedFpsTarget(Math.max(0, config.fpsTarget()));
		}
		fpsTouched = unlockFps;
	}

	void restoreFpsIfTouched()
	{
		if (fpsTouched)
		{
			client.setUnlockedFps(false);
			fpsTouched = false;
		}
	}
}
