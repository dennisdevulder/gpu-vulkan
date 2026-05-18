package net.runelite.client.plugins.gpuvulkan;

import net.runelite.client.RuneLite;

/**
 * IDE entry point and shadowJar Main-Class. Just runs RuneLite — the
 * plugin loads itself because it lives in {@code net.runelite.client.plugins},
 * the package {@code PluginManager.loadCorePlugins} scans for
 * {@code @PluginDescriptor} classes.
 *
 * <p>NOT calling {@code ExternalPluginManager.loadBuiltin(...)} here is
 * deliberate: adding it would register the plugin a second time on top
 * of the classpath scan, producing two "GPU (Vulkan)" entries that fight
 * over the {@code setDrawCallbacks} slot and refuse to start.
 */
public class GpuVulkanPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// LWJGL's MemoryStack defaults to 64 KB per thread. The Vulkan
		// instance + device extension queries can blow that on drivers that
		// expose many extensions (AMD/RADV regularly hits OutOfMemoryError
		// "Out of stack space" mid-startup). Stock RuneLite sets this to
		// 1024 via its launcher; mirror it here so `java -jar …all.jar`
		// works without the user needing -D flags.
		if (System.getProperty("org.lwjgl.system.stackSize") == null)
		{
			System.setProperty("org.lwjgl.system.stackSize", "1024");
		}
		RuneLite.main(args);
	}
}
