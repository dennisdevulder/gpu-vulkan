package net.runelite.client.plugins.gpuvulkan;

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
 * macOS surface creation via {@code VK_EXT_metal_surface}. Defers the JAWT
 * lock + {@code CAMetalLayer} attach to {@link MacOSMetalHelper} (a small
 * native helper modelled on rlawt's macOS path). Once the helper returns a
 * layer pointer, we wrap it in a {@code VkSurfaceKHR} via
 * {@code vkCreateMetalSurfaceEXT}.
 *
 * <p>Instance-side prereq: {@link #requiredInstanceExtensions()} requests
 * {@code VK_KHR_portability_enumeration}; {@link VulkanInstance} translates
 * that into the matching create-flag (MoltenVK requires it or instance
 * creation fails with {@code VK_ERROR_INCOMPATIBLE_DRIVER}).
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
		// Hold the AWT tree lock across JAWT — same pattern the stock
		// GpuPlugin uses when it calls rlawt. Without this, the canvas
		// peer can mutate between lock and GetDrawingSurfaceInfo and the
		// JDK crashes inside jni_GetObjectField.
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

		// Java-side nudge to trigger AWT's CALayer layout pass. Dispatching a
		// synthetic COMPONENT_RESIZED to the canvas reuses the same code path
		// a real window resize uses — AWT sizes our JAWT-installed CALayer to
		// match the Canvas widget's bounds. Without this nudge, AWT defers
		// layout against the freshly-installed layer until something else
		// invalidates the canvas (which is why the user previously had to
		// resize the window after enabling the plugin). No native code is
		// touched here — only AWT's own event dispatch — so it can't trip
		// MoltenVK the way directly setting CALayer.bounds did.
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			canvas.dispatchEvent(new java.awt.event.ComponentEvent(
				canvas, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
		});

		try (MemoryStack stack = stackPush())
		{
			VkMetalSurfaceCreateInfoEXT info = VkMetalSurfaceCreateInfoEXT.calloc(stack)
				.sType$Default();
			// LWJGL 3.3.6's generated binding only exposes pLayer(PointerBuffer)
			// — its implementation writes the address of the PointerBuffer's
			// backing storage into the struct field, instead of the contained
			// pointer. MoltenVK then reads the storage as a CAMetalLayer and
			// the first 8 bytes (our real layer pointer) get interpreted as
			// the layer's isa, producing
			//     objc[…]: Attempt to use unknown class 0x….
			// Write the layer pointer directly to the field offset to bypass.
			MemoryUtil.memPutAddress(
				info.address() + VkMetalSurfaceCreateInfoEXT.PLAYER,
				caMetalLayer);

			LongBuffer pSurface = stack.mallocLong(1);
			int r = EXTMetalSurface.vkCreateMetalSurfaceEXT(
				instance.handle(), info, null, pSurface);
			if (r != VK_SUCCESS)
			{
				throw new RuntimeException("vkCreateMetalSurfaceEXT failed: " + r);
			}
			return pSurface.get(0);
		}
	}
}
