/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import net.runelite.api.Client;
import com.gpuvulkan.gfx.RenderDevice;

/**
 * Stable host context exposed to Vulkan render extensions. The backend owns
 * the instance/device/swapchain/frame lifecycle — extensions may inspect
 * handles but must not destroy backend-owned objects.
 */
public interface VulkanRenderContext
{
	Client client();

	GpuVulkanPluginConfig config();

	RenderDevice renderer();

	VulkanSceneRenderer createSceneRenderer();

	String deviceName();

	long deviceHandle();

	long physicalDeviceHandle();

	long renderPassHandle();

	int renderPassSamples();

	int graphicsQueueFamily();

	int framesInFlight();
}
