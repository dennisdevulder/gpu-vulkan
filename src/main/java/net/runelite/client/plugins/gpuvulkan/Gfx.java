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

import net.runelite.client.plugins.gpuvulkan.gfx.Renderer;

/**
 * Entry point for the {@code gfx} rendering device layer. The layer's public
 * API lives in {@code net.runelite.client.plugins.gpuvulkan.gfx} as
 * interfaces; the implementation classes are package-private in this top
 * package so they can keep using the existing
 * {@link Texture}/{@link Buffer}/{@link Descriptors} wrappers without
 * forcing those types to become public.
 *
 * <p>Phase 1 (the current slice): the layer is constructed by adopting
 * already-built Vulkan handles via {@link #wrap}. The plugin's existing
 * init path stays unchanged. The migrated consumer today is
 * {@link InterfaceRenderer}; it dropped the old hardcoded
 * {@code Descriptors} + {@code UiPipeline} pair in favour of the layer's
 * {@link Renderer}-built pipeline + bind group.
 *
 * <p>Phase 2 (future): the layer constructs the instance / device /
 * swapchain itself, swallowing the rest of the boilerplate that the
 * existing classes contain.
 */
public final class Gfx
{
	private Gfx() {}

	/**
	 * Builds a {@link Renderer} that adopts the already-constructed Vulkan
	 * state passed in. The Renderer does not assume ownership of any of
	 * these handles — its {@code close()} only releases resources the
	 * Renderer itself created (shader modules, pipelines, bind groups,
	 * streaming images).
	 *
	 * @param device         The VulkanDevice wrapping the active
	 *                       {@code VkDevice} + queue.
	 * @param frameSync      Per-slot fences/semaphores; the Renderer reads
	 *                       {@link FrameSync#currentFrame} to route streaming
	 *                       resource updates to the right slot.
	 * @param renderPass     The swapchain's main render pass; pipelines
	 *                       built via the Renderer target it.
	 */
	public static Renderer wrap(VulkanDevice device, FrameSync frameSync, RenderPass renderPass)
	{
		return new GfxRenderer(device, frameSync, renderPass);
	}
}
