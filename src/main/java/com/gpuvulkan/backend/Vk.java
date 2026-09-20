/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import static org.lwjgl.vulkan.VK13.VK_SUCCESS;

/** Vulkan return-code helpers. */
final class Vk
{
	private Vk() {}

	static void check(String call, int result)
	{
		if (result != VK_SUCCESS)
		{
			throw fail(call, result);
		}
	}

	/** Builds (without throwing) the failure for {@code call} — for sites
	 *  that clean up partially created resources before throwing. */
	static VulkanException fail(String call, int result)
	{
		return new VulkanException(call, result);
	}

	static boolean ok(int result)
	{
		return result == VK_SUCCESS;
	}

	/** Always-on precondition guard ({@code assert} is a no-op — RuneLite runs without {@code -ea}). */
	static void require(boolean condition, String message)
	{
		if (!condition)
		{
			throw new IllegalStateException(message);
		}
	}

	/** Thrown by {@link #check(String, int)}; carries the raw {@code VK_*}
	 *  code so callers can react to specific failures (device loss). */
	static final class VulkanException extends RuntimeException
	{
		private final int result;

		VulkanException(String call, int result)
		{
			super(call + " failed: " + result);
			this.result = result;
		}

		int result()
		{
			return result;
		}
	}
}
