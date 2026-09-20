/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import com.gpuvulkan.gfx.Renderer;

public final class Gfx
{
	private Gfx() {}

	/** The Renderer does not own these handles — its close() only releases
	 *  resources the Renderer itself created. */
	public static Renderer wrap(VulkanDevice device, FrameSync frameSync, RenderPass renderPass, int colorFormat)
	{
		return new GfxRenderer(device, frameSync, renderPass, colorFormat);
	}
}
