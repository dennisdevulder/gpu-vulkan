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

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Generic pipeline builder for the gfx layer. Scoped to what migrated
 * consumers need today — no vertex attributes, single colour attachment,
 * blend / depth presets matching {@link RenderPipelineDesc.BlendMode} +
 * {@link RenderPipelineDesc.DepthTest}. Vertex-attribute support comes
 * back when {@link SceneRenderer} migrates.
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

	private long createPipeline(MemoryStack stack, GfxRenderer renderer,
								RenderPipelineDesc desc, long pipelineLayout)
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

		// No vertex attributes: full-screen triangle pattern with
		// gl_VertexIndex picking corners. SceneRenderer's migration will
		// extend the API for real attribute layouts.
		VkPipelineVertexInputStateCreateInfo vertexInput =
			VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();

		int topology = desc.topology() == RenderPipelineDesc.Topology.TRIANGLE_LIST
			? VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
			: VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

		VkPipelineInputAssemblyStateCreateInfo inputAssembly =
			VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
				.sType$Default()
				.topology(topology)
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

		// One colour attachment matching the swapchain's render pass.
		VkPipelineColorBlendAttachmentState.Buffer blendAttachments =
			VkPipelineColorBlendAttachmentState.calloc(1, stack);
		boolean premul = desc.blendMode() == RenderPipelineDesc.BlendMode.PREMUL_ALPHA;
		blendAttachments.get(0)
			.blendEnable(premul)
			.srcColorBlendFactor(premul ? VK_BLEND_FACTOR_ONE : VK_BLEND_FACTOR_ONE)
			.dstColorBlendFactor(premul ? VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA : VK_BLEND_FACTOR_ZERO)
			.colorBlendOp(VK_BLEND_OP_ADD)
			.srcAlphaBlendFactor(premul ? VK_BLEND_FACTOR_ONE : VK_BLEND_FACTOR_ONE)
			.dstAlphaBlendFactor(premul ? VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA : VK_BLEND_FACTOR_ZERO)
			.alphaBlendOp(VK_BLEND_OP_ADD)
			.colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
				| VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);

		VkPipelineColorBlendStateCreateInfo colorBlend =
			VkPipelineColorBlendStateCreateInfo.calloc(stack)
				.sType$Default()
				.logicOpEnable(false)
				.pAttachments(blendAttachments);

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

		VkPipelineDynamicStateCreateInfo dynamic =
			VkPipelineDynamicStateCreateInfo.calloc(stack)
				.sType$Default()
				.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

		VkGraphicsPipelineCreateInfo.Buffer info =
			VkGraphicsPipelineCreateInfo.calloc(1, stack);
		info.get(0)
			.sType$Default()
			.pStages(stages)
			.pVertexInputState(vertexInput)
			.pInputAssemblyState(inputAssembly)
			.pViewportState(viewport)
			.pRasterizationState(raster)
			.pMultisampleState(multisample)
			.pDepthStencilState(depthStencil)
			.pColorBlendState(colorBlend)
			.pDynamicState(dynamic)
			.layout(pipelineLayout)
			.renderPass(renderer.renderPass().handle())
			.subpass(0);

		LongBuffer p = stack.mallocLong(1);
		Vk.check("vkCreateGraphicsPipelines (gfx)",
			vkCreateGraphicsPipelines(device.handle(), VK_NULL_HANDLE, info, null, p));
		return p.get(0);
	}
}
