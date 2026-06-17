/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
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
