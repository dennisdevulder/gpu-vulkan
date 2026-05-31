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
package net.runelite.client.plugins.gpuvulkan;

import java.awt.Canvas;

/**
 * Strategy for creating a {@code VkSurfaceKHR} against an AWT canvas on the
 * current OS. Each OS exposes a different native window handle through JAWT
 * (X11 Display+Window, Win32 HWND, Cocoa CAMetalLayer) and requires a
 * different Vulkan instance extension to wrap it ({@code VK_KHR_xlib_surface},
 * {@code VK_KHR_win32_surface}, {@code VK_EXT_metal_surface}).
 *
 * <p>Pick the right implementation once at startup via {@link #current()}; pass
 * the same instance to both {@link VulkanInstance} (so the right extension is
 * enabled) and {@link VulkanSurface} (so the right surface-create call is
 * made). Mismatched picks would fail validation at {@code vkCreate*SurfaceKHR}.
 */
interface PlatformSurface
{
	/** Vulkan instance extension names this platform needs in addition to
	 *  {@code VK_KHR_surface}. Order doesn't matter. */
	String[] requiredInstanceExtensions();

	/** Create a {@code VkSurfaceKHR} for the given canvas. Caller takes
	 *  ownership; destroys via {@code vkDestroySurfaceKHR}. */
	long createSurface(VulkanInstance instance, Canvas canvas);

	/** Pick the implementation matching the running OS. Throws with a clear
	 *  message on platforms we don't yet support so the plugin enable path
	 *  surfaces a usable error rather than a confusing Vulkan failure.
	 *
	 *  @param vsync macOS only — when true, CAMetalLayer is configured with
	 *               {@code displaySyncEnabled = YES} so presents block until
	 *               the next display refresh. Other platforms ignore this
	 *               (their vsync behaviour is controlled via Vulkan present
	 *               modes on the swapchain side). */
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
		if (os.contains("mac") || os.contains("darwin"))
		{
			return new MacOSPlatformSurface(vsync);
		}
		throw new UnsupportedOperationException(
			"GPU (Vulkan) plugin: unsupported OS \"" + System.getProperty("os.name") + "\"");
	}
}
