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

import net.runelite.api.Client;
import com.gpuvulkan.gfx.RenderDevice;
import com.gpuvulkan.gfx.Renderer;

final class DefaultVulkanRenderContext implements VulkanRenderContext
{
	private final Client client;
	private final GpuVulkanPluginConfig config;
	private final Renderer renderer;
	private final VulkanDevice device;
	private final FrameSync sync;
	private final RenderPass renderPass;
	private final TextureArray textureArray;
	private final DrawCallbackStats stats;

	DefaultVulkanRenderContext(Client client, GpuVulkanPluginConfig config,
		Renderer renderer, VulkanDevice device, FrameSync sync,
		RenderPass renderPass, TextureArray textureArray,
		DrawCallbackStats stats)
	{
		this.client = client;
		this.config = config;
		this.renderer = renderer;
		this.device = device;
		this.sync = sync;
		this.renderPass = renderPass;
		this.textureArray = textureArray;
		this.stats = stats;
	}

	/** Backend-internal escape hatch for in-repo extensions (recorder). External
	 *  consumers get raw handles through the public interface instead. */
	VulkanDevice device()
	{
		return device;
	}

	@Override
	public Client client()
	{
		return client;
	}

	@Override
	public GpuVulkanPluginConfig config()
	{
		return config;
	}

	@Override
	public RenderDevice renderer()
	{
		return renderer;
	}

	@Override
	public VulkanSceneRenderer createSceneRenderer()
	{
		return new DefaultVulkanSceneRenderer(device, sync, renderPass, textureArray, stats);
	}

	@Override
	public String deviceName()
	{
		return device.deviceName();
	}

	@Override
	public long deviceHandle()
	{
		return device.handle().address();
	}

	@Override
	public long physicalDeviceHandle()
	{
		return device.physicalDevice().address();
	}

	@Override
	public long renderPassHandle()
	{
		return renderPass.handle();
	}

	@Override
	public int renderPassSamples()
	{
		return renderPass.samples();
	}

	@Override
	public int graphicsQueueFamily()
	{
		return device.graphicsQueueFamily();
	}

	@Override
	public int framesInFlight()
	{
		return FrameSync.FRAMES_IN_FLIGHT;
	}
}
