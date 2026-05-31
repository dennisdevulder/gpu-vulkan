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

import net.runelite.api.Client;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderDevice;

/**
 * Stable host context exposed to Vulkan render extensions.
 *
 * <p>The backend still owns the Vulkan instance, device, surface, swapchain and
 * frame lifecycle. Extensions can inspect handles when they need to allocate
 * their own Vulkan resources, but they should not destroy backend-owned
 * objects.
 */
public interface VulkanRenderContext
{
	Client client();

	GpuVulkanPluginConfig config();

	/**
	 * Shared rendering-device facade for creating bind groups, pipelines,
	 * shader modules and streaming images.
	 */
	RenderDevice renderer();

	/**
	 * Creates an extension-owned scene renderer using the backend's current
	 * Vulkan device, frame sync, render pass and texture array.
	 */
	VulkanSceneRenderer createSceneRenderer();

	/**
	 * Vulkan video encode capability context. The context is always present,
	 * but {@link VulkanEncodeContext#isAvailable()} may be false when the
	 * current backend device was not created with encode queues/extensions.
	 */
	VulkanEncodeContext encode();

	String deviceName();

	long deviceHandle();

	long physicalDeviceHandle();

	long renderPassHandle();

	int renderPassSamples();

	int graphicsQueueFamily();

	int framesInFlight();
}
