/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

/**
 * Public service surface for plugins that want to share the Vulkan backend.
 */
public interface VulkanRenderBackend
{
	boolean isReady();

	/** Thread-safe but may block until the current callback finishes; attaches at
	 *  next startup if not ready. Closing the handle unregisters and closes the extension. */
	AutoCloseable registerExtension(VulkanRenderExtension extension);
}
