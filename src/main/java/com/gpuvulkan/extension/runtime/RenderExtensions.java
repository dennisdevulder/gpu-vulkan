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
	private boolean closed;

	RenderExtensions(VulkanRenderContext context)
	{
		this.context = context;
	}

	void register(VulkanRenderExtension extension)
	{
		synchronized (this)
		{
			if (closed)
			{
				throw new IllegalStateException("Render extension registry is closed");
			}
			extensions.add(extension);
		}
		try
		{
			extension.onRegistered(context);
		}
		catch (RuntimeException e)
		{
			synchronized (this)
			{
				extensions.remove(extension);
			}
			closeFailedExtension(extension, "register", e);
		}
	}

	void unregister(VulkanRenderExtension extension)
	{
		boolean removed;
		synchronized (this)
		{
			removed = extensions.remove(extension);
		}
		if (removed)
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
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				extension.beginFrame();
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, "beginFrame", e);
			}
		}
	}

	void captureScene(Scene scene)
	{
		forEachExtension("captureScene", extension -> extension.captureScene(scene));
	}

	void captureDynamicPending()
	{
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				extension.captureDynamicPending();
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, "captureDynamicPending", e);
			}
		}
	}

	void invalidateCapturedScene()
	{
		forEachExtension("invalidateCapturedScene", VulkanRenderExtension::invalidateCapturedScene);
	}

	void invalidateZone(Scene scene, int zx, int zz)
	{
		forEachExtension("invalidateZone", extension -> extension.invalidateZone(scene, zx, zz));
	}

	void rebuildDirtyZones(Scene scene)
	{
		forEachExtension("rebuildDirtyZones", extension -> extension.rebuildDirtyZones(scene));
	}

	void captureSkybox(Scene scene)
	{
		forEachExtension("captureSkybox", extension -> extension.captureSkybox(scene));
	}

	void drawPass(int pass)
	{
		forEachExtension("drawPass", extension -> extension.drawPass(pass));
	}

	void captureModel(Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				extension.captureModel(model, orientation, worldX, worldY, worldZ);
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, "captureModel", e);
			}
		}
	}

	void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				extension.captureModel(projection, model, orientation, worldX, worldY, worldZ);
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, "captureModelSorted", e);
			}
		}
	}

	void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ, int renderMode)
	{
		captureModel(projection, model, orientation, worldX, worldY, worldZ, renderMode, false);
	}

	void captureModel(Projection projection, Model model, int orientation, int worldX, int worldY, int worldZ,
		int renderMode, boolean actorModel)
	{
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				extension.captureModel(projection, model, orientation, worldX, worldY, worldZ, renderMode, actorModel);
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, "captureModelSorted", e);
			}
		}
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
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				extension.uploadUiPixels(pixels, width, height);
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, "uploadUiPixels", e);
			}
		}
	}

	ScenePassRedirect scenePassRedirect()
	{
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				ScenePassRedirect redirect = extension.scenePassRedirect();
				if (redirect != null)
				{
					return redirect;
				}
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, "scenePassRedirect", e);
			}
		}
		return null;
	}

	void recordBeforeRenderPass(VkCommandBuffer commandBuffer)
	{
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				extension.recordBeforeRenderPass(commandBuffer);
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, "recordBeforeRenderPass", e);
			}
		}
	}

	void recordScenePass(VulkanFrameContext frame)
	{
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				extension.recordScenePass(frame);
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, "recordScenePass", e);
			}
		}
	}

	void recordUiPass(VulkanFrameContext frame)
	{
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				extension.recordUiPass(frame);
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, "recordUiPass", e);
			}
		}
	}

	void recordRenderPass(VulkanFrameContext frame)
	{
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				extension.recordRenderPass(frame);
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, "recordRenderPass", e);
			}
		}
	}

	void recordAfterComposite(VulkanPostFrameContext frame)
	{
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				extension.recordAfterComposite(frame);
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, "recordAfterComposite", e);
			}
		}
	}

	synchronized void beforeSwapchainRebuild()
	{
		forEachExtension("beforeSwapchainRebuild", VulkanRenderExtension::beforeSwapchainRebuild);
	}

	@Override
	public void close()
	{
		List<VulkanRenderExtension> closing;
		synchronized (this)
		{
			closed = true;
			closing = new ArrayList<>(extensions);
			Collections.reverse(closing);
			extensions.clear();
		}
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
		for (VulkanRenderExtension extension : snapshot())
		{
			try
			{
				call.accept(extension);
			}
			catch (RuntimeException e)
			{
				removeFailedExtension(extension);
				closeFailedExtension(extension, operation, e);
			}
		}
	}

	private synchronized List<VulkanRenderExtension> snapshot()
	{
		return new ArrayList<>(extensions);
	}

	private synchronized void removeFailedExtension(VulkanRenderExtension extension)
	{
		extensions.remove(extension);
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
