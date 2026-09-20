/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import com.gpuvulkan.gfx.RenderTarget;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Redirects the scene pass into an extension-owned {@link RenderTarget} (the
 * upscaler hook). {@link #sceneTarget} supplies the target — call
 * {@code prepareForSampling} before sampling it. {@link #recordAfterScene} runs
 * with no pass open; {@link #recordResolve} draws in the on-screen pass before the UI.
 */
public interface ScenePassRedirect
{
	RenderTarget sceneTarget(int targetWidth, int targetHeight);

	void recordAfterScene(VkCommandBuffer cmd);

	void recordResolve(VulkanFrameContext frame);
}
