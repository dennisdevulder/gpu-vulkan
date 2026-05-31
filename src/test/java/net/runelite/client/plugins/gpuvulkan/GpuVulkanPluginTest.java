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
