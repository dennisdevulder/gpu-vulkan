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
