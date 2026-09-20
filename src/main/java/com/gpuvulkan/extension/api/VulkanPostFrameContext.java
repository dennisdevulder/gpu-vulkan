/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * The composited frame (scene + UI) handed to recordAfterComposite before
 * present. {@link #colorImage()} has TRANSFER_SRC usage, is in
 * {@link #imageLayout()} at entry and must be restored on return. Commands are
 * fenced by the frame's in-flight fence — cycle resources by {@link #frameIndex()}.
 */
public interface VulkanPostFrameContext
{
	VkCommandBuffer commandBuffer();

	long colorImage();

	int width();

	int height();

	/** {@code VkImageLayout} of {@link #colorImage()} at hook entry and required at exit. */
	int imageLayout();

	/** {@code VkFormat} of {@link #colorImage()} — the swapchain/surface format,
	 *  typically BGRA-ordered. Do not assume RGBA. */
	int imageFormat();

	/** Frame-in-flight slot, {@code [0, framesInFlight)}. */
	int frameIndex();
}
