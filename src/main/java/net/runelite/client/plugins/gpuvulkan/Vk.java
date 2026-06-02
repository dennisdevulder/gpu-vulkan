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

import static org.lwjgl.vulkan.VK13.VK_SUCCESS;

/**
 * Vulkan return-code helpers. Every Vulkan call returns an {@code int};
 * leaving raw {@code VK_*} codes to bubble up obscures the failing call
 * and forces every site to write a custom {@code throw new RuntimeException(...)}.
 * Use {@link #check(String, int)} at the boundary to convert non-success
 * codes into named exceptions and {@link #ok(int)} when a routine returns
 * its own meaningful non-VK_SUCCESS value (e.g. {@code VK_SUBOPTIMAL_KHR}).
 */
final class Vk
{
	private Vk() {}

	/**
	 * Throws if {@code result != VK_SUCCESS}. The message names the Vulkan
	 * call that failed and the raw return code so a stack trace from the
	 * caller is enough to diagnose.
	 */
	static void check(String call, int result)
	{
		if (result != VK_SUCCESS)
		{
			throw new VulkanException(call + " failed: " + result);
		}
	}

	/** True iff {@code result == VK_SUCCESS}. */
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

	/** Thrown by {@link #check(String, int)}. Plain {@code RuntimeException}
	 *  subclass so callers can catch this specifically if they want to. */
	static final class VulkanException extends RuntimeException
	{
		VulkanException(String message) { super(message); }
	}
}
