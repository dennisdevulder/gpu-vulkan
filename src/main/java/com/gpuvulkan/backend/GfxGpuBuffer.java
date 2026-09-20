/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import java.nio.ByteBuffer;
import com.gpuvulkan.gfx.BufferUsage;
import com.gpuvulkan.gfx.GpuBuffer;

import static org.lwjgl.vulkan.VK13.*;

final class GfxGpuBuffer implements GpuBuffer
{
	private Buffer buffer;

	GfxGpuBuffer(VulkanDevice device, long size, BufferUsage usage)
	{
		buffer = new Buffer(device, size, vulkanUsage(usage),
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
				| VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		buffer.mapPersistent();
	}

	long handle()
	{
		return buffer.handle();
	}

	@Override
	public long size()
	{
		return buffer.size();
	}

	@Override
	public ByteBuffer mapped()
	{
		return buffer.mappedByteBuffer();
	}

	@Override
	public void flush()
	{
		buffer.flushIfNeeded();
	}

	@Override
	public void close()
	{
		if (buffer != null)
		{
			buffer.close();
			buffer = null;
		}
	}

	private static int vulkanUsage(BufferUsage usage)
	{
		switch (usage)
		{
			case VERTEX:
				return VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
			case INDEX:
				return VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
			case UNIFORM:
				return VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
			case STORAGE:
				return VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
			default:
				throw new IllegalArgumentException("Unhandled buffer usage: " + usage);
		}
	}
}
