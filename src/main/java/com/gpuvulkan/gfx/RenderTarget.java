/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

/**
 * Offscreen color+depth render target. Render via {@link RenderEncoder#beginPass},
 * sample after {@link RenderEncoder#prepareForSampling}. Pipelines that draw into
 * this target MUST come from {@link #device()} — the target owns its render pass.
 */
public interface RenderTarget extends AutoCloseable
{
	int width();

	int height();

	/** Waits for in-flight frames first. True = recreated; bind groups
	 *  referencing its color image must then be recreated by the caller. */
	boolean resize(int width, int height);

	/** Device whose pipelines render into this target. */
	RenderDevice device();

	@Override
	void close();
}
