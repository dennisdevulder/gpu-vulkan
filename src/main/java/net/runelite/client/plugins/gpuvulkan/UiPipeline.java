package net.runelite.client.plugins.gpuvulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
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
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK13.*;

/**
 * UI fullscreen-quad pipeline. Differs from {@link Pipeline} in two ways:
 * a single combined-image-sampler descriptor in the pipeline layout, and
 * alpha blending enabled (so the UI composites on top of the cleared canvas
 * + scene draws). Vertex input still empty — ui.vert manufactures three
 * vertices from {@code gl_VertexIndex}.
 */
final class UiPipeline implements AutoCloseable
{
	private final VulkanDevice device;
	private final long pipelineLayout;
	private final long pipeline;

	UiPipeline(VulkanDevice device, RenderPass renderPass, Descriptors descriptors, int samples)
	{
		this.device = device;
		Disposables tmp = new Disposables();
		try (MemoryStack stack = stackPush())
		{
			long vertModule = createShaderModule(loadResource("ui.vert.spv"));
			tmp.add(() -> vkDestroyShaderModule(device.handle(), vertModule, null));
			long fragModule = createShaderModule(loadResource("ui.frag.spv"));
			tmp.add(() -> vkDestroyShaderModule(device.handle(), fragModule, null));

			ByteBuffer entry = stack.UTF8("main");

			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT)  .module(vertModule).pName(entry);
			stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(entry);

			VkPipelineVertexInputStateCreateInfo vertexInput =
				VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();

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
					.minSampleShading(1.0f);

			// Pre-multiplied alpha: out = src + dst * (1 - src.a). Matches what the
			// stock GpuPlugin uses (glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)). The
			// OSRS UI ships pre-multiplied; the standard SRC_ALPHA factor would
			// multiply alpha twice and fringe text edges.
			VkPipelineColorBlendAttachmentState.Buffer blend =
				VkPipelineColorBlendAttachmentState.calloc(1, stack);
			blend.get(0)
				.colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
					| VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
				.blendEnable(true)
				.srcColorBlendFactor(VK_BLEND_FACTOR_ONE)
				.dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
				.colorBlendOp(VK_BLEND_OP_ADD)
				.srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
				.dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
				.alphaBlendOp(VK_BLEND_OP_ADD);

			VkPipelineColorBlendStateCreateInfo colorBlend =
				VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
					.pAttachments(blend);

			// UI must always show on top — no depth test, no depth write.
			// The depth attachment exists in the render pass; we just opt out.
			VkPipelineDepthStencilStateCreateInfo depth =
				VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
					.depthTestEnable(false)
					.depthWriteEnable(false);

			VkPipelineDynamicStateCreateInfo dynamic =
				VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
					.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

			VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pSetLayouts(stack.longs(descriptors.layout()));
			LongBuffer pLayout = stack.mallocLong(1);
			if (vkCreatePipelineLayout(device.handle(), layoutInfo, null, pLayout) != VK_SUCCESS)
			{
				throw new RuntimeException("vkCreatePipelineLayout failed");
			}
			this.pipelineLayout = pLayout.get(0);

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
				throw new RuntimeException("vkCreateGraphicsPipelines (UI) failed: " + r);
			}
			this.pipeline = pPipe.get(0);
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

	@Override
	public void close()
	{
		vkDestroyPipeline(device.handle(), pipeline, null);
		vkDestroyPipelineLayout(device.handle(), pipelineLayout, null);
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
				throw new RuntimeException("vkCreateShaderModule failed");
			}
			return p.get(0);
		}
		finally
		{
			memFree(code);
		}
	}

	private static ByteBuffer loadResource(String resource)
	{
		try (InputStream in = UiPipeline.class.getResourceAsStream(resource))
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
