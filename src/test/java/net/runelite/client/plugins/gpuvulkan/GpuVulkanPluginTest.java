package net.runelite.client.plugins.gpuvulkan;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * IDE entry point: launches RuneLite with this plugin sideloaded. Run the
 * {@code main} method from your IDE to develop against the plugin without
 * needing to install a release build.
 */
public class GpuVulkanPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GpuVulkanPlugin.class);
		RuneLite.main(args);
	}
}
