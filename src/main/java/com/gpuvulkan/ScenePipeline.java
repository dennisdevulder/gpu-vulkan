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
package com.gpuvulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
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
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Pipeline for captured scene geometry. Vertex layout is 24 bytes:
 * {@code float3 position + uint alpha/bias/hsl + short4 texture/u/v}.
 */
final class ScenePipeline implements AutoCloseable
{
	// float3 position, uint [alpha:8 | bias:8 | hsl:16], short4 texture/u/v.
	// Positions stay float — packing reintroduces close-zoom vertex snapping.
	static final int VERTEX_STRIDE = 24;
	static final int OFFSET_POS      = 0;
	static final int OFFSET_ABHSL    = 12;
	static final int OFFSET_TEX_UV   = 16;

	private final VulkanDevice device;
	private long descriptorSetLayout;
	private long pipelineLayout;
	private long pipeline;

	ScenePipeline(VulkanDevice device, RenderPass renderPass, int polygonMode, boolean depthTest,
				  int samples, boolean alphaToCoverage)
	{
		this(device, renderPass, polygonMode, depthTest, depthTest, true, samples, alphaToCoverage);
	}

	ScenePipeline(VulkanDevice device, RenderPass renderPass, int polygonMode, boolean depthTest,
				  boolean depthWrite, boolean colorWrite, int samples, boolean alphaToCoverage)
	{
		this(device, renderPass, polygonMode, depthTest, depthWrite, colorWrite, samples, alphaToCoverage, false);
	}

	ScenePipeline(VulkanDevice device, RenderPass renderPass, int polygonMode, boolean depthTest,
				  boolean depthWrite, boolean colorWrite, int samples, boolean alphaToCoverage,
				  boolean forceBlend)
	{
		this.device = device;
		Disposables tmp = new Disposables();
		try (MemoryStack stack = stackPush())
		{
			long vertModule = createShaderModule(loadResource("scene.vert.spv"));
			tmp.add(() -> vkDestroyShaderModule(device.handle(), vertModule, null));
			long fragModule = createShaderModule(loadResource("scene.frag.spv"));
			tmp.add(() -> vkDestroyShaderModule(device.handle(), fragModule, null));

			VkPipelineShaderStageCreateInfo.Buffer stages = buildShaderStages(stack, vertModule, fragModule);
			VkPipelineVertexInputStateCreateInfo vertexInput = buildVertexInput(stack);
			VkPipelineRasterizationStateCreateInfo raster = buildRasterizationState(stack, polygonMode, forceBlend);
			VkPipelineMultisampleStateCreateInfo multisample = buildMultisampleState(stack, samples, alphaToCoverage);

			// The alpha pass forces src-over blending with depth writes off; the
			// normal fill pipeline keeps its single-sample fallback.
			boolean blendFallback = forceBlend || samples == VK_SAMPLE_COUNT_1_BIT;
			VkPipelineColorBlendStateCreateInfo colorBlend = buildColorBlendState(stack, colorWrite, blendFallback);
			VkPipelineDepthStencilStateCreateInfo depth = buildDepthStencilState(stack, depthTest, depthWrite);

			descriptorSetLayout = createDescriptorSetLayout(stack);
			pipelineLayout = createPipelineLayout(stack);
			pipeline = createGraphicsPipeline(stack, stages, vertexInput, raster, multisample,
				colorBlend, depth, renderPass);
		}
		finally
		{
			tmp.close();
		}
	}

