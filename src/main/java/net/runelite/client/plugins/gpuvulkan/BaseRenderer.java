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
	public void captureScene(Scene scene)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.captureScene(scene);
	}

	@Override
	public void captureModel(Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.captureModel(model, orientation, worldX, worldY, worldZ);
	}

	@Override
	public void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.captureModel(projection, model, orientation, worldX, worldY, worldZ);
	}

	@Override
	public void setLevelRange(int minLevel, int maxLevel)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.setLevelRange(minLevel, maxLevel);
	}

	@Override
	public void setHideRoofIds(Set<Integer> hideRoofIds)
	{
		if (sceneRenderer == null) return;
		sceneRenderer.setHideRoofIds(hideRoofIds);
	}

	@Override
	public void recordBeforeRenderPass(VkCommandBuffer commandBuffer)
	{
		if (interfaceRenderer == null) return;
		interfaceRenderer.recordCopyToImage(commandBuffer);
	}

	@Override
	public void recordRenderPass(VulkanFrameContext frame)
	{
		if (sceneRenderer != null)
		{
			sceneRenderer.recordDraw(frame);
		}
		if (interfaceRenderer != null)
		{
			interfaceRenderer.recordDraw(frame.commandBuffer(), frame.overlayColor());
		}
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
