/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import java.awt.Canvas;

import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;

/**
 * Wraps a {@code VkSurfaceKHR} created from the AWT canvas; creation is
 * delegated to the per-OS {@link PlatformSurface}.
 */
final class VulkanSurface implements AutoCloseable
{
	private final VulkanInstance instance;
	private final PlatformSurface platform;
	private long handle;

	VulkanSurface(VulkanInstance instance, PlatformSurface platform, Canvas canvas)
	{
		this.instance = instance;
		this.platform = platform;
		recreate(canvas);
	}

	long handle()
	{
		return handle;
	}

	void recreate(Canvas canvas)
	{
		close();
		handle = platform.createSurface(instance, canvas);
	}

	@Override
	public void close()
	{
		if (handle != 0L)
		{
			vkDestroySurfaceKHR(instance.handle(), handle, null);
			handle = 0L;
		}
	}
}
