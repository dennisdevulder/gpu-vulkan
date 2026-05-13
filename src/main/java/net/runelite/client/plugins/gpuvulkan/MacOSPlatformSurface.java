package net.runelite.client.plugins.gpuvulkan;

import java.awt.Canvas;
import org.lwjgl.vulkan.EXTMetalSurface;
import org.lwjgl.vulkan.KHRSurface;

/**
 * macOS surface stub. Reports the right extension names so the rest of the
 * codebase can be uniform, but {@link #createSurface} throws — actually
 * creating a {@code VkSurfaceKHR} on macOS requires:
 *
 * <ul>
 *   <li>MoltenVK runtime (LWJGL ships it in {@code lwjgl-vulkan-natives-macos}
 *       / {@code -macos-arm64} — these need to be added to the build's
 *       runtimeOnly classifier list, currently absent).</li>
 *   <li>{@code JAWTSurfaceLayers} to obtain the canvas's root {@code CALayer},
 *       then attaching a {@code CAMetalLayer} as a child (or replacing) via
 *       the Objective-C runtime ({@code objc_msgSend}). LWJGL exposes the
 *       helpers in {@code org.lwjgl.system.macosx}.</li>
 *   <li>{@code vkCreateMetalSurfaceEXT} with that {@code CAMetalLayer}
 *       pointer as {@code pLayer}.</li>
 * </ul>
 *
 * <p>Wiring this up correctly is a separate piece of work — incorrect Cocoa
 * layer attachment crashes the EDT, which is hard to debug remotely. Until
 * then enabling the plugin on macOS surfaces a clear "not implemented"
 * message rather than a confusing X11/Vulkan failure.
 */
final class MacOSPlatformSurface implements PlatformSurface
{
	@Override
	public String[] requiredInstanceExtensions()
	{
		return new String[]
		{
			KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME,
			EXTMetalSurface.VK_EXT_METAL_SURFACE_EXTENSION_NAME,
		};
	}

	@Override
	public long createSurface(VulkanInstance instance, Canvas canvas)
	{
		throw new UnsupportedOperationException(
			"GPU (Vulkan) plugin on macOS is not implemented yet — requires "
				+ "MoltenVK + CAMetalLayer integration through JAWT. "
				+ "Use the stock GPU (OpenGL) plugin in the meantime.");
	}
}
