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
package net.runelite.client.plugins.gpuvulkan;

import java.nio.LongBuffer;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupLayout;
import net.runelite.client.plugins.gpuvulkan.gfx.ComputePipeline;
import net.runelite.client.plugins.gpuvulkan.gfx.ComputePipelineDesc;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderPipelineDesc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

final class GfxComputePipeline implements ComputePipeline
{
	private final VulkanDevice device;
	private long pipeline;
	private long layout;

	GfxComputePipeline(GfxRenderer renderer, ComputePipelineDesc desc)
	{
		this.device = renderer.device();
		try (MemoryStack stack = stackPush())
		{
			layout = createLayout(stack, desc);
			try
			{
				pipeline = createPipeline(stack, desc, layout);
			}
			catch (RuntimeException e)
			{
				vkDestroyPipelineLayout(device.handle(), layout, null);
				throw e;
			}
		}
	}

	long handle() { return pipeline; }
	long layout() { return layout; }

	@Override
	public void close()
	{
		if (pipeline != VK_NULL_HANDLE)
		{
			vkDestroyPipeline(device.handle(), pipeline, null);
			pipeline = VK_NULL_HANDLE;
		}
		if (layout != VK_NULL_HANDLE)
		{
			vkDestroyPipelineLayout(device.handle(), layout, null);
			layout = VK_NULL_HANDLE;
		}
	}

	private long createLayout(MemoryStack stack, ComputePipelineDesc desc)
	{
		LongBuffer setLayouts = null;
		if (!desc.bindGroupLayouts().isEmpty())
		{
			setLayouts = stack.mallocLong(desc.bindGroupLayouts().size());
			for (int i = 0; i < desc.bindGroupLayouts().size(); i++)
			{
				BindGroupLayout bgl = desc.bindGroupLayouts().get(i);
				setLayouts.put(i, ((GfxBindGroupLayout) bgl).handle());
			}
		}

		VkPushConstantRange.Buffer pcr = null;
		if (!desc.pushConstants().isEmpty())
		{
			pcr = VkPushConstantRange.calloc(desc.pushConstants().size(), stack);
			for (int i = 0; i < desc.pushConstants().size(); i++)
			{
				RenderPipelineDesc.PushConstantRange r = desc.pushConstants().get(i);
				pcr.get(i).stageFlags(r.stages).offset(r.offset).size(r.size);
			}
		}

		VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
			.sType$Default()
			.pSetLayouts(setLayouts)
			.pPushConstantRanges(pcr);

		LongBuffer p = stack.mallocLong(1);
		Vk.check("vkCreatePipelineLayout (gfx compute)",
			vkCreatePipelineLayout(device.handle(), info, null, p));
		return p.get(0);
	}

	private long createPipeline(MemoryStack stack, ComputePipelineDesc desc, long pipelineLayout)
	{
		VkComputePipelineCreateInfo.Buffer info =
			VkComputePipelineCreateInfo.calloc(1, stack);
		info.get(0)
			.sType$Default()
			.layout(pipelineLayout);
		info.get(0).stage()
			.sType$Default()
			.stage(VK_SHADER_STAGE_COMPUTE_BIT)
			.module(((GfxShaderModule) desc.compute()).handle())
			.pName(stack.UTF8("main"));

		LongBuffer p = stack.mallocLong(1);
		Vk.check("vkCreateComputePipelines (gfx)",
			vkCreateComputePipelines(device.handle(), VK_NULL_HANDLE, info, null, p));
		return p.get(0);
	}
}
