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

import net.runelite.client.plugins.gpuvulkan.gfx.RenderDevice;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderTarget;

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
