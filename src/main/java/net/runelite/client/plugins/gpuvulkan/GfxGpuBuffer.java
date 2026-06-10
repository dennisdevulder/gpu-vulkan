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
import net.runelite.client.plugins.gpuvulkan.gfx.BufferUsage;
import net.runelite.client.plugins.gpuvulkan.gfx.GpuBuffer;

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
