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
 * Records draw commands into the current frame's command stream. Acquired
 * implicitly by being inside a render pass — for now the layer doesn't
 * expose render-pass boundaries (the existing
 * {@code VulkanRenderer.recordClearPass} owns them) so consumers receive a
 * {@code RenderEncoder} that's already mid-pass and can issue draws.
 *
 * <p>Verbs are intentionally close to WebGPU's {@code GPURenderPassEncoder}.
 */
public interface RenderEncoder
{
	RenderEncoder bindPipeline(RenderPipeline pipeline);

	RenderEncoder bindBindGroup(int set, BindGroup group);

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
}
