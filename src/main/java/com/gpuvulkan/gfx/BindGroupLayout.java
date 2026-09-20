/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

/**
 * Describes the shape of a {@link BindGroup} — the bindings and which shader
 * stages can read them. The matching pipeline declares the same layout so the
 * GPU knows what to expect at each set index. Equivalent to Vulkan's
 * {@code VkDescriptorSetLayout}.
 */
public interface BindGroupLayout extends AutoCloseable
{
	@Override
	void close();
}
