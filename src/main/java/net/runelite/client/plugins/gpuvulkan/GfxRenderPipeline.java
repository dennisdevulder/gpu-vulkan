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
import net.runelite.client.plugins.gpuvulkan.gfx.RenderPipeline;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderPipelineDesc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Constraints: single colour attachment, blend / depth presets matching
 * {@link RenderPipelineDesc.BlendMode} + {@link RenderPipelineDesc.DepthTest}.
 * No vertex-buffer declarations = no vertex input (vertex-pulling).
 */
final class GfxRenderPipeline implements RenderPipeline
{
	private final VulkanDevice device;
	private long pipeline;
	private long layout;

	GfxRenderPipeline(GfxRenderer renderer, RenderPipelineDesc desc)
	{
		this.device = renderer.device();
		try (MemoryStack stack = stackPush())
		{
			layout = createLayout(stack, desc);
			try
			{
				pipeline = createPipeline(stack, renderer, desc, layout);
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

	private long createLayout(MemoryStack stack, RenderPipelineDesc desc)
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
		Vk.check("vkCreatePipelineLayout (gfx)",
			vkCreatePipelineLayout(device.handle(), info, null, p));
		return p.get(0);
	}

	// Helpers return stack-allocated structs; the caller's stack frame spans
	// pipeline creation, so every pointer stays live until vkCreate returns.
	private long createPipeline(MemoryStack stack, GfxRenderer renderer,
								RenderPipelineDesc desc, long pipelineLayout)
	{
		VkPipelineInputAssemblyStateCreateInfo inputAssembly =
			VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
				.sType$Default()
				.topology(vulkanTopology(desc.topology()))
				.primitiveRestartEnable(false);

		VkPipelineViewportStateCreateInfo viewport =
			VkPipelineViewportStateCreateInfo.calloc(stack)
				.sType$Default()
				.viewportCount(1)
				.scissorCount(1);

		VkPipelineRasterizationStateCreateInfo raster =
			VkPipelineRasterizationStateCreateInfo.calloc(stack)
				.sType$Default()
				.depthClampEnable(false)
				.rasterizerDiscardEnable(false)
				.polygonMode(VK_POLYGON_MODE_FILL)
				.lineWidth(1.0f)
				.cullMode(VK_CULL_MODE_NONE)
				.frontFace(VK_FRONT_FACE_CLOCKWISE)
				.depthBiasEnable(false);

		VkPipelineMultisampleStateCreateInfo multisample =
			VkPipelineMultisampleStateCreateInfo.calloc(stack)
				.sType$Default()
				.rasterizationSamples(renderer.renderPass().samples())
				.sampleShadingEnable(false);

		VkPipelineDynamicStateCreateInfo dynamic =
			VkPipelineDynamicStateCreateInfo.calloc(stack)
				.sType$Default()
				.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

		VkGraphicsPipelineCreateInfo.Buffer info =
			VkGraphicsPipelineCreateInfo.calloc(1, stack);
		info.get(0)
			.sType$Default()
			.pStages(shaderStages(stack, desc))
			.pVertexInputState(vertexInputState(stack, desc))
			.pInputAssemblyState(inputAssembly)
			.pViewportState(viewport)
			.pRasterizationState(raster)
			.pMultisampleState(multisample)
			.pDepthStencilState(depthStencilState(stack, desc))
			.pColorBlendState(colorBlendState(stack, desc))
			.pDynamicState(dynamic)
			.layout(pipelineLayout)
			.renderPass(renderer.renderPass().handle())
			.subpass(0);

		LongBuffer p = stack.mallocLong(1);
		Vk.check("vkCreateGraphicsPipelines (gfx)",
			vkCreateGraphicsPipelines(device.handle(), VK_NULL_HANDLE, info, null, p));
		return p.get(0);
	}

	private static VkPipelineShaderStageCreateInfo.Buffer shaderStages(MemoryStack stack, RenderPipelineDesc desc)
	{
		VkPipelineShaderStageCreateInfo.Buffer stages =
			VkPipelineShaderStageCreateInfo.calloc(2, stack);
		stages.get(0)
			.sType$Default()
			.stage(VK_SHADER_STAGE_VERTEX_BIT)
			.module(((GfxShaderModule) desc.vertex()).handle())
			.pName(stack.UTF8("main"));
		stages.get(1)
			.sType$Default()
			.stage(VK_SHADER_STAGE_FRAGMENT_BIT)
			.module(((GfxShaderModule) desc.fragment()).handle())
			.pName(stack.UTF8("main"));
		return stages;
	}

	private static VkPipelineVertexInputStateCreateInfo vertexInputState(MemoryStack stack, RenderPipelineDesc desc)
	{
		VkPipelineVertexInputStateCreateInfo vertexInput =
			VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
		if (desc.vertexBuffers().isEmpty())
		{
			return vertexInput;
		}
		VkVertexInputBindingDescription.Buffer bindings =
			VkVertexInputBindingDescription.calloc(desc.vertexBuffers().size(), stack);
		for (int i = 0; i < desc.vertexBuffers().size(); i++)
		{
			RenderPipelineDesc.VertexBufferBinding b = desc.vertexBuffers().get(i);
			bindings.get(i)
				.binding(b.binding)
				.stride(b.stride)
				.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
		}
		VkVertexInputAttributeDescription.Buffer attributes =
			VkVertexInputAttributeDescription.calloc(desc.vertexAttributes().size(), stack);
		for (int i = 0; i < desc.vertexAttributes().size(); i++)
		{
			RenderPipelineDesc.VertexAttribute a = desc.vertexAttributes().get(i);
			attributes.get(i)
				.location(a.location)
				.binding(a.binding)
				.format(vulkanFormat(a.format))
				.offset(a.offset);
		}
		return vertexInput
			.pVertexBindingDescriptions(bindings)
			.pVertexAttributeDescriptions(attributes);
	}

	private static VkPipelineColorBlendStateCreateInfo colorBlendState(MemoryStack stack, RenderPipelineDesc desc)
	{
		VkPipelineColorBlendAttachmentState.Buffer blendAttachments =
			VkPipelineColorBlendAttachmentState.calloc(1, stack);
		boolean premul = desc.blendMode() == RenderPipelineDesc.BlendMode.PREMUL_ALPHA;
		blendAttachments.get(0)
			.blendEnable(premul)
			.srcColorBlendFactor(VK_BLEND_FACTOR_ONE)
			.dstColorBlendFactor(premul ? VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA : VK_BLEND_FACTOR_ZERO)
			.colorBlendOp(VK_BLEND_OP_ADD)
			.srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
			.dstAlphaBlendFactor(premul ? VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA : VK_BLEND_FACTOR_ZERO)
			.alphaBlendOp(VK_BLEND_OP_ADD)
			.colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
				| VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);

		return VkPipelineColorBlendStateCreateInfo.calloc(stack)
			.sType$Default()
			.logicOpEnable(false)
			.pAttachments(blendAttachments);
	}

	private static VkPipelineDepthStencilStateCreateInfo depthStencilState(MemoryStack stack, RenderPipelineDesc desc)
	{
		VkPipelineDepthStencilStateCreateInfo depthStencil =
			VkPipelineDepthStencilStateCreateInfo.calloc(stack)
				.sType$Default();
		if (desc.depthTest() == RenderPipelineDesc.DepthTest.REVERSE_Z)
		{
			depthStencil
				.depthTestEnable(true)
				.depthWriteEnable(true)
				.depthCompareOp(VK_COMPARE_OP_GREATER)
				.depthBoundsTestEnable(false)
				.stencilTestEnable(false);
		}
		else
		{
			depthStencil
				.depthTestEnable(false)
				.depthWriteEnable(false)
				.stencilTestEnable(false);
		}
		return depthStencil;
	}

	private static int vulkanTopology(RenderPipelineDesc.Topology topology)
	{
		switch (topology)
		{
			case TRIANGLE_LIST:
				return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
			default:
				throw new IllegalArgumentException("Unsupported topology: " + topology);
		}
	}

	private static int vulkanFormat(RenderPipelineDesc.AttributeFormat format)
	{
		switch (format)
		{
			case FLOAT:
				return VK_FORMAT_R32_SFLOAT;
			case FLOAT2:
				return VK_FORMAT_R32G32_SFLOAT;
			case FLOAT3:
				return VK_FORMAT_R32G32B32_SFLOAT;
			case FLOAT4:
				return VK_FORMAT_R32G32B32A32_SFLOAT;
			case INT:
				return VK_FORMAT_R32_SINT;
			case UINT:
				return VK_FORMAT_R32_UINT;
			case UBYTE4_NORM:
				return VK_FORMAT_R8G8B8A8_UNORM;
			default:
				throw new IllegalArgumentException("Unhandled attribute format: " + format);
		}
	}
}
