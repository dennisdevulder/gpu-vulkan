/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

/**
 * A compiled SPIR-V shader; {@code close()} destroys the VkShaderModule.
 * Stage is declared at pipeline-build time, not on the module, so the same
 * SPIR-V can be reused across stages.
 */
public interface ShaderModule extends AutoCloseable
{
	@Override
	void close();
}
