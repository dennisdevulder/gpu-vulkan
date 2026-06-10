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

import net.runelite.client.plugins.gpuvulkan.gfx.RenderTarget;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Redirects the scene pass into an extension-owned {@link RenderTarget} —
 * the hook for upscalers and other full-scene post-processors.
 *
 * <p>Per-frame sequence when an extension's
 * {@link VulkanRenderExtension#scenePassRedirect()} returns non-null:
 * <ol>
 *   <li>{@link #sceneTarget} — supply (and resize) the target the backend
 *       renders the 3D scene into.</li>
 *   <li>The backend records the scene pass into that target and ends it.
 *       The target is NOT yet transitioned for sampling — call
 *       {@code prepareForSampling} first.</li>
 *   <li>{@link #recordAfterScene} — record intermediate passes against your
 *       own targets; no pass is open on entry or may be left open.</li>
 *   <li>The backend begins the final on-screen pass.
 *       {@link #recordResolve} — draw your full-screen resolve; the UI pass
 *       follows in the same render pass.</li>
 * </ol>
 */
public interface ScenePassRedirect
{
	RenderTarget sceneTarget(int targetWidth, int targetHeight);

	void recordAfterScene(VkCommandBuffer cmd);

	void recordResolve(VulkanFrameContext frame);
}
