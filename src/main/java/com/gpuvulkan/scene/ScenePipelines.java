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

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * The scene pipelines and the descriptor set every {@link SceneRenderer} binds.
 * Pipeline state and set contents do not vary per scene, so they are built once
 * per device and shared by the top-level renderer and every sub-worldview.
 */
final class ScenePipelines implements AutoCloseable
{
	private final VulkanDevice device;
	private final Disposables disposables = new Disposables();
	private final ScenePipeline fill;
	private final ScenePipeline alpha;
	private final ScenePipeline skybox;
	private final ScenePipeline priorityColor;
	private final ScenePipeline priorityDepth;
	/** Null when the device lacks fillModeNonSolid, which VK_POLYGON_MODE_LINE requires. */
	private final ScenePipeline line;
	private long descriptorPool = VK_NULL_HANDLE;
	private final long descriptorSet;

	ScenePipelines(VulkanDevice device, RenderPass renderPass, TextureArray textureArray)
	{
		this.device = device;
		int samples = renderPass.samples();
		try
		{
			fill = pipeline(new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL, true, samples, false));
			alpha = pipeline(new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL,
				true, false, true, samples, false, true));
			skybox = pipeline(new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL,
				true, false, true, samples, false, true));
			priorityColor = pipeline(new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL,
				true, false, true, samples, false));
			priorityDepth = pipeline(new ScenePipeline(device, renderPass, VK_POLYGON_MODE_FILL,
				true, true, false, samples, false));
			line = device.supportsFillModeNonSolid()
				? pipeline(new ScenePipeline(device, renderPass, VK_POLYGON_MODE_LINE, true, samples, false))
				: null;

			try (MemoryStack stack = stackPush())
			{
				descriptorPool = createDescriptorPool(stack);
				descriptorSet = allocateAndWriteDescriptorSet(stack, textureArray);
			}
		}
		catch (RuntimeException e)
		{
			close();
			throw e;
		}
	}

	private ScenePipeline pipeline(ScenePipeline p)
	{
		disposables.add(p);
		return p;
	}

	ScenePipeline fill()          { return fill; }
	ScenePipeline alpha()         { return alpha; }
	ScenePipeline skybox()        { return skybox; }
	ScenePipeline priorityColor() { return priorityColor; }
	ScenePipeline priorityDepth() { return priorityDepth; }
	ScenePipeline line()          { return line; }
	long descriptorSet()          { return descriptorSet; }

	// Binding 0 = texture-array sampler, binding 1 = the UV-scroll UBO.
	private long createDescriptorPool(MemoryStack stack)
	{
		VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
		poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
		poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)        .descriptorCount(1);
		VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
			.sType$Default()
			.maxSets(1)
			.pPoolSizes(poolSizes);
		LongBuffer pPool = stack.mallocLong(1);
		Vk.check("vkCreateDescriptorPool (scene)", vkCreateDescriptorPool(device.handle(), poolInfo, null, pPool));
		return pPool.get(0);
	}

	private long allocateAndWriteDescriptorSet(MemoryStack stack, TextureArray textureArray)
	{
		VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
			.sType$Default()
			.descriptorPool(descriptorPool)
			.pSetLayouts(stack.longs(fill.descriptorSetLayout()));
		LongBuffer pSet = stack.mallocLong(1);
		Vk.check("vkAllocateDescriptorSets (scene)", vkAllocateDescriptorSets(device.handle(), allocInfo, pSet));
		long set = pSet.get(0);

		VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
		imgInfo.get(0)
			.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
			.imageView(textureArray.view())
			.sampler(textureArray.sampler());

		VkDescriptorBufferInfo.Buffer animBufInfo = VkDescriptorBufferInfo.calloc(1, stack);
		animBufInfo.get(0)
			.buffer(textureArray.animationUboHandle())
			.offset(0)
			.range(textureArray.animationUboSize());

		VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
		writes.get(0)
			.sType$Default()
			.dstSet(set)
			.dstBinding(0)
			.dstArrayElement(0)
			.descriptorCount(1)
			.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
			.pImageInfo(imgInfo);
		writes.get(1)
			.sType$Default()
			.dstSet(set)
			.dstBinding(1)
			.dstArrayElement(0)
			.descriptorCount(1)
			.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
			.pBufferInfo(animBufInfo);
		vkUpdateDescriptorSets(device.handle(), writes, null);
		return set;
	}

	@Override
	public void close()
	{
		if (descriptorPool != VK_NULL_HANDLE)
		{
			vkDestroyDescriptorPool(device.handle(), descriptorPool, null);
			descriptorPool = VK_NULL_HANDLE;
		}
		disposables.close();
	}
}
