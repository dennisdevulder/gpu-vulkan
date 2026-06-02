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
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMappedMemoryRange;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Generic {@code VkBuffer + VkDeviceMemory} wrapper. M4 uses it for host-visible
 * staging (UI pixel upload). Map persistently — saves the per-frame map/unmap
 * round-trip that {@code GpuPlugin}'s {@code glMapBuffer} suffers.
 */
final class Buffer implements AutoCloseable
{
	private final VulkanDevice device;
	private final long handle;
	private final long memory;
	private final long size;
	private final int memoryPropertyFlags;
	private long mappedAddress;
	/** Cached ByteBuffer view over the mapped region. Allocated once in
	 *  {@link #mapPersistent()} so we don't churn a fresh wrapper per write —
	 *  and so callers can't race on the {@code position/limit} state of two
	 *  independently-created views over the same memory. */
	private ByteBuffer mappedView;
	private IntBuffer mappedIntView;

	Buffer(VulkanDevice device, long size, int usage, int memoryProperties)
	{
		this(device, size, usage, memoryProperties, memoryProperties);
	}

	Buffer(VulkanDevice device, long size, int usage, int requiredMemoryProperties, int preferredMemoryProperties)
	{
		Vk.require(size > 0, "buffer size must be positive, got " + size);
		this.device = device;
		this.size = size;
		try (MemoryStack stack = stackPush())
		{
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
				.sType$Default()
				.size(size)
				.usage(usage)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer pBuf = stack.mallocLong(1);
			Vk.check("vkCreateBuffer", vkCreateBuffer(device.handle(), info, null, pBuf));
			handle = pBuf.get(0);

			VkMemoryRequirements memReq = VkMemoryRequirements.calloc(stack);
			vkGetBufferMemoryRequirements(device.handle(), handle, memReq);

			int memType = findMemoryType(device, memReq.memoryTypeBits(), requiredMemoryProperties,
				preferredMemoryProperties, stack);
			memoryPropertyFlags = memoryTypeFlags(device, memType, stack);
			VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
				.sType$Default()
				.allocationSize(memReq.size())
				.memoryTypeIndex(memType);

			LongBuffer pMem = stack.mallocLong(1);
			if (vkAllocateMemory(device.handle(), alloc, null, pMem) != VK_SUCCESS)
			{
				vkDestroyBuffer(device.handle(), handle, null);
				throw new RuntimeException("vkAllocateMemory failed");
			}
			memory = pMem.get(0);
			vkBindBufferMemory(device.handle(), handle, memory, 0);
		}
	}

	long handle()
	{
		return handle;
	}

	long size()
	{
		return size;
	}

	int memoryPropertyFlags()
	{
		return memoryPropertyFlags;
	}

	void mapPersistent()
	{
		try (MemoryStack stack = stackPush())
		{
			PointerBuffer pp = stack.mallocPointer(1);
			Vk.check("vkMapMemory", vkMapMemory(device.handle(), memory, 0, size, 0, pp));
			mappedAddress = pp.get(0);
			mappedView = memByteBuffer(mappedAddress, (int) size);
			mappedIntView = mappedView.asIntBuffer();
		}
	}

	/**
	 * The single cached ByteBuffer view over the mapped region. Callers must
	 * not assume the {@code position/limit} state on entry — reset before
	 * use ({@code rewind()}, {@code clear()}, or build a fresh view via
	 * {@link ByteBuffer#duplicate()} if you need concurrent views).
	 */
	ByteBuffer mappedByteBuffer()
	{
		if (mappedAddress == 0L) throw new IllegalStateException("buffer not mapped");
		return mappedView;
	}

	/** Convenience: copy an int[] (one pixel = one int) into the mapped region. */
	void writeInts(int[] src, int srcOffset, int count)
	{
		writeIntsUnflushed(src, srcOffset, 0, count);
		flushIfNeeded();
	}

	/** Copy an int[] into the mapped region at an int offset. Caller must flush when batching writes. */
	void writeIntsUnflushed(int[] src, int srcOffset, int dstOffset, int count)
	{
		if (mappedAddress == 0L) throw new IllegalStateException("buffer not mapped");
		mappedIntView.clear();
		mappedIntView.position(dstOffset);
		mappedIntView.put(src, srcOffset, count);
	}

