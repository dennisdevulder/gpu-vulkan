/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

import java.nio.ByteBuffer;

/**
 * Records draw commands into the current frame's command stream. Inside the
 * backend's own passes the encoder is already mid-pass; extension-owned passes
 * against a {@link RenderTarget} are bracketed with {@link #beginPass} /
 * {@link #endPass}.
 */
public interface RenderEncoder
{
	/** Begins an offscreen pass (clears color + reverse-Z depth, sets full-target
	 *  viewport/scissor). Must not be called while another pass is open. */
	RenderEncoder beginPass(RenderTarget target, float r, float g, float b, float a);

	RenderEncoder endPass();

	/** Transitions the target's color image for sampling. Call between
	 *  {@link #endPass()} and the sampling pass; outside any pass. */
	RenderEncoder prepareForSampling(RenderTarget target);

	RenderEncoder bindPipeline(RenderPipeline pipeline);

	RenderEncoder bindComputePipeline(ComputePipeline pipeline);

	RenderEncoder bindBindGroup(int set, BindGroup group);

	RenderEncoder bindVertexBuffer(int binding, GpuBuffer buffer, long offset);

	/** Binds a {@code VK_INDEX_TYPE_UINT32} index buffer. */
	RenderEncoder bindIndexBuffer(GpuBuffer buffer, long offset);

	/** {@code stages} must be a subset of the pipeline's matching range;
	 *  {@code data}'s remaining bytes are copied without advancing its position. */
	RenderEncoder pushConstants(int stages, int offset, ByteBuffer data);

	RenderEncoder setViewport(int x, int y, int width, int height);

	RenderEncoder setScissor(int x, int y, int width, int height);

	RenderEncoder draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);

	RenderEncoder drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance);

	/** Only valid strictly outside render passes. */
	RenderEncoder dispatch(int x, int y, int z);
}
