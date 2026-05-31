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
 * Stock-style scene pipeline using RuneLite GPU's compact zone vertex layout.
 *
 * <p>This is the target pipeline for the performance-parity renderer. It is
 * intentionally not used by {@link SceneRenderer} yet; wiring comes after the
 * uploader can split static opaque/alpha zone geometry correctly.</p>
 */
final class StockScenePipeline implements AutoCloseable
{
	private final VulkanDevice device;
	private final long descriptorSetLayout;
	private final long pipelineLayout;
	private final long pipeline;

	StockScenePipeline(VulkanDevice device, RenderPass renderPass, boolean depthWrite,
					   boolean blend, int samples)
	{
		this.device = device;
		Disposables tmp = new Disposables();
		try (MemoryStack stack = stackPush())
		{
			long vertModule = createShaderModule(loadResource("stock_scene.vert.spv"));
			tmp.add(() -> vkDestroyShaderModule(device.handle(), vertModule, null));
			long fragModule = createShaderModule(loadResource("scene.frag.spv"));
			tmp.add(() -> vkDestroyShaderModule(device.handle(), fragModule, null));

			ByteBuffer entry = stack.UTF8("main");

			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(entry);
			stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(entry);

			VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
			binding.get(0).binding(0).stride(StockSceneVertexLayout.VERTEX_STRIDE)
				.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

			VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(3, stack);
			attrs.get(0).binding(0).location(0).format(VK_FORMAT_R16G16B16_SINT)
				.offset(StockSceneVertexLayout.OFFSET_POS);
			attrs.get(1).binding(0).location(1).format(VK_FORMAT_R32_UINT)
				.offset(StockSceneVertexLayout.OFFSET_ABHSL);
			attrs.get(2).binding(0).location(2).format(VK_FORMAT_R16G16B16A16_SINT)
				.offset(StockSceneVertexLayout.OFFSET_TEX_UV);

			VkPipelineVertexInputStateCreateInfo vertexInput =
				VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
					.pVertexBindingDescriptions(binding)
					.pVertexAttributeDescriptions(attrs);

			VkPipelineInputAssemblyStateCreateInfo inputAssembly =
				VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
					.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
					.primitiveRestartEnable(false);

			VkPipelineViewportStateCreateInfo viewportState =
				VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
					.viewportCount(1)
					.scissorCount(1);

			VkPipelineRasterizationStateCreateInfo raster =
				VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
					.polygonMode(VK_POLYGON_MODE_FILL)
					.cullMode(VK_CULL_MODE_NONE)
					.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
					.lineWidth(1.0f);

			VkPipelineMultisampleStateCreateInfo multisample =
				VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
					.rasterizationSamples(samples)
					.minSampleShading(1.0f)
					.alphaToCoverageEnable(false);

			VkPipelineColorBlendAttachmentState.Buffer colorAttachment =
				VkPipelineColorBlendAttachmentState.calloc(1, stack);
			colorAttachment.get(0)
				.colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
					| VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
				.blendEnable(blend)
				.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
				.dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
				.colorBlendOp(VK_BLEND_OP_ADD)
				.srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
				.dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
				.alphaBlendOp(VK_BLEND_OP_ADD);

			VkPipelineColorBlendStateCreateInfo colorBlend =
				VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
					.pAttachments(colorAttachment);

			VkPipelineDepthStencilStateCreateInfo depth =
				VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
					.depthTestEnable(true)
					.depthWriteEnable(depthWrite)
					.depthCompareOp(VK_COMPARE_OP_GREATER)
					.depthBoundsTestEnable(false)
					.stencilTestEnable(false);

			VkPipelineDynamicStateCreateInfo dynamic =
				VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
					.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

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
			Vk.check("vkCreateDescriptorSetLayout (stock scene)",
				vkCreateDescriptorSetLayout(device.handle(), dsInfo, null, pDsl));
			descriptorSetLayout = pDsl.get(0);

			VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(2, stack);
			pc.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(96);
			pc.get(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(96).size(32);

			VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pSetLayouts(stack.longs(descriptorSetLayout))
				.pPushConstantRanges(pc);

			LongBuffer pLayout = stack.mallocLong(1);
			if (vkCreatePipelineLayout(device.handle(), layoutInfo, null, pLayout) != VK_SUCCESS)
			{
				vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
				throw new RuntimeException("vkCreatePipelineLayout (stock scene) failed");
			}
			pipelineLayout = pLayout.get(0);

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

			LongBuffer pPipeline = stack.mallocLong(1);
			if (vkCreateGraphicsPipelines(device.handle(), VK_NULL_HANDLE, info, null, pPipeline) != VK_SUCCESS)
			{
				vkDestroyPipelineLayout(device.handle(), pipelineLayout, null);
				vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
				throw new RuntimeException("vkCreateGraphicsPipelines (stock scene) failed");
			}
			pipeline = pPipeline.get(0);
		}
		finally
		{
			tmp.close();
		}
	}

	long handle()
	{
		return pipeline;
	}

	long layout()
	{
		return pipelineLayout;
	}

	long descriptorSetLayout()
	{
		return descriptorSetLayout;
	}

	@Override
	public void close()
	{
		vkDestroyPipeline(device.handle(), pipeline, null);
		vkDestroyPipelineLayout(device.handle(), pipelineLayout, null);
		vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
	}

	private long createShaderModule(ByteBuffer code)
	{
		try (MemoryStack stack = stackPush())
		{
			VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
				.sType$Default()
				.pCode(code);
			LongBuffer p = stack.mallocLong(1);
			if (vkCreateShaderModule(device.handle(), info, null, p) != VK_SUCCESS)
			{
				throw new RuntimeException("vkCreateShaderModule (stock scene) failed");
			}
			return p.get(0);
		}
		finally
		{
			memFree(code);
		}
	}

	private static ByteBuffer loadResource(String name)
	{
		String path = "/net/runelite/client/plugins/gpuvulkan/" + name;
		try (InputStream in = StockScenePipeline.class.getResourceAsStream(path))
		{
			if (in == null)
			{
				throw new RuntimeException("Missing shader resource " + path);
			}
			byte[] bytes = in.readAllBytes();
			ByteBuffer buf = memAlloc(bytes.length);
			buf.put(bytes).flip();
			return buf;
		}
		catch (IOException e)
		{
			throw new RuntimeException("Failed to read shader resource " + path, e);
		}
	}
}
