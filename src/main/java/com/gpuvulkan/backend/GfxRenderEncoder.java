/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import java.nio.ByteBuffer;
import com.gpuvulkan.gfx.BindGroup;
import com.gpuvulkan.gfx.ComputePipeline;
import com.gpuvulkan.gfx.GpuBuffer;
import com.gpuvulkan.gfx.RenderEncoder;
import com.gpuvulkan.gfx.RenderPipeline;
import com.gpuvulkan.gfx.RenderTarget;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Stack-allocates per-call structs; all are consumed during the vkCmd call,
 * so no struct outlives its stack scope.
 */
final class GfxRenderEncoder implements RenderEncoder
{
	private final VkCommandBuffer cmd;
	private long currentPipelineLayout;
	private int currentBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;

	GfxRenderEncoder(VkCommandBuffer cmd)
	{
		this.cmd = cmd;
	}

	@Override
	public RenderEncoder beginPass(RenderTarget target, float r, float g, float b, float a)
	{
		GfxRenderTarget t = (GfxRenderTarget) target;
		try (MemoryStack stack = stackPush())
		{
			VkClearValue.Buffer clears = VkClearValue.calloc(2, stack);
			clears.get(0).color(c -> c.float32(0, r).float32(1, g).float32(2, b).float32(3, a));
			// Reverse-Z: far plane clears to 0.
			clears.get(1).depthStencil(ds -> ds.depth(0.0f).stencil(0));

			VkRenderPassBeginInfo info = VkRenderPassBeginInfo.calloc(stack)
				.sType$Default()
				.renderPass(t.renderPassHandle())
				.framebuffer(t.framebuffer())
				.renderArea(area -> area.offset(o -> o.set(0, 0)).extent(e -> e.set(t.width(), t.height())))
				.pClearValues(clears);
			vkCmdBeginRenderPass(cmd, info, VK_SUBPASS_CONTENTS_INLINE);
		}
		setViewport(0, 0, t.width(), t.height());
		setScissor(0, 0, t.width(), t.height());
		return this;
	}

	@Override
	public RenderEncoder endPass()
	{
		vkCmdEndRenderPass(cmd);
		return this;
	}

	@Override
	public RenderEncoder prepareForSampling(RenderTarget target)
	{
		GfxRenderTarget t = (GfxRenderTarget) target;
		try (MemoryStack stack = stackPush())
		{
			VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
			barrier.get(0)
				.sType$Default()
				.oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
				.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.image(t.colorImage())
				.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
				.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
			barrier.get(0).subresourceRange()
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0).levelCount(1)
				.baseArrayLayer(0).layerCount(1);
			vkCmdPipelineBarrier(cmd,
				VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
				VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
				0, null, null, barrier);
		}
		return this;
	}

	@Override
	public RenderEncoder bindPipeline(RenderPipeline pipeline)
	{
		GfxRenderPipeline gp = (GfxRenderPipeline) pipeline;
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, gp.handle());
		currentPipelineLayout = gp.layout();
		currentBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
		return this;
	}

	@Override
	public RenderEncoder bindComputePipeline(ComputePipeline pipeline)
	{
		GfxComputePipeline cp = (GfxComputePipeline) pipeline;
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, cp.handle());
		currentPipelineLayout = cp.layout();
		currentBindPoint = VK_PIPELINE_BIND_POINT_COMPUTE;
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
			vkCmdBindDescriptorSets(cmd, currentBindPoint,
				currentPipelineLayout, set,
				stack.longs(g.descriptorSetForCurrentFrame()),
				null);
		}
		return this;
	}

	@Override
	public RenderEncoder bindVertexBuffer(int binding, GpuBuffer buffer, long offset)
	{
		GfxGpuBuffer b = (GfxGpuBuffer) buffer;
		try (MemoryStack stack = stackPush())
		{
			vkCmdBindVertexBuffers(cmd, binding, stack.longs(b.handle()), stack.longs(offset));
		}
		return this;
	}

	@Override
	public RenderEncoder bindIndexBuffer(GpuBuffer buffer, long offset)
	{
		GfxGpuBuffer b = (GfxGpuBuffer) buffer;
		vkCmdBindIndexBuffer(cmd, b.handle(), offset, VK_INDEX_TYPE_UINT32);
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

	@Override
	public RenderEncoder drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance)
	{
		vkCmdDrawIndexed(cmd, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
		return this;
	}

	@Override
	public RenderEncoder dispatch(int x, int y, int z)
	{
		vkCmdDispatch(cmd, x, y, z);
		return this;
	}
}
