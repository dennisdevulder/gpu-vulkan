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

import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK13.*;

/**
 * Host-visible vertex buffer: static arena followed by framesInFlight dynamic
 * arenas, so the CPU can write the next frame while the GPU reads the previous
 * one.
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
	/** Memory placement is a device property, identical for every arena, so only
	 *  the top-level arena reports it at info; a sub-worldview would repeat it
	 *  for every ship in view. */
	SceneVertexBuffer(VulkanDevice device, long totalBytes, long slotBytes, boolean topLevel)
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
		String placement = hostIsDeviceLocal ? "device-local/ReBAR-UMA" : "host memory";
		String mirror = staticMirror != null
			? (staticBytes >> 20) + " MiB VRAM"
			: (hostIsDeviceLocal ? "not needed" : "FAILED");
		String summary = "Scene vertex buffer: {} MiB, memory flags 0x{} ({}), static mirror {}";
		Object[] args = {totalBytes >> 20,
			Integer.toHexString(vertexBuffer.memoryPropertyFlags()), placement, mirror};
		if (topLevel)
		{
			log.info(summary, args);
		}
		else
		{
			log.debug(summary, args);
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

	long handle() { return vertexBuffer.handle(); }
	ByteBuffer mapped() { return mapped; }
	long slotBytes() { return slotBytes; }

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
		if (staticMirror != null)
		{
			staticMirror.close();
		}
		vertexBuffer.close();
	}
}
