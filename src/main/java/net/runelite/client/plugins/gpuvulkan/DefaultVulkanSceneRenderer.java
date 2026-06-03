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

final class DefaultVulkanSceneRenderer implements VulkanSceneRenderer
{
	private final SceneRenderer sceneRenderer;

	DefaultVulkanSceneRenderer(VulkanDevice device, FrameSync sync,
		RenderPass renderPass, TextureArray textureArray,
		DrawCallbackStats stats, boolean alphaToCoverage, boolean singlePassAlpha)
	{
		this.sceneRenderer = new SceneRenderer(device, sync, renderPass, textureArray, stats,
			alphaToCoverage, singlePassAlpha);
	}

	@Override
	public void beginFrame()
	{
		sceneRenderer.beginFrame();
	}

	@Override
	public void captureDynamicPending()
	{
		sceneRenderer.captureDynamicPending();
	}

	@Override
	public void invalidateCapturedScene()
	{
		sceneRenderer.invalidateCapturedScene();
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		sceneRenderer.invalidateZone(scene, zx, zz);
	}

	@Override
	public void rebuildDirtyZones(Scene scene)
	{
		sceneRenderer.rebuildDirtyZones(scene);
	}

	@Override
	public void captureSkybox(Scene scene)
	{
		sceneRenderer.captureSkybox(scene);
	}

	@Override
	public void drawPass(int pass)
	{
		sceneRenderer.drawPass(pass);
	}

	@Override
	public void captureScene(Scene scene)
	{
		sceneRenderer.captureScene(scene);
	}

	@Override
	public void captureModel(Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		sceneRenderer.captureModel(model, orientation, worldX, worldY, worldZ);
	}

	@Override
	public void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		sceneRenderer.captureModelSorted(projection, model, orientation, worldX, worldY, worldZ);
	}

	@Override
	public void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ, int renderMode)
	{
		sceneRenderer.captureModelSorted(projection, model, orientation, worldX, worldY, worldZ, renderMode);
	}

	@Override
	public void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ,
		int renderMode, boolean actorModel)
	{
		sceneRenderer.captureModelSorted(projection, model, orientation, worldX, worldY, worldZ, renderMode, actorModel);
	}

	@Override
	public void setLevelRange(int minLevel, int maxLevel)
	{
		sceneRenderer.setLevelRange(minLevel, maxLevel);
	}

	@Override
	public void setLevelRange(int minLevel, int currentLevel, int maxLevel)
	{
		sceneRenderer.setLevelRange(minLevel, currentLevel, maxLevel);
	}

	@Override
	public void setHideRoofIds(Set<Integer> hideRoofIds)
	{
		sceneRenderer.setHideRoofIds(hideRoofIds);
	}

	@Override
	public void collectDebugMetrics(GpuVulkanDebugMetrics metrics)
	{
		sceneRenderer.collectDebugMetrics(metrics);
	}

	@Override
	public void setWireframeTerrain(boolean enabled)
	{
		sceneRenderer.setWireframe(SceneRenderer.Layer.TERRAIN, enabled);
	}

	@Override
	public void setWireframeWalls(boolean enabled)
	{
		sceneRenderer.setWireframe(SceneRenderer.Layer.WALLS, enabled);
	}

	@Override
	public void setWireframeDecorative(boolean enabled)
	{
		sceneRenderer.setWireframe(SceneRenderer.Layer.DECORATIVE, enabled);
	}

	@Override
	public void setWireframeGround(boolean enabled)
	{
		sceneRenderer.setWireframe(SceneRenderer.Layer.GROUND, enabled);
	}

	@Override
	public void setWireframeGameObjects(boolean enabled)
	{
		sceneRenderer.setWireframe(SceneRenderer.Layer.GAME_OBJECTS, enabled);
	}

	@Override
	public void setWireframeDynamic(boolean enabled)
	{
		sceneRenderer.setWireframe(SceneRenderer.Layer.DYNAMIC, enabled);
	}

	@Override
	public void recordDraw(VulkanFrameContext frame)
	{
		sceneRenderer.recordDraw(frame.commandBuffer(), frame.sceneMvp(), frame.brightness(),
			frame.cameraX(), frame.cameraY(), frame.cameraZ(), frame.drawDistanceTiles(), frame.fogDepthTiles(),
			frame.fogR(), frame.fogG(), frame.fogB(), frame.gameTick(),
			frame.textureLightMode(), frame.colorBlindMode(), frame.colorBlindIntensity(),
			frame.smoothBanding());
	}

	@Override
	public void recordBeforeRenderPass(org.lwjgl.vulkan.VkCommandBuffer commandBuffer)
	{
		sceneRenderer.recordBeforeRenderPass(commandBuffer);
	}

	@Override
	public void close()
	{
		sceneRenderer.close();
	}
}
