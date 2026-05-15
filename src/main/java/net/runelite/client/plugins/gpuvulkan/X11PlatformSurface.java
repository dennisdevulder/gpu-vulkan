package net.runelite.client.plugins.gpuvulkan;

import java.awt.Canvas;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTFunctions;
import org.lwjgl.system.jawt.JAWTX11DrawingSurfaceInfo;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRXlibSurface;
import org.lwjgl.vulkan.VkXlibSurfaceCreateInfoKHR;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.VK_SUCCESS;

/** Linux/X11 surface creation via {@code VK_KHR_xlib_surface}. JDKs running
 *  on Wayland sessions usually go through XWayland and present the canvas as
 *  an X window, so this path covers both. */
final class X11PlatformSurface implements PlatformSurface
{
	@Override
	public String[] requiredInstanceExtensions()
	{
		return new String[]
		{
			KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME,
			KHRXlibSurface.VK_KHR_XLIB_SURFACE_EXTENSION_NAME,
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
						JAWTX11DrawingSurfaceInfo x11 = JAWTX11DrawingSurfaceInfo.create(dsi.platformInfo());
						try (MemoryStack stack = stackPush())
						{
							VkXlibSurfaceCreateInfoKHR info = VkXlibSurfaceCreateInfoKHR.calloc(stack)
								.sType$Default()
								.dpy(x11.display())
								.window(x11.drawable());
							LongBuffer pSurface = stack.mallocLong(1);
							Vk.check("vkCreateXlibSurfaceKHR", KHRXlibSurface.vkCreateXlibSurfaceKHR(
								instance.handle(), info, null, pSurface));
							return pSurface.get(0);
						}
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
}
