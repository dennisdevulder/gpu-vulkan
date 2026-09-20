/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

/**
 * Top-level entry point for the rendering device layer: device + swapchain +
 * per-frame command buffers, plus resource/pipeline factories. Resources
 * returned by {@code create*} are owned by the caller and must be closed.
 */
public interface Renderer extends RenderDevice, AutoCloseable
{
	/** Drops layer state. Resources created via this Renderer are NOT
	 *  closed transitively — their owners are still responsible. */
	@Override
	void close();
}