	private static VkPipelineShaderStageCreateInfo.Buffer buildShaderStages(MemoryStack stack,
		long vertModule, long fragModule)
	{
		ByteBuffer entry = stack.UTF8("main");

		VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
		stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT)  .module(vertModule).pName(entry);
		stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(entry);
		return stages;
	}

	private static VkPipelineVertexInputStateCreateInfo buildVertexInput(MemoryStack stack)
	{
		VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
		binding.get(0).binding(0).stride(VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

		VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(3, stack);
		attrs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(OFFSET_POS);
		attrs.get(1).binding(0).location(1).format(VK_FORMAT_R32_UINT).offset(OFFSET_ABHSL);
		attrs.get(2).binding(0).location(2).format(VK_FORMAT_R16G16B16A16_SINT).offset(OFFSET_TEX_UV);

		return VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
			.pVertexBindingDescriptions(binding)
			.pVertexAttributeDescriptions(attrs);
	}

	private static VkPipelineRasterizationStateCreateInfo buildRasterizationState(MemoryStack stack,
		int polygonMode, boolean forceBlend)
	{
		return VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
			.polygonMode(polygonMode)
			.cullMode(polygonMode == VK_POLYGON_MODE_FILL && !forceBlend ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
			.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
			.lineWidth(1.0f);
	}

	private static VkPipelineMultisampleStateCreateInfo buildMultisampleState(MemoryStack stack,
		int samples, boolean alphaToCoverage)
	{
		return VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
			.rasterizationSamples(samples)
			.minSampleShading(1.0f)
			// Alpha-to-coverage approximates sorted alpha blending via MSAA coverage;
			// keeps high-transparency "glow tip" faces from rendering opaque.
			.alphaToCoverageEnable(alphaToCoverage && samples != VK_SAMPLE_COUNT_1_BIT);
	}

	private static VkPipelineColorBlendStateCreateInfo buildColorBlendState(MemoryStack stack,
		boolean colorWrite, boolean blendFallback)
	{
		VkPipelineColorBlendAttachmentState.Buffer blend =
			VkPipelineColorBlendAttachmentState.calloc(1, stack);
		blend.get(0)
			.colorWriteMask(colorWrite
				? VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
					| VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT
				: 0)
			.blendEnable(blendFallback)
			.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
			.dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
			.colorBlendOp(VK_BLEND_OP_ADD)
			.srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
			.dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
			.alphaBlendOp(VK_BLEND_OP_ADD);

		return VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
			.pAttachments(blend);
	}

	private static VkPipelineDepthStencilStateCreateInfo buildDepthStencilState(MemoryStack stack,
		boolean depthTest, boolean depthWrite)
	{
		// Reverse-Z to match OSRS's projection (z_ndc = 2n/z, closer = bigger).
		// Paired with depth-clear value 0.0 in VulkanRenderer.
		return VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
			.depthTestEnable(depthTest)
			.depthWriteEnable(depthWrite)
			.depthCompareOp(VK_COMPARE_OP_GREATER)
			.depthBoundsTestEnable(false)
			.stencilTestEnable(false);
	}

	private long createDescriptorSetLayout(MemoryStack stack)
	{
		// Set 0: binding 0 = texture-array sampler (frag);
		// binding 1 = per-layer UV-scroll UBO (vert).
		VkDescriptorSetLayoutBinding.Buffer dsBindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
		dsBindings.get(0)
			.binding(0)
			.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
			.descriptorCount(1)
			.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
		dsBindings.get(1)
			.binding(1)
			.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
			.descriptorCount(1)
			.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
		VkDescriptorSetLayoutCreateInfo dsInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
			.sType$Default()
			.pBindings(dsBindings);
		LongBuffer pDsl = stack.mallocLong(1);
		Vk.check("vkCreateDescriptorSetLayout (scene)", vkCreateDescriptorSetLayout(device.handle(), dsInfo, null, pDsl));
		return pDsl.get(0);
	}

	private long createPipelineLayout(MemoryStack stack)
	{
		// Push constant layout (128 bytes total — Vulkan's guaranteed minimum):
		//   Vertex   0..63 mat4 mvp; 64..79 vec4 (cameraX, cameraZ, drawDistance, fogDepth);
		//   Vertex   80..95 ivec4 misc (.x = tick); Fragment 96..111 vec4 (fogRGB, brightness);
		//   Fragment 112..127 vec4 (textureLightMode, _, _, _).
		VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(2, stack);
		pc.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT)  .offset(0) .size(96);
		pc.get(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(96).size(32);

		VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
			.sType$Default()
			.pSetLayouts(stack.longs(descriptorSetLayout))
			.pPushConstantRanges(pc);

		LongBuffer pLayout = stack.mallocLong(1);
		int layoutResult = vkCreatePipelineLayout(device.handle(), layoutInfo, null, pLayout);
		if (layoutResult != VK_SUCCESS)
		{
			vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
			throw Vk.fail("vkCreatePipelineLayout (scene)", layoutResult);
		}
		return pLayout.get(0);
	}

	private long createGraphicsPipeline(MemoryStack stack,
		VkPipelineShaderStageCreateInfo.Buffer stages,
		VkPipelineVertexInputStateCreateInfo vertexInput,
		VkPipelineRasterizationStateCreateInfo raster,
		VkPipelineMultisampleStateCreateInfo multisample,
		VkPipelineColorBlendStateCreateInfo colorBlend,
		VkPipelineDepthStencilStateCreateInfo depth,
		RenderPass renderPass)
	{
		VkPipelineInputAssemblyStateCreateInfo inputAssembly =
			VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
				.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
				.primitiveRestartEnable(false);

		VkPipelineViewportStateCreateInfo viewportState =
			VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
				.viewportCount(1)
				.scissorCount(1);

		VkPipelineDynamicStateCreateInfo dynamic =
			VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
				.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

		VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
		info.get(0).sType$Default()
			.pStages(stages)
			.pVertexInputState(vertexInput)
			.pInputAssemblyState(inputAssembly)
			.pViewportState(viewportState)
			.pRasterizationState(raster)
			.pMultisampleState(multisample)
			.pColorBlendState(colorBlend)
			.pDepthStencilState(depth)
			.pDynamicState(dynamic)
			.layout(pipelineLayout)
			.renderPass(renderPass.handle())
			.subpass(0);

		LongBuffer pPipe = stack.mallocLong(1);
		int r = vkCreateGraphicsPipelines(device.handle(), VK_NULL_HANDLE, info, null, pPipe);
		if (r != VK_SUCCESS)
		{
			vkDestroyPipelineLayout(device.handle(), pipelineLayout, null);
			vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
			throw Vk.fail("vkCreateGraphicsPipelines (scene)", r);
		}
		return pPipe.get(0);
	}

	long handle() { return pipeline; }
	long layout() { return pipelineLayout; }
	long descriptorSetLayout() { return descriptorSetLayout; }

	@Override
	public void close()
	{
		if (pipeline != VK_NULL_HANDLE)
		{
			vkDestroyPipeline(device.handle(), pipeline, null);
			pipeline = VK_NULL_HANDLE;
		}
		if (pipelineLayout != VK_NULL_HANDLE)
		{
			vkDestroyPipelineLayout(device.handle(), pipelineLayout, null);
			pipelineLayout = VK_NULL_HANDLE;
		}
		if (descriptorSetLayout != VK_NULL_HANDLE)
		{
			vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
			descriptorSetLayout = VK_NULL_HANDLE;
		}
	}

	private long createShaderModule(ByteBuffer code)
	{
		try (MemoryStack stack = stackPush())
		{
			VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
				.sType$Default()
				.pCode(code);
			LongBuffer p = stack.mallocLong(1);
			Vk.check("vkCreateShaderModule", vkCreateShaderModule(device.handle(), info, null, p));
			return p.get(0);
		}
		finally
		{
			memFree(code);
		}
	}

	private static ByteBuffer loadResource(String resource)
	{
		try (InputStream in = ScenePipeline.class.getResourceAsStream(resource))
		{
			if (in == null) throw new RuntimeException("missing resource: " + resource);
			byte[] bytes = in.readAllBytes();
			ByteBuffer buf = memAlloc(bytes.length);
			buf.put(bytes).flip();
			return buf;
		}
		catch (IOException e)
		{
			throw new RuntimeException("failed to read " + resource, e);
		}
	}
}
