package net.runelite.client.plugins.gpuvulkan.gfx;

/**
 * A baked graphics pipeline: vertex layout, shader stages, blend / depth /
 * rasterizer state, push-constant ranges, descriptor set layouts, target
 * attachments. Created once at init time; bound at draw time via
 * {@link RenderEncoder#bindPipeline(RenderPipeline)}.
 *
 * <p>Pipelines are tied to a specific render-pass shape. Recreating the
 * swapchain doesn't invalidate the pipeline as long as the attachment
 * formats and sample count don't change, which is the case for our
 * resize path today.
 */
public interface RenderPipeline extends AutoCloseable
{
	@Override
	void close();
}
