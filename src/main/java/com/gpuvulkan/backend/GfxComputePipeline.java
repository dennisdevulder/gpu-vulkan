/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import java.nio.LongBuffer;
import com.gpuvulkan.gfx.BindGroupLayout;
import com.gpuvulkan.gfx.ComputePipeline;
import com.gpuvulkan.gfx.ComputePipelineDesc;
import com.gpuvulkan.gfx.RenderPipelineDesc;
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