	void flushIfNeeded()
	{
		flushRangeIfNeeded(0, size);
	}

	void invalidateIfNeeded()
	{
		invalidateRangeIfNeeded(0, size);
	}

	void invalidateRangeIfNeeded(long offset, long rangeSize)
	{
		if ((memoryPropertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0)
		{
			return;
		}
		if (rangeSize <= 0)
		{
			return;
		}
		long atom = Math.max(1L, device.nonCoherentAtomSize());
		long alignedOffset = offset - (offset % atom);
		long end = Math.min(size, offset + rangeSize);
		long alignedEnd = Math.min(size, ((end + atom - 1L) / atom) * atom);
		long alignedSize = alignedEnd - alignedOffset;
		try (MemoryStack stack = stackPush())
		{
			VkMappedMemoryRange.Buffer range = VkMappedMemoryRange.calloc(1, stack)
				.sType$Default()
				.memory(memory)
				.offset(alignedOffset)
				.size(alignedSize);
			Vk.check("vkInvalidateMappedMemoryRanges", vkInvalidateMappedMemoryRanges(device.handle(), range));
		}
	}

	void flushRangeIfNeeded(long offset, long rangeSize)
	{
		if ((memoryPropertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0)
		{
			return;
		}
		if (rangeSize <= 0)
		{
			return;
		}
		long atom = Math.max(1L, device.nonCoherentAtomSize());
		long alignedOffset = offset - (offset % atom);
		long end = Math.min(size, offset + rangeSize);
		long alignedEnd = Math.min(size, ((end + atom - 1L) / atom) * atom);
		long alignedSize = alignedEnd - alignedOffset;
		try (MemoryStack stack = stackPush())
		{
			VkMappedMemoryRange.Buffer range = VkMappedMemoryRange.calloc(1, stack)
				.sType$Default()
				.memory(memory)
				.offset(alignedOffset)
				.size(alignedSize);
			Vk.check("vkFlushMappedMemoryRanges", vkFlushMappedMemoryRanges(device.handle(), range));
		}
	}

	@Override
	public void close()
	{
		if (mappedAddress != 0L)
		{
			vkUnmapMemory(device.handle(), memory);
			mappedAddress = 0L;
			mappedIntView = null;
			mappedView = null;
		}
		vkDestroyBuffer(device.handle(), handle, null);
		vkFreeMemory(device.handle(), memory, null);
	}

	static int findMemoryType(VulkanDevice device, int typeBits, int properties, MemoryStack stack)
	{
		return findMemoryType(device, typeBits, properties, properties, stack);
	}

	static int findMemoryType(VulkanDevice device, int typeBits, int requiredProperties, int preferredProperties, MemoryStack stack)
	{
		VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
		vkGetPhysicalDeviceMemoryProperties(device.physicalDevice(), memProps);
		for (int i = 0; i < memProps.memoryTypeCount(); i++)
		{
			int flags = memProps.memoryTypes(i).propertyFlags();
			boolean typeOk = (typeBits & (1 << i)) != 0;
			boolean propsOk = (flags & requiredProperties) == requiredProperties;
			boolean preferredOk = (flags & preferredProperties) == preferredProperties;
			if (typeOk && propsOk && preferredOk)
			{
				return i;
			}
		}
		for (int i = 0; i < memProps.memoryTypeCount(); i++)
		{
			int flags = memProps.memoryTypes(i).propertyFlags();
			boolean typeOk = (typeBits & (1 << i)) != 0;
			boolean propsOk = (flags & requiredProperties) == requiredProperties;
			if (typeOk && propsOk)
			{
				return i;
			}
		}
		throw new RuntimeException("No memory type matches typeBits=" + typeBits + " props=" + requiredProperties);
	}

	private static int memoryTypeFlags(VulkanDevice device, int memoryTypeIndex, MemoryStack stack)
	{
		VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
		vkGetPhysicalDeviceMemoryProperties(device.physicalDevice(), memProps);
		return memProps.memoryTypes(memoryTypeIndex).propertyFlags();
	}
}
