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

final class BaseRenderer implements VulkanRenderExtension
{
	private VulkanSceneRenderer sceneRenderer;
	private InterfaceRenderer interfaceRenderer;
	private GpuVulkanPluginConfig config;

	@Override
	public void onRegistered(VulkanRenderContext context)
	{
		config = context.config();
		sceneRenderer = context.createSceneRenderer();
		interfaceRenderer = new InterfaceRenderer(context.renderer());
		applyWireframeConfig();
	}

	@Override
	public void uploadUiPixels(int[] pixels, int width, int height)
	{
		if (config != null && config.benchmarkSkipUi()) return;
		if (interfaceRenderer == null) return;
		interfaceRenderer.uploadPixels(pixels, width, height);
	}

	@Override
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getKey() != null && event.getKey().startsWith("wireframe"))
		{
			applyWireframeConfig();
		}
	}

	@Override
	public void beginFrame()
	{
		if (sceneRenderer == null) return;
		sceneRenderer.beginFrame();
	}

	@Override
	public void captureDynamicPending()
	{
		if (config != null && config.benchmarkSkipDynamicCapture()) return;
		if (sceneRenderer == null) return;
		sceneRenderer.captureDynamicPending();
	}

	@Override
	public void invalidateCapturedScene()
	{
		if (sceneRenderer == null) return;
		sceneRenderer.invalidateCapturedScene();
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.invalidateZone(scene, zx, zz);
	}

	@Override
	public void rebuildDirtyZones(Scene scene)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.rebuildDirtyZones(scene);
	}

	@Override
	public void captureSkybox(Scene scene)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.captureSkybox(scene);
	}

	@Override
	public void drawPass(int pass)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.drawPass(pass);
	}

	@Override
	public void captureScene(Scene scene)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.captureScene(scene);
	}

	@Override
	public void captureModel(Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		if (config != null && config.benchmarkSkipDynamicCapture()) return;
		if (sceneRenderer == null) return;
		sceneRenderer.captureModel(model, orientation, worldX, worldY, worldZ);
	}

	@Override
	public void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		if (config != null && config.benchmarkSkipDynamicCapture()) return;
		if (sceneRenderer == null) return;
		sceneRenderer.captureModel(projection, model, orientation, worldX, worldY, worldZ);
	}

	@Override
	public void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ, int renderMode)
	{
		if (config != null && config.benchmarkSkipDynamicCapture()) return;
		if (sceneRenderer == null) return;
		sceneRenderer.captureModel(projection, model, orientation, worldX, worldY, worldZ, renderMode);
	}

	@Override
	public void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ,
		int renderMode, boolean actorModel)
	{
		if (config != null && config.benchmarkSkipDynamicCapture()) return;
		if (sceneRenderer == null) return;
		sceneRenderer.captureModel(projection, model, orientation, worldX, worldY, worldZ, renderMode, actorModel);
	}

	@Override
	public void setLevelRange(int minLevel, int maxLevel)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.setLevelRange(minLevel, maxLevel);
	}

	@Override
	public void setLevelRange(int minLevel, int currentLevel, int maxLevel)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.setLevelRange(minLevel, currentLevel, maxLevel);
	}

	@Override
	public void setHideRoofIds(Set<Integer> hideRoofIds)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.setHideRoofIds(hideRoofIds);
	}

	@Override
	public void collectDebugMetrics(GpuVulkanDebugMetrics metrics)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.collectDebugMetrics(metrics);
	}

	@Override
	public void recordBeforeRenderPass(VkCommandBuffer commandBuffer)
	{
		if (sceneRenderer != null)
		{
			sceneRenderer.recordBeforeRenderPass(commandBuffer);
		}
		if (config != null && config.benchmarkSkipUi()) return;
		if (interfaceRenderer == null) return;
		interfaceRenderer.recordCopyToImage(commandBuffer);
	}

	@Override
	public void recordScenePass(VulkanFrameContext frame)
	{
		if (sceneRenderer != null)
		{
			if (config == null || !config.benchmarkSkipScene())
			{
				sceneRenderer.recordDraw(frame);
			}
		}
	}

	@Override
	public void recordUiPass(VulkanFrameContext frame)
	{
		if (interfaceRenderer != null && (config == null || !config.benchmarkSkipUi()))
		{
			interfaceRenderer.recordDraw(frame);
		}
	}

	@Override
	public void recordRenderPass(VulkanFrameContext frame)
	{
		recordScenePass(frame);
		recordUiPass(frame);
	}

	@Override
	public void close()
	{
		if (interfaceRenderer != null)
		{
			interfaceRenderer.close();
			interfaceRenderer = null;
		}
		if (sceneRenderer != null)
		{
			sceneRenderer.close();
			sceneRenderer = null;
		}
	}

	private void applyWireframeConfig()
	{
		if (sceneRenderer == null || config == null) return;
		sceneRenderer.setWireframeTerrain(config.wireframeTerrain());
		sceneRenderer.setWireframeWalls(config.wireframeWalls());
		sceneRenderer.setWireframeDecorative(config.wireframeDecorative());
		sceneRenderer.setWireframeGround(config.wireframeGround());
		sceneRenderer.setWireframeGameObjects(config.wireframeGameObjects());
		sceneRenderer.setWireframeDynamic(config.wireframeDynamic());
	}

}
