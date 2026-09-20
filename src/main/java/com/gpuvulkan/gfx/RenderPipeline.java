/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

/**
 * A baked graphics pipeline, bound via {@link RenderEncoder#bindPipeline}.
 * Tied to a render-pass shape: swapchain recreation doesn't invalidate it as
 * long as attachment formats and sample count are unchanged.
 */
public interface RenderPipeline extends AutoCloseable
{
	@Override
	void close();
}
