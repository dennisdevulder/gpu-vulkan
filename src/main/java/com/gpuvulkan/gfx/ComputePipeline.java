/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

/**
 * A baked compute pipeline. Bound at dispatch time via
 * {@link RenderEncoder#bindComputePipeline(ComputePipeline)}.
 */
public interface ComputePipeline extends AutoCloseable
{
	@Override
	void close();
}
