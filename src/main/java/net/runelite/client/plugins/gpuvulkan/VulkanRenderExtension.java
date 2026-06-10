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

import java.util.Set;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import net.runelite.client.events.ConfigChanged;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Extension point for renderers that want to share the GPU Vulkan backend.
 *
 * <p>The backend owns RuneLite's {@code DrawCallbacks} slot and fans scene,
 * model, config and command-recording events out through this interface. This
 * keeps platform setup, swapchain handling and frame sync in one place while
 * allowing renderer modules to provide their own pipelines.
 */
public interface VulkanRenderExtension extends AutoCloseable
{
	default void onRegistered(VulkanRenderContext context) {}

	default void onConfigChanged(ConfigChanged event) {}

	default void beginFrame() {}

	default void captureDynamicPending() {}

	default void invalidateCapturedScene() {}

	default void invalidateZone(Scene scene, int zx, int zz) {}

	default void rebuildDirtyZones(Scene scene) {}

	default void captureSkybox(Scene scene) {}

	default void drawPass(int pass) {}

	default void captureScene(Scene scene) {}

	default void captureModel(Model model, int orientation, int worldX, int worldY, int worldZ) {}

	default void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		captureModel(model, orientation, worldX, worldY, worldZ);
	}

	default void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ, int renderMode)
	{
		captureModel(projection, model, orientation, worldX, worldY, worldZ);
	}

	default void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ,
		int renderMode, boolean actorModel)
	{
		captureModel(projection, model, orientation, worldX, worldY, worldZ, renderMode);
	}

	default void setLevelRange(int minLevel, int currentLevel, int maxLevel)
	{
		setLevelRange(minLevel, maxLevel);
	}

	default void setLevelRange(int minLevel, int maxLevel) {}

	default void setHideRoofIds(Set<Integer> hideRoofIds) {}

	default void collectDebugMetrics(GpuVulkanDebugMetrics metrics) {}

	default void uploadUiPixels(int[] pixels, int width, int height) {}

	/**
	 * Record commands that must happen before {@code vkCmdBeginRenderPass}.
	 * Typical use: staging-buffer copies and image layout transitions.
	 */
	default void recordBeforeRenderPass(VkCommandBuffer commandBuffer) {}

	/**
	 * Non-null to redirect the scene pass into an extension-owned target
	 * this frame (upscaling, full-scene post-processing). The first
	 * registered extension returning non-null wins.
	 */
	default ScenePassRedirect scenePassRedirect() { return null; }

	default void recordScenePass(VulkanFrameContext frame) {}

	default void recordUiPass(VulkanFrameContext frame) {}

	default void recordRenderPass(VulkanFrameContext frame) {}

	/**
	 * Record commands against the final composited frame (scene + UI), after
	 * the last render pass has ended and before present. Typical use: copying
	 * the frame out for capture or video encode. See
	 * {@link VulkanPostFrameContext} for the layout contract.
	 */
	default void recordAfterComposite(VulkanPostFrameContext frame) {}

	@Override
	default void close() {}
}
