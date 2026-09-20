/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Non-owning rendering-device facade exposed to extensions.
 *
 * <p>Resources returned by {@code create*} are owned by the caller and must be
 * closed by the caller. The backend-owned device facade itself is borrowed and
 * intentionally has no {@code close()} method.
 */
public interface RenderDevice
{
	ShaderModule createShaderModule(byte[] spirv);

	BindGroupLayout createBindGroupLayout(BindGroupLayoutDesc desc);

	BindGroup createBindGroup(BindGroupDesc desc);

	RenderPipeline createRenderPipeline(RenderPipelineDesc desc);

	ComputePipeline createComputePipeline(ComputePipelineDesc desc);

	GpuBuffer createBuffer(long size, BufferUsage usage);

	StreamingImage createStreamingImage(int width, int height);

	/** {@code samples} is a {@code VK_SAMPLE_COUNT_*} value — match the main
	 *  scene pass via renderPassSamples(), or 1 for post-process targets. */
	RenderTarget createRenderTarget(int width, int height, int samples);

	int currentSlot();

	RenderEncoder encodeInto(VkCommandBuffer cmd);
}
