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

	default void setModelComputeDebugDraw(boolean enabled)
	{
	}

	default void setModelComputeReplacement(boolean enabled)
	{
	}

	void recordDraw(VulkanFrameContext frame);

	default void recordBeforeRenderPass(VkCommandBuffer commandBuffer)
	{
	}

	@Override
	void close();
}
