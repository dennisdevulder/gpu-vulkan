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

	/** Thrown by {@link #check(String, int)}. Plain {@code RuntimeException}
	 *  subclass so callers can catch this specifically if they want to. */
	static final class VulkanException extends RuntimeException
	{
		VulkanException(String message) { super(message); }
	}
}
