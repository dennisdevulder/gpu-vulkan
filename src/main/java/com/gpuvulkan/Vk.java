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
