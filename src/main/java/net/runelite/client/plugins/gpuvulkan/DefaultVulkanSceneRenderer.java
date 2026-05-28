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
		DrawCallbackStats stats, boolean alphaToCoverage, boolean modelComputeDebugDraw)
	{
		this.sceneRenderer = new SceneRenderer(device, sync, renderPass, textureArray, stats,
			alphaToCoverage, modelComputeDebugDraw);
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
	public void setModelComputeDebugDraw(boolean enabled)
	{
		sceneRenderer.setModelComputeDebugDraw(enabled);
	}

	@Override
	public void recordDraw(VulkanFrameContext frame)
	{
		sceneRenderer.recordDraw(frame.commandBuffer(), frame.sceneMvp(), frame.brightness(),
			frame.cameraX(), frame.cameraZ(), frame.drawDistanceTiles(), frame.fogDepthTiles(),
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
