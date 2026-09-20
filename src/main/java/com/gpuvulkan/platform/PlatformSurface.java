/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import java.awt.Canvas;

/**
 * Per-OS {@code VkSurfaceKHR} creation strategy. The same instance must go to
 * both {@link VulkanInstance} and {@link VulkanSurface} — they have to match.
 */
interface PlatformSurface
{
	/** Vulkan instance extension names this platform needs in addition to
	 *  {@code VK_KHR_surface}. Order doesn't matter. */
	String[] requiredInstanceExtensions();

	/** Create a {@code VkSurfaceKHR} for the given canvas. Caller takes
	 *  ownership; destroys via {@code vkDestroySurfaceKHR}. */
	long createSurface(VulkanInstance instance, Canvas canvas);

	/** Platforms control vsync via swapchain present modes. */
	static PlatformSurface current(boolean vsync)
	{
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("linux") || os.contains("nix") || os.contains("nux") || os.contains("aix"))
		{
			return new X11PlatformSurface();
		}
		if (os.contains("win"))
		{
			return new Win32PlatformSurface();
		}
		throw new UnsupportedOperationException(
			"GPU (Vulkan) plugin: unsupported OS \"" + System.getProperty("os.name") + "\"");
	}
}
