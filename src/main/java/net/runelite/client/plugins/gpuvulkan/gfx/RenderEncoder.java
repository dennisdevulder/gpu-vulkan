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
package net.runelite.client.plugins.gpuvulkan.gfx;

import java.nio.ByteBuffer;

/**
 * Records draw commands into the current frame's command stream. Inside the
 * backend's own passes consumers receive a {@code RenderEncoder} that's
 * already mid-pass and can issue draws; extension-owned passes against a
 * {@link RenderTarget} are bracketed with {@link #beginPass} /
 * {@link #endPass}.
 */
public interface RenderEncoder
{
	/**
	 * Begins a render pass on an offscreen target, clearing color to the
	 * given values and depth to the reverse-Z far plane. Sets a full-target
	 * viewport and scissor. Must not be called while another pass is open.
	 */
	RenderEncoder beginPass(RenderTarget target, float r, float g, float b, float a);

	RenderEncoder endPass();

	/**
	 * Transitions the target's color image for sampling. Call between
	 * {@link #endPass()} and the pass that samples it; outside any pass.
	 */
	RenderEncoder prepareForSampling(RenderTarget target);

	RenderEncoder bindPipeline(RenderPipeline pipeline);

	RenderEncoder bindComputePipeline(ComputePipeline pipeline);

	RenderEncoder bindBindGroup(int set, BindGroup group);

	RenderEncoder bindVertexBuffer(int binding, GpuBuffer buffer, long offset);

	/** Binds a {@code VK_INDEX_TYPE_UINT32} index buffer. */
	RenderEncoder bindIndexBuffer(GpuBuffer buffer, long offset);

	/**
	 * Updates a contiguous push-constant range. {@code stages} must be a
	 * subset of the stages declared on the pipeline's matching range.
	 * {@code data}'s remaining bytes are copied; this method does not
	 * advance the buffer's position.
	 */
	RenderEncoder pushConstants(int stages, int offset, ByteBuffer data);

	RenderEncoder setViewport(int x, int y, int width, int height);

	RenderEncoder setScissor(int x, int y, int width, int height);

	RenderEncoder draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);

	RenderEncoder drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance);

	/** Only valid strictly outside render passes. */
	RenderEncoder dispatch(int x, int y, int z);
}
