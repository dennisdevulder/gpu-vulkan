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

import java.awt.Canvas;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTFunctions;
import org.lwjgl.system.jawt.JAWTWin32DrawingSurfaceInfo;
import org.lwjgl.system.windows.WinBase;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRWin32Surface;
import org.lwjgl.vulkan.VkWin32SurfaceCreateInfoKHR;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.VK_SUCCESS;

/** Windows surface creation via {@code VK_KHR_win32_surface}. HWND comes from
 *  JAWT; HINSTANCE from {@code GetModuleHandle(NULL)} (the loader passes it
 *  through to the ICD verbatim — what actually matters for the surface
 *  identity is the HWND). */
final class Win32PlatformSurface implements PlatformSurface
{
	@Override
	public String[] requiredInstanceExtensions()
	{
		return new String[]
		{
			KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME,
			KHRWin32Surface.VK_KHR_WIN32_SURFACE_EXTENSION_NAME,
		};
	}

	@Override
	public long createSurface(VulkanInstance instance, Canvas canvas)
	{
		JAWT awt = JAWT.calloc();
		awt.version(JAWTFunctions.JAWT_VERSION_9);
		try
		{
			if (!JAWTFunctions.JAWT_GetAWT(awt))
			{
				throw new RuntimeException("JAWT_GetAWT failed");
			}

			JAWTDrawingSurface ds = JAWTFunctions.JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
			if (ds == null)
			{
				throw new RuntimeException("JAWT_GetDrawingSurface returned null");
			}
			try
			{
				return lockAndCreateSurface(instance, ds);
			}
			finally
			{
				JAWTFunctions.JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
			}
		}
		finally
		{
			awt.free();
		}
	}

	private static long lockAndCreateSurface(VulkanInstance instance, JAWTDrawingSurface ds)
	{
		int lockResult = JAWTFunctions.JAWT_DrawingSurface_Lock(ds, ds.Lock());
		if ((lockResult & JAWTFunctions.JAWT_LOCK_ERROR) != 0)
		{
			throw new RuntimeException("JAWT_DrawingSurface_Lock failed: " + lockResult);
		}
		try
		{
			JAWTDrawingSurfaceInfo dsi = JAWTFunctions.JAWT_DrawingSurface_GetDrawingSurfaceInfo(
				ds, ds.GetDrawingSurfaceInfo());
			if (dsi == null)
			{
				throw new RuntimeException("JAWT_DrawingSurface_GetDrawingSurfaceInfo returned null");
			}
			try
			{
				JAWTWin32DrawingSurfaceInfo win = JAWTWin32DrawingSurfaceInfo.create(dsi.platformInfo());
				return createWin32Surface(instance, win.hwnd());
			}
			finally
			{
				JAWTFunctions.JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds.FreeDrawingSurfaceInfo());
			}
		}
		finally
		{
			JAWTFunctions.JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
		}
	}

	private static long createWin32Surface(VulkanInstance instance, long hwnd)
	{
		// GetModuleHandle(NULL) → HMODULE of the process EXE. The
		long hinstance = WinBase.GetModuleHandle(null, (java.nio.ByteBuffer) null);
		try (MemoryStack stack = stackPush())
		{
			VkWin32SurfaceCreateInfoKHR info = VkWin32SurfaceCreateInfoKHR.calloc(stack)
				.sType$Default()
				.hinstance(hinstance)
				.hwnd(hwnd);
			LongBuffer pSurface = stack.mallocLong(1);
			Vk.check("vkCreateWin32SurfaceKHR", KHRWin32Surface.vkCreateWin32SurfaceKHR(
				instance.handle(), info, null, pSurface));
			return pSurface.get(0);
		}
	}
}
