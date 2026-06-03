/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package net.runelite.client.plugins.gpuvulkan;

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

		int fpsTarget = Math.max(0, config.fpsTarget());
		client.setUnlockedFps(true);
		client.setUnlockedFpsTarget(fpsTarget);
		fpsTouched = true;
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
