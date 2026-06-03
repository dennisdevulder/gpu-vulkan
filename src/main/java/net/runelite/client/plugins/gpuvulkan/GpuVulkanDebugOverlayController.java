/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package net.runelite.client.plugins.gpuvulkan;

import java.util.List;
import net.runelite.client.ui.overlay.OverlayManager;

final class GpuVulkanDebugOverlayController
{
	private final GpuVulkanDebugOverlay overlay;
	private final GpuVulkanPluginConfig config;
	private final OverlayManager overlayManager;
	private final DrawCallbackStats stats;
	private volatile List<String> snapshot = List.of("GPU Vulkan", "status: starting");
	private boolean registered;

	GpuVulkanDebugOverlayController(GpuVulkanPlugin plugin, GpuVulkanPluginConfig config,
		OverlayManager overlayManager, DrawCallbackStats stats)
	{
		this.overlay = new GpuVulkanDebugOverlay(plugin, config);
		this.config = config;
		this.overlayManager = overlayManager;
		this.stats = stats;
	}

	List<String> lines()
	{
		return snapshot;
	}

	boolean isRegistered()
	{
		return registered;
	}

	void updateSnapshot(VulkanDevice device, Swapchain swapchain, RenderExtensions renderExtensions)
	{
		snapshot = GpuVulkanDebugSnapshot.build(device, swapchain, renderExtensions, stats);
	}

	void updateRegistration()
	{
		if (config.debugOverlay())
		{
			if (!registered)
			{
				overlayManager.add(overlay);
				registered = true;
				stats.setOverlayStatsEnabled(true);
			}
		}
		else
		{
			remove();
		}
	}

	void remove()
	{
		if (registered)
		{
			overlayManager.remove(overlay);
			registered = false;
			stats.setOverlayStatsEnabled(false);
		}
	}
}
