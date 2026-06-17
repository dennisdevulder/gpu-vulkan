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
package com.gpuvulkan;

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

	synchronized void register(VulkanRenderExtension extension)
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

	synchronized void unregister(VulkanRenderExtension extension)
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

	synchronized void onConfigChanged(ConfigChanged event)
	{
		forEachExtension("onConfigChanged", extension -> extension.onConfigChanged(event));
	}

	synchronized void beginFrame()
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				extension.beginFrame();
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, "beginFrame", e);
			}
		}
	}

	synchronized void captureScene(Scene scene)
	{
		forEachExtension("captureScene", extension -> extension.captureScene(scene));
	}

	synchronized void captureDynamicPending()
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				extension.captureDynamicPending();
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, "captureDynamicPending", e);
			}
		}
	}

	synchronized void invalidateCapturedScene()
	{
		forEachExtension("invalidateCapturedScene", VulkanRenderExtension::invalidateCapturedScene);
	}

	synchronized void invalidateZone(Scene scene, int zx, int zz)
	{
		forEachExtension("invalidateZone", extension -> extension.invalidateZone(scene, zx, zz));
	}

	synchronized void rebuildDirtyZones(Scene scene)
	{
		forEachExtension("rebuildDirtyZones", extension -> extension.rebuildDirtyZones(scene));
	}

	synchronized void captureSkybox(Scene scene)
	{
		forEachExtension("captureSkybox", extension -> extension.captureSkybox(scene));
	}

	synchronized void drawPass(int pass)
	{
		forEachExtension("drawPass", extension -> extension.drawPass(pass));
	}

	synchronized void captureModel(Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				extension.captureModel(model, orientation, worldX, worldY, worldZ);
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, "captureModel", e);
			}
		}
	}

	synchronized void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				extension.captureModel(projection, model, orientation, worldX, worldY, worldZ);
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, "captureModelSorted", e);
			}
		}
	}

	synchronized void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ, int renderMode)
	{
		captureModel(projection, model, orientation, worldX, worldY, worldZ, renderMode, false);
	}

	synchronized void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ,
		int renderMode, boolean actorModel)
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				extension.captureModel(projection, model, orientation, worldX, worldY, worldZ, renderMode, actorModel);
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, "captureModelSorted", e);
			}
		}
	}

	synchronized void setLevelRange(int minLevel, int maxLevel)
	{
		forEachExtension("setLevelRange", extension -> extension.setLevelRange(minLevel, maxLevel));
	}

	synchronized void setLevelRange(int minLevel, int currentLevel, int maxLevel)
	{
		forEachExtension("setLevelRange", extension -> extension.setLevelRange(minLevel, currentLevel, maxLevel));
	}

	synchronized void setHideRoofIds(Set<Integer> hideRoofIds)
	{
		forEachExtension("setHideRoofIds", extension -> extension.setHideRoofIds(hideRoofIds));
	}

	synchronized void collectDebugMetrics(GpuVulkanDebugMetrics metrics)
	{
		forEachExtension("collectDebugMetrics", extension -> extension.collectDebugMetrics(metrics));
	}

	synchronized void uploadUiPixels(int[] pixels, int width, int height)
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				extension.uploadUiPixels(pixels, width, height);
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, "uploadUiPixels", e);
			}
		}
	}

	synchronized ScenePassRedirect scenePassRedirect()
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				ScenePassRedirect redirect = extension.scenePassRedirect();
				if (redirect != null)
				{
					return redirect;
				}
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, "scenePassRedirect", e);
			}
		}
		return null;
	}

	synchronized void recordBeforeRenderPass(VkCommandBuffer commandBuffer)
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				extension.recordBeforeRenderPass(commandBuffer);
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, "recordBeforeRenderPass", e);
			}
		}
	}

	synchronized void recordScenePass(VulkanFrameContext frame)
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				extension.recordScenePass(frame);
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, "recordScenePass", e);
			}
		}
	}

	synchronized void recordUiPass(VulkanFrameContext frame)
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				extension.recordUiPass(frame);
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, "recordUiPass", e);
			}
		}
	}

	synchronized void recordRenderPass(VulkanFrameContext frame)
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				extension.recordRenderPass(frame);
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, "recordRenderPass", e);
			}
		}
	}

	synchronized void recordAfterComposite(VulkanPostFrameContext frame)
	{
		for (int i = 0; i < extensions.size(); )
		{
			VulkanRenderExtension extension = extensions.get(i);
			try
			{
				extension.recordAfterComposite(frame);
				i++;
			}
			catch (RuntimeException e)
			{
				extensions.remove(i);
				closeFailedExtension(extension, "recordAfterComposite", e);
			}
		}
	}

	synchronized void beforeSwapchainRebuild()
	{
		forEachExtension("beforeSwapchainRebuild", VulkanRenderExtension::beforeSwapchainRebuild);
	}

	@Override
	public synchronized void close()
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
