/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import com.gpuvulkan.gfx.RenderDevice;
import com.gpuvulkan.gfx.RenderTarget;

final class GfxRenderTarget implements RenderTarget
{
	private final FrameSync frameSync;
	private final RenderPass pass;
	private final OffscreenSceneTarget target;
	private final GfxRenderer renderer;

	GfxRenderTarget(VulkanDevice device, FrameSync frameSync, int colorFormat,
		int width, int height, int samples)
	{
		this.frameSync = frameSync;
		this.pass = new RenderPass(device, colorFormat, samples, false);
		this.target = new OffscreenSceneTarget(device, pass, width, height, colorFormat, samples);
		this.renderer = new GfxRenderer(device, frameSync, pass, colorFormat);
	}

	@Override
	public int width()
	{
		return target.width();
	}

	@Override
	public int height()
	{
		return target.height();
	}

	@Override
	public boolean resize(int width, int height)
	{
		width = Math.max(width, 1);
		height = Math.max(height, 1);
		if (width == target.width() && height == target.height())
		{
			return false;
		}
		frameSync.waitAllInFlight();
		target.recreate(width, height);
		return true;
	}

	@Override
	public RenderDevice device()
	{
		return renderer;
	}

	long framebuffer()
	{
		return target.framebuffer();
	}

	long renderPassHandle()
	{
		return pass.handle();
	}

	long colorImage()
	{
		return target.colorImage();
	}

	long colorView()
	{
		return target.colorView();
	}

	long sampler()
	{
		return target.sampler();
	}

	@Override
	public void close()
	{
		target.close();
		pass.close();
	}
}
