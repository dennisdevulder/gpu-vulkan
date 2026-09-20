/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
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
 * Host-visible vertex buffer (static arena + framesInFlight dynamic arenas)
 * and its descriptor set. Per-frame arenas let the CPU write the next frame
 * while the GPU reads the previous one.
 */
@Slf4j
final class SceneVertexBuffer implements AutoCloseable
{
	private final VulkanDevice device;
	private final Buffer vertexBuffer;
	/** VRAM mirror of the static region; null when the host buffer is already
	 *  device-local (UMA, ReBAR). */
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

		boolean hostIsDeviceLocal =
			(vertexBuffer.memoryPropertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0;
		this.staticMirror = createStaticMirror(hostIsDeviceLocal);
		log.info("Scene vertex buffer: {} MiB, memory flags 0x{} ({}), static mirror {}",
			totalBytes >> 20,
			Integer.toHexString(vertexBuffer.memoryPropertyFlags()),
			hostIsDeviceLocal ? "device-local/ReBAR-UMA" : "host memory",
			staticMirror != null ? (staticBytes >> 20) + " MiB VRAM" : (hostIsDeviceLocal ? "not needed" : "FAILED"));

		try (MemoryStack stack = stackPush())
		{
			descriptorPool = createDescriptorPool(stack);
			descriptorSet = allocateAndWriteDescriptorSet(stack, descriptorSetLayout, textureArray);
		}
	}

	// Without ReBAR the GPU would fetch every static vertex across PCIe each
	// frame — mirror the static region into VRAM and draw statics from there.
	private Buffer createStaticMirror(boolean hostIsDeviceLocal)
	{
		if (hostIsDeviceLocal)
		{
			return null;
		}
		try
		{
			return new Buffer(device, staticBytes,
				VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
				VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		}
		catch (RuntimeException e)
		{
			// VRAM too small for the mirror — statics draw from host
			// memory like before, slower but functional.
			return null;
		}
	}

	// Pool needs slots for both descriptor types: binding 0 = combined image
	// sampler (texture array), binding 1 = uniform buffer (texture animations).
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

	private long allocateAndWriteDescriptorSet(MemoryStack stack, long descriptorSetLayout, TextureArray textureArray)
	{
		VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
			.sType$Default()
			.descriptorPool(descriptorPool)
			.pSetLayouts(stack.longs(descriptorSetLayout));
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
