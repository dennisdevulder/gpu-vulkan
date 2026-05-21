package net.runelite.client.plugins.gpuvulkan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import net.runelite.client.events.ConfigChanged;
import org.lwjgl.vulkan.VkCommandBuffer;

@Slf4j
final class RenderExtensions implements AutoCloseable
{
	private final VulkanRenderContext context;
	private final List<VulkanRenderExtension> extensions = new ArrayList<>();

	RenderExtensions(VulkanRenderContext context)
	{
		this.context = context;
	}

	void register(VulkanRenderExtension extension)
	{
		try
		{
			extensions.add(extension);
			extension.onRegistered(context);
		}
		catch (RuntimeException e)
		{
			extensions.remove(extension);
			closeFailedExtension(extension, "register", e);
		}
	}

	void unregister(VulkanRenderExtension extension)
	{
		if (extensions.remove(extension))
		{
			try
			{
				extension.close();
			}
			catch (RuntimeException e)
			{
				log.warn("Vulkan render extension {} failed during unregister close", extensionName(extension), e);
			}
		}
	}

	void onConfigChanged(ConfigChanged event)
	{
		forEachExtension("onConfigChanged", extension -> extension.onConfigChanged(event));
	}

	void beginFrame()
	{
		forEachExtension("beginFrame", VulkanRenderExtension::beginFrame);
	}

	void captureScene(Scene scene)
	{
		forEachExtension("captureScene", extension -> extension.captureScene(scene));
	}

	void captureDynamicPending()
	{
		forEachExtension("captureDynamicPending", VulkanRenderExtension::captureDynamicPending);
	}

	void invalidateCapturedScene()
	{
		forEachExtension("invalidateCapturedScene", VulkanRenderExtension::invalidateCapturedScene);
	}

	void captureModel(Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		forEachExtension("captureModel", extension -> extension.captureModel(model, orientation, worldX, worldY, worldZ));
	}

	void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		forEachExtension("captureModelSorted", extension -> extension.captureModel(projection, model, orientation, worldX, worldY, worldZ));
	}

	void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ, int renderMode)
	{
		forEachExtension("captureModelSorted", extension -> extension.captureModel(projection, model, orientation, worldX, worldY, worldZ, renderMode));
	}

	void setLevelRange(int minLevel, int maxLevel)
	{
		forEachExtension("setLevelRange", extension -> extension.setLevelRange(minLevel, maxLevel));
	}

	void setLevelRange(int minLevel, int currentLevel, int maxLevel)
	{
		forEachExtension("setLevelRange", extension -> extension.setLevelRange(minLevel, currentLevel, maxLevel));
	}

	void setHideRoofIds(Set<Integer> hideRoofIds)
	{
		forEachExtension("setHideRoofIds", extension -> extension.setHideRoofIds(hideRoofIds));
	}

	void collectDebugMetrics(GpuVulkanDebugMetrics metrics)
	{
		forEachExtension("collectDebugMetrics", extension -> extension.collectDebugMetrics(metrics));
	}

	void uploadUiPixels(int[] pixels, int width, int height)
	{
		forEachExtension("uploadUiPixels", extension -> extension.uploadUiPixels(pixels, width, height));
	}

	void recordBeforeRenderPass(VkCommandBuffer commandBuffer)
	{
		forEachExtension("recordBeforeRenderPass", extension -> extension.recordBeforeRenderPass(commandBuffer));
	}

	void recordRenderPass(VulkanFrameContext frame)
	{
		forEachExtension("recordRenderPass", extension -> extension.recordRenderPass(frame));
	}

	@Override
	public void close()
	{
		List<VulkanRenderExtension> closing = new ArrayList<>(extensions);
		Collections.reverse(closing);
		extensions.clear();
		for (VulkanRenderExtension extension : closing)
		{
			try
			{
				extension.close();
			}
			catch (RuntimeException e)
			{
				log.warn("Vulkan render extension {} failed during close", extensionName(extension), e);
			}
		}
	}

	private void forEachExtension(String operation, ExtensionCall call)
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				call.accept(extension);
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, operation, e);
			}
		}
	}

	private void closeFailedExtension(VulkanRenderExtension extension, String operation, RuntimeException failure)
	{
		log.warn("Disabling Vulkan render extension {} after {} failed", extensionName(extension), operation, failure);
		try
		{
			extension.close();
		}
		catch (RuntimeException closeFailure)
		{
			log.warn("Vulkan render extension {} also failed during close", extensionName(extension), closeFailure);
		}
	}

	private static String extensionName(VulkanRenderExtension extension)
	{
		return extension.getClass().getName();
	}

	private interface ExtensionCall
	{
		void accept(VulkanRenderExtension extension);
	}
}
