/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * IDE entry point and shadowJar Main-Class. Registers the plugin
 * explicitly: since the com.gpuvulkan move (the hub bans net.runelite
 * packages), RuneLite's core-plugin classpath scan no longer finds it,
 * so loadBuiltin is the only registration — no double-entry risk.
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
		ExternalPluginManager.loadBuiltin(GpuVulkanPlugin.class);
		RuneLite.main(args);
	}
}
