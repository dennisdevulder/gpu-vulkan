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
