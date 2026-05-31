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
 * Pipeline for captured scene geometry. Vertex layout is 36 bytes:
 * {@code vec3 pos + vec3 color + vec2 uv + uint texLayer}.
 *
 * <p>Bindings:
 * <ul>
 *   <li>Push constant: 64-byte mat4 MVP at the vertex stage.</li>
 *   <li>Descriptor set 0, binding 0: combined image sampler (the OSRS
 *       texture array — {@link TextureArray}).</li>
 * </ul>
 */
final class ScenePipeline implements AutoCloseable
{
	// 12 × 4 bytes — vec3 position and vec3 color are bound as 4-component
	// formats with a trailing dummy float, because MoltenVK's vec3 attribute
	// alignment handling produces garbage values in the vertex shader on
	// Metal (KhronosGroup/MoltenVK#2182). Padding to vec4-aligned bindings
	// sidesteps the bug — the shader still declares vec3 inputs and Vulkan
	// drops the unused W component, so Linux drivers are unaffected.
	static final int VERTEX_STRIDE = 48;
	static final int OFFSET_POS      = 0;   // vec3, padded to vec4 (16 bytes)
	static final int OFFSET_COLOR    = 16;  // vec3, padded to vec4 (16 bytes)
	static final int OFFSET_LIGHT    = 32;
	static final int OFFSET_UV       = 36;
	static final int OFFSET_TEXLAYER = 44;

	private final VulkanDevice device;
	private final long descriptorSetLayout;
	private final long pipelineLayout;
	private final long pipeline;

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

			ByteBuffer entry = stack.UTF8("main");

			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT)  .module(vertModule).pName(entry);
			stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(entry);

			VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
			binding.get(0).binding(0).stride(VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

			VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(5, stack);
			attrs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(OFFSET_POS);
			attrs.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(OFFSET_COLOR);
			attrs.get(2).binding(0).location(2).format(VK_FORMAT_R32_SFLOAT)         .offset(OFFSET_LIGHT);
			attrs.get(3).binding(0).location(3).format(VK_FORMAT_R32G32_SFLOAT)      .offset(OFFSET_UV);
			attrs.get(4).binding(0).location(4).format(VK_FORMAT_R32_UINT)           .offset(OFFSET_TEXLAYER);

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
					.polygonMode(polygonMode)
					.cullMode(VK_CULL_MODE_NONE)
					.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
					.lineWidth(1.0f);

			VkPipelineMultisampleStateCreateInfo multisample =
				VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
					.rasterizationSamples(samples)
					.minSampleShading(1.0f)
					// Alpha-to-coverage: the GPU emits subsamples in proportion
					// to the fragment's output alpha, so faces with low alpha
					// (high faceTransparencies) effectively dissolve into the
					// background WITHOUT needing a sorted alpha-blend pass.
					// Stock GpuPlugin sorts and blends them in a separate pass;
					// we get a close approximation by writing alpha=1-trans/255
					// from the frag shader and letting MSAA's coverage hardware
					// translate that into per-sample visibility. Stops fire
					// "glow tip" faces (transparency 252+) from rendering as
					// opaque red blobs while still keeping translucent geometry
					// (tent drapes, banners, water surfaces) visible.
					.alphaToCoverageEnable(alphaToCoverage && samples != VK_SAMPLE_COUNT_1_BIT);

			// The dedicated alpha pass forces standard src-over blending with
			// depth writes off, matching stock GPU's transparent-face rule. The
			// normal fill pipeline keeps its historical single-sample fallback.
			boolean blendFallback = forceBlend || samples == VK_SAMPLE_COUNT_1_BIT;
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

			VkPipelineColorBlendStateCreateInfo colorBlend =
				VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
					.pAttachments(blend);

			// Reverse-Z to match OSRS's projection (z_ndc = 2n/z, closer = bigger).
			// Paired with depth-clear value 0.0 in VulkanRenderer.
			VkPipelineDepthStencilStateCreateInfo depth =
				VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
					.depthTestEnable(depthTest)
					.depthWriteEnable(depthWrite)
					.depthCompareOp(VK_COMPARE_OP_GREATER)
					.depthBoundsTestEnable(false)
					.stencilTestEnable(false);

			VkPipelineDynamicStateCreateInfo dynamic =
				VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
					.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

			// Descriptor set 0:
			//   binding 0 = combined image sampler (OSRS texture array, frag)
			//   binding 1 = UBO of per-layer texture-animation vec2's (vert) —
			//               vert shader looks them up to scroll UV per game tick.
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
			descriptorSetLayout = pDsl.get(0);

			// Push constant layout (128 bytes total — at Vulkan's guaranteed minimum):
			//   Vertex   0..63   : mat4 mvp
			//   Vertex   64..79  : vec4 (cameraX, cameraZ, drawDistance, fogDepth)
			//   Vertex   80..95  : ivec4 misc (.x = tick, rest unused/padding for vec4 alignment)
			//   Fragment 96..111 : vec4 (fogR, fogG, fogB, brightness)
			//   Fragment 112..127: vec4 (textureLightMode, _, _, _) — first slot in use,
			//                      rest reserved for future scene-frag uniforms.
			VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(2, stack);
			pc.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT)  .offset(0) .size(96);
			pc.get(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(96).size(32);

			VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pSetLayouts(stack.longs(descriptorSetLayout))
				.pPushConstantRanges(pc);

			LongBuffer pLayout = stack.mallocLong(1);
			if (vkCreatePipelineLayout(device.handle(), layoutInfo, null, pLayout) != VK_SUCCESS)
			{
				vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
				throw new RuntimeException("vkCreatePipelineLayout (scene) failed");
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

			LongBuffer pPipe = stack.mallocLong(1);
			int r = vkCreateGraphicsPipelines(device.handle(), VK_NULL_HANDLE, info, null, pPipe);
			if (r != VK_SUCCESS)
			{
				vkDestroyPipelineLayout(device.handle(), pipelineLayout, null);
				vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
				throw new RuntimeException("vkCreateGraphicsPipelines (scene) failed: " + r);
			}
			pipeline = pPipe.get(0);
		}
		finally
		{
			tmp.close();
		}
	}

	long handle() { return pipeline; }
	long layout() { return pipelineLayout; }
	long descriptorSetLayout() { return descriptorSetLayout; }

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
