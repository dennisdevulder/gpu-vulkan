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
