/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

/**
 * A concrete set of resources bound to a {@link BindGroupLayout} (a Vulkan
 * {@code VkDescriptorSet}). For streaming resources, rebind the same BindGroup
 * each frame — the layer dispatches to the correct per-slot set internally.
 */
public interface BindGroup extends AutoCloseable
{
	@Override
	void close();
}
