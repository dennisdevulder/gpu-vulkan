/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import java.util.Set;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import net.runelite.client.events.ConfigChanged;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Extension point for renderers sharing the GPU Vulkan backend. The backend
 * owns RuneLite's {@code DrawCallbacks} slot and fans scene, model, config and
 * command-recording events out through this interface.
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

	default boolean hasSkybox() { return false; }

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

	/** Record commands that must precede {@code vkCmdBeginRenderPass}
	 *  (staging copies, layout transitions). */
	default void recordBeforeRenderPass(VkCommandBuffer commandBuffer) {}

	/** Non-null redirects the scene pass into an extension-owned target this
	 *  frame; the first registered extension returning non-null wins. */
	default ScenePassRedirect scenePassRedirect() { return null; }

	default void recordScenePass(VulkanFrameContext frame) {}

	default void recordUiPass(VulkanFrameContext frame) {}

	default void recordRenderPass(VulkanFrameContext frame) {}

	/** Record against the composited frame after the last render pass, before
	 *  present. See {@link VulkanPostFrameContext} for the layout contract. */
	default void recordAfterComposite(VulkanPostFrameContext frame) {}

	default void beforeSwapchainRebuild() {}

	@Override
	default void close() {}
}
