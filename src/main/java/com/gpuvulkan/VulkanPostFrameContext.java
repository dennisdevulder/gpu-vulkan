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
