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
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Backend-provided scene renderer for extensions that want the stock
 * gpu-vulkan scene capture and draw path without reaching into backend
 * internals.
 */
public interface VulkanSceneRenderer extends AutoCloseable
{
	void beginFrame();

	void captureDynamicPending();

	void invalidateCapturedScene();

	default void invalidateZone(Scene scene, int zx, int zz)
	{
	}

	default void rebuildDirtyZones(Scene scene)
	{
	}

	default void captureSkybox(Scene scene)
	{
	}

	default void drawPass(int pass)
	{
	}

	void captureScene(Scene scene);

	void captureModel(Model model, int orientation, int worldX, int worldY, int worldZ);

	void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ);

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

	void setLevelRange(int minLevel, int maxLevel);

	void setHideRoofIds(Set<Integer> hideRoofIds);

	void collectDebugMetrics(GpuVulkanDebugMetrics metrics);

	void setWireframeTerrain(boolean enabled);

	void setWireframeWalls(boolean enabled);

	void setWireframeDecorative(boolean enabled);

	void setWireframeGround(boolean enabled);

	void setWireframeGameObjects(boolean enabled);

	void setWireframeDynamic(boolean enabled);

	void recordDraw(VulkanFrameContext frame);

	default void recordBeforeRenderPass(VkCommandBuffer commandBuffer)
	{
	}

	@Override
	void close();
}
