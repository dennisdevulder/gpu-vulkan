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
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroup;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupDesc;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupLayoutDesc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Concrete bind group. Holds {@code FRAMES_IN_FLIGHT} VkDescriptorSets
 * (one per ring slot) so that a single logical {@code BindGroup}
 * referencing a {@link GfxStreamingImage} dispatches to the right slot's
 * texture automatically. For non-streaming resources every slot would
 * have the same descriptor write — wasteful, but the layer's only
 * resource kind today IS streaming, so the cost is paid where it matters.
 */
final class GfxBindGroup implements BindGroup
{
	private final VulkanDevice device;
	private final long pool;
	private final long[] sets = new long[FrameSync.FRAMES_IN_FLIGHT];
	private final FrameSync frameSync;

	GfxBindGroup(GfxRenderer renderer, BindGroupDesc desc)
	{
		this.device = renderer.device();
		this.frameSync = renderer.frameSync();

		GfxBindGroupLayout layout = (GfxBindGroupLayout) desc.layout();
		try (MemoryStack stack = stackPush())
		{
			pool = createPool(stack, layout.desc());
			allocateSets(stack, pool, layout.handle());
			writeStreamingImages(stack, desc);
		}
	}

	long descriptorSetForCurrentFrame()
	{
		return sets[frameSync.currentFrame()];
	}

	private long createPool(MemoryStack stack, BindGroupLayoutDesc layoutDesc)
	{
		// One pool entry per binding kind in the layout, multiplied by
		// FRAMES_IN_FLIGHT (one descriptor per slot per binding).
		int kinds = layoutDesc.entries().size();
		VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(kinds, stack);
		for (int i = 0; i < kinds; i++)
		{
			BindGroupLayoutDesc.Entry e = layoutDesc.entries().get(i);
			sizes.get(i)
				.type(vulkanDescriptorType(e.kind))
				.descriptorCount(FrameSync.FRAMES_IN_FLIGHT);
		}
		VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
			.sType$Default()
			.maxSets(FrameSync.FRAMES_IN_FLIGHT)
			.pPoolSizes(sizes);
		LongBuffer p = stack.mallocLong(1);
		Vk.check("vkCreateDescriptorPool (gfx bind group)",
			vkCreateDescriptorPool(device.handle(), info, null, p));
		return p.get(0);
	}

	private void allocateSets(MemoryStack stack, long pool, long layoutHandle)
	{
		LongBuffer layouts = stack.mallocLong(FrameSync.FRAMES_IN_FLIGHT);
		for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
		{
			layouts.put(i, layoutHandle);
		}
		VkDescriptorSetAllocateInfo alloc = VkDescriptorSetAllocateInfo.calloc(stack)
			.sType$Default()
			.descriptorPool(pool)
			.pSetLayouts(layouts);
		LongBuffer pSets = stack.mallocLong(FrameSync.FRAMES_IN_FLIGHT);
		Vk.check("vkAllocateDescriptorSets (gfx bind group)",
			vkAllocateDescriptorSets(device.handle(), alloc, pSets));
		for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
		{
			sets[i] = pSets.get(i);
		}
	}

	private void writeStreamingImages(MemoryStack stack, BindGroupDesc desc)
	{
		if (desc.streamingImages().isEmpty()) return;

		// One descriptor write per (slot, streaming-image entry). Each
		// slot's set points at the corresponding slot's texture in the
		// streaming image's ring, so binding the current-frame set
		// transparently samples from the right slot.
		int total = desc.streamingImages().size() * FrameSync.FRAMES_IN_FLIGHT;
		VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(total, stack);
		int w = 0;
		for (BindGroupDesc.StreamingImageEntry entry : desc.streamingImages())
		{
			GfxStreamingImage si = (GfxStreamingImage) entry.image;
			for (int slot = 0; slot < FrameSync.FRAMES_IN_FLIGHT; slot++)
			{
				VkDescriptorImageInfo.Buffer imgInfo =
					VkDescriptorImageInfo.calloc(1, stack);
				imgInfo.get(0)
					.sampler(si.samplerForSlot(slot))
					.imageView(si.viewForSlot(slot))
					.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

				writes.get(w++)
					.sType$Default()
					.dstSet(sets[slot])
					.dstBinding(entry.binding)
					.dstArrayElement(0)
					.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
					.descriptorCount(1)
					.pImageInfo(imgInfo);
			}
		}
		vkUpdateDescriptorSets(device.handle(), writes, null);
	}

	private static int vulkanDescriptorType(BindGroupLayoutDesc.BindingKind kind)
	{
		switch (kind)
		{
			case COMBINED_IMAGE_SAMPLER:
				return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
			default:
				throw new IllegalArgumentException("Unhandled binding kind: " + kind);
		}
	}

	@Override
	public void close()
	{
		if (pool != VK_NULL_HANDLE)
		{
			vkDestroyDescriptorPool(device.handle(), pool, null);
		}
	}
}
