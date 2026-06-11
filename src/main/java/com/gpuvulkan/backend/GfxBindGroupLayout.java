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

import com.gpuvulkan.gfx.BindGroupLayout;
import com.gpuvulkan.gfx.BindGroupLayoutDesc;

import static org.lwjgl.vulkan.VK13.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK13.vkDestroyDescriptorSetLayout;

final class GfxBindGroupLayout implements BindGroupLayout
{
	private final VulkanDevice device;
	private long handle;
	private final BindGroupLayoutDesc desc;

	GfxBindGroupLayout(VulkanDevice device, long handle, BindGroupLayoutDesc desc)
	{
		this.device = device;
		this.handle = handle;
		this.desc = desc;
	}

	long handle() { return handle; }
	BindGroupLayoutDesc desc() { return desc; }

	@Override
	public void close()
	{
		if (handle != VK_NULL_HANDLE)
		{
			vkDestroyDescriptorSetLayout(device.handle(), handle, null);
			handle = VK_NULL_HANDLE;
		}
	}
}
