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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * Owns the scene's host-visible vertex buffer and its descriptor set. The
 * buffer contains one static scene arena followed by {@code framesInFlight}
 * dynamic/overlay arenas. The CPU can write the next frame's dynamic geometry
 * while the GPU is still reading the previous frame's arena. Static scene
 * geometry lives once at the start of the buffer and is drawn with a zero
 * first-vertex base.
 *
 * <p>The descriptor set has two bindings: 0 = OSRS texture array (combined
 * image sampler), 1 = texture-animation UBO. Both are static for the plugin
 * lifetime — animation parameters are written into the UBO each frame, but
 * the binding itself does not change.
 */
final class SceneVertexBuffer implements AutoCloseable
{
	private final VulkanDevice device;
	private final Buffer vertexBuffer;
	/** Device-local mirror of the static region, uploaded after capture.
	 *  Null when the host buffer already landed in device-local memory
	 *  (UMA, ReBAR) — there a mirror would just duplicate VRAM. */
	private final Buffer staticMirror;
	private final ByteBuffer mapped;
	private final long slotBytes;
	private final long staticBytes;
	private final long descriptorPool;
	private final long descriptorSet;

	SceneVertexBuffer(VulkanDevice device, long totalBytes, long slotBytes,
		long descriptorSetLayout, TextureArray textureArray)
	{
		this.device = device;
		this.slotBytes = slotBytes;
		this.staticBytes = totalBytes - slotBytes * FrameSync.FRAMES_IN_FLIGHT;
		// Prefer BAR/ReBAR memory — on discrete cards plain host memory puts
		// every per-frame vertex fetch across PCIe.
		this.vertexBuffer = new Buffer(device, totalBytes,
			VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
			VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
				| VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		vertexBuffer.mapPersistent();
		this.mapped = vertexBuffer.mappedByteBuffer().order(ByteOrder.nativeOrder());

		// Without ReBAR the big allocation falls back to plain host memory and
		// the GPU would fetch every static vertex across PCIe each frame —
		// mirror the static region into VRAM and draw statics from there.
		boolean hostIsDeviceLocal =
			(vertexBuffer.memoryPropertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0;
		Buffer mirror = null;
		if (!hostIsDeviceLocal)
		{
			try
			{
				mirror = new Buffer(device, staticBytes,
					VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
					VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
			}
			catch (RuntimeException e)
			{
				// VRAM too small for the mirror — statics draw from host
				// memory like before, slower but functional.
			}
		}
		this.staticMirror = mirror;

		try (MemoryStack stack = stackPush())
		{
			// Pool needs slots for both descriptor types:
			//   binding 0 = combined image sampler (texture array)
			//   binding 1 = uniform buffer (texture animations)
			VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
			poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
			poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)        .descriptorCount(1);
			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
				.sType$Default()
				.maxSets(1)
				.pPoolSizes(poolSizes);
			LongBuffer pPool = stack.mallocLong(1);
			Vk.check("vkCreateDescriptorPool (scene)", vkCreateDescriptorPool(device.handle(), poolInfo, null, pPool));
			descriptorPool = pPool.get(0);

			VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
				.sType$Default()
				.descriptorPool(descriptorPool)
				.pSetLayouts(stack.longs(descriptorSetLayout));
			LongBuffer pSet = stack.mallocLong(1);
			Vk.check("vkAllocateDescriptorSets (scene)", vkAllocateDescriptorSets(device.handle(), allocInfo, pSet));
			descriptorSet = pSet.get(0);

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
				.dstSet(descriptorSet)
				.dstBinding(0)
				.dstArrayElement(0)
				.descriptorCount(1)
				.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(imgInfo);
			writes.get(1)
				.sType$Default()
				.dstSet(descriptorSet)
				.dstBinding(1)
				.dstArrayElement(0)
				.descriptorCount(1)
				.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
				.pBufferInfo(animBufInfo);
			vkUpdateDescriptorSets(device.handle(), writes, null);
		}
	}

	long handle() { return vertexBuffer.handle(); }
	ByteBuffer mapped() { return mapped; }
	long slotBytes() { return slotBytes; }
	long descriptorSet() { return descriptorSet; }

	/** Buffer static-region draws should bind: the VRAM mirror when one
	 *  exists, otherwise the host buffer (same offsets either way). */
	long staticDrawHandle()
	{
		return staticMirror != null ? staticMirror.handle() : vertexBuffer.handle();
	}

	boolean hasStaticMirror()
	{
		return staticMirror != null;
	}

	long staticMirrorHandle()
	{
		return staticMirror != null ? staticMirror.handle() : VK_NULL_HANDLE;
	}

	long staticBytes()
	{
		return staticBytes;
	}

	@Override
	public void close()
	{
		// vkDestroyDescriptorPool implicitly frees all sets allocated from it.
		vkDestroyDescriptorPool(device.handle(), descriptorPool, null);
		if (staticMirror != null)
		{
			staticMirror.close();
		}
		vertexBuffer.close();
	}
}
