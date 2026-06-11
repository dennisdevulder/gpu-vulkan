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

import java.awt.Canvas;
import java.nio.LongBuffer;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTMetalSurface;
import org.lwjgl.vulkan.KHRPortabilityEnumeration;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkMetalSurfaceCreateInfoEXT;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.VK_SUCCESS;

/**
 * macOS surface via {@code VK_EXT_metal_surface}: {@link MacOSMetalHelper}
 * attaches the CAMetalLayer, vkCreateMetalSurfaceEXT wraps the pointer.
 */
@Slf4j
final class MacOSPlatformSurface implements PlatformSurface
{
	private final boolean vsync;

	MacOSPlatformSurface(boolean vsync)
	{
		this.vsync = vsync;
	}


	@Override
	public String[] requiredInstanceExtensions()
	{
		return new String[]
		{
			KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME,
			EXTMetalSurface.VK_EXT_METAL_SURFACE_EXTENSION_NAME,
			KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME,
		};
	}

	@Override
	public long createSurface(VulkanInstance instance, Canvas canvas)
	{
		// Hold the AWT tree lock across JAWT — the peer can mutate between
		// lock and GetDrawingSurfaceInfo and crash in jni_GetObjectField.
		long caMetalLayer;
		synchronized (canvas.getTreeLock())
		{
			if (!canvas.isValid())
			{
				throw new RuntimeException("Canvas not valid at JAWT-attach time");
			}
			caMetalLayer = MacOSMetalHelper.attachMetalLayer(canvas, vsync);
		}
		log.info("Attached CAMetalLayer 0x{}", Long.toHexString(caMetalLayer));

		// Synthetic COMPONENT_RESIZED so AWT lays out the CALayer now.
		// AWT-only dispatch — setting CALayer.bounds directly trips MoltenVK.
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			canvas.dispatchEvent(new java.awt.event.ComponentEvent(
				canvas, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
		});

		try (MemoryStack stack = stackPush())
		{
			VkMetalSurfaceCreateInfoEXT info = VkMetalSurfaceCreateInfoEXT.calloc(stack)
				.sType$Default();
			// LWJGL 3.3.6's pLayer(PointerBuffer) writes the buffer's address,
			// not the contained pointer — write the field offset directly.
			MemoryUtil.memPutAddress(
				info.address() + VkMetalSurfaceCreateInfoEXT.PLAYER,
				caMetalLayer);

			LongBuffer pSurface = stack.mallocLong(1);
			Vk.check("vkCreateMetalSurfaceEXT", EXTMetalSurface.vkCreateMetalSurfaceEXT(
				instance.handle(), info, null, pSurface));
			return pSurface.get(0);
		}
	}
}
