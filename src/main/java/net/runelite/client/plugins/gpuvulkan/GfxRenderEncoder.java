package net.runelite.client.plugins.gpuvulkan;

import java.nio.ByteBuffer;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroup;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderEncoder;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderPipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Thin wrapper over a command buffer that is already mid-render-pass. Each
 * method records exactly one Vulkan call; the verbosity reduction is in
 * what the consumer DOESN'T have to think about — pipeline layouts pulled
 * from the bound pipeline, descriptor sets routed to the current frame's
 * slot inside {@link GfxBindGroup}, viewport/scissor laid out for them.
 *
 * <p>Stack lifetime: {@link #pushConstants} and {@link #setViewport} /
 * {@link #setScissor} stack-allocate their structs per call. Push-constants
 * are copied into the command buffer immediately; viewport/scissor structs
 * are read by {@code vkCmdSetViewport} / {@code vkCmdSetScissor} during the
 * call, both pure value semantics. No struct outlives its stack scope.
 */
final class GfxRenderEncoder implements RenderEncoder
{
	private final VkCommandBuffer cmd;
	private long currentPipelineLayout;

	GfxRenderEncoder(VkCommandBuffer cmd)
	{
		this.cmd = cmd;
	}

	@Override
	public RenderEncoder bindPipeline(RenderPipeline pipeline)
	{
		GfxRenderPipeline gp = (GfxRenderPipeline) pipeline;
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, gp.handle());
		currentPipelineLayout = gp.layout();
		return this;
	}

	@Override
	public RenderEncoder bindBindGroup(int set, BindGroup group)
	{
		if (currentPipelineLayout == VK_NULL_HANDLE)
		{
			throw new IllegalStateException("bindBindGroup called before bindPipeline");
		}
		GfxBindGroup g = (GfxBindGroup) group;
		try (MemoryStack stack = stackPush())
		{
			vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
				currentPipelineLayout, set,
				stack.longs(g.descriptorSetForCurrentFrame()),
				null);
		}
		return this;
	}

	@Override
	public RenderEncoder pushConstants(int stages, int offset, ByteBuffer data)
	{
		if (currentPipelineLayout == VK_NULL_HANDLE)
		{
			throw new IllegalStateException("pushConstants called before bindPipeline");
		}
		vkCmdPushConstants(cmd, currentPipelineLayout, stages, offset, data);
		return this;
	}

	@Override
	public RenderEncoder setViewport(int x, int y, int width, int height)
	{
		try (MemoryStack stack = stackPush())
		{
			VkViewport.Buffer vp = VkViewport.calloc(1, stack);
			vp.get(0)
				.x(x).y(y)
				.width(width).height(height)
				.minDepth(0f).maxDepth(1f);
			vkCmdSetViewport(cmd, 0, vp);
		}
		return this;
	}

	@Override
	public RenderEncoder setScissor(int x, int y, int width, int height)
	{
		try (MemoryStack stack = stackPush())
		{
			VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
			sc.get(0)
				.offset(o -> o.set(x, y))
				.extent(e -> e.set(width, height));
			vkCmdSetScissor(cmd, 0, sc);
		}
		return this;
	}

	@Override
	public RenderEncoder draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance)
	{
		vkCmdDraw(cmd, vertexCount, instanceCount, firstVertex, firstInstance);
		return this;
	}
}
