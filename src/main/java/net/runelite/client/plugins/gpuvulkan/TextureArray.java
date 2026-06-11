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

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.PointerBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Static 2D-array texture for the OSRS texture set. Layer 0 is a white
 * "no-texture" tile; OSRS texture id N goes into layer N+1. Built once
 * at startup; OSRS pixels may change (animated water) but slots are
 * stable.
 */
@lombok.extern.slf4j.Slf4j
final class TextureArray implements AutoCloseable
{
	static final int LAYER_SIZE = 128;
	private static final int FORMAT = VK_FORMAT_B8G8R8A8_UNORM;
	/** 128 = 2^7, so 8 mip levels including base (128 → 1). */
	private static final int MIP_LEVELS = 8;

	private final VulkanDevice device;
	private long image;
	private long memory;
	private long view;
	private long sampler;
	private final int layerCount;
	/** Per-layer UV-scroll vector (texels-per-tick), std140-padded to
	 *  vec4. Bound as set 0 binding 1 by SceneRenderer. */
	private final Buffer animationUbo;

	TextureArray(VulkanDevice device, TextureProvider tp, int requestedAnisotropy)
	{
		this.device = device;
		Texture[] osrsTextures = tp == null ? new Texture[0] : tp.getTextures();
		this.layerCount = osrsTextures.length + 1;

		try (MemoryStack stack = stackPush())
		{
			// TRANSFER_SRC required for the mip-chain vkCmdBlitImage cascade.
			VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
				.sType$Default()
				.imageType(VK_IMAGE_TYPE_2D)
				.format(FORMAT)
				.extent(e -> e.width(LAYER_SIZE).height(LAYER_SIZE).depth(1))
				.mipLevels(MIP_LEVELS)
				.arrayLayers(layerCount)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

			LongBuffer pImage = stack.mallocLong(1);
			Vk.check("vkCreateImage (textureArray)", vkCreateImage(device.handle(), info, null, pImage));
			image = pImage.get(0);

			VkMemoryRequirements memReq = VkMemoryRequirements.calloc(stack);
			vkGetImageMemoryRequirements(device.handle(), image, memReq);
			int memType = Buffer.findMemoryType(device, memReq.memoryTypeBits(),
				VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);
			VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
				.sType$Default()
				.allocationSize(memReq.size())
				.memoryTypeIndex(memType);
			LongBuffer pMem = stack.mallocLong(1);
			Vk.check("vkAllocateMemory (textureArray)", vkAllocateMemory(device.handle(), alloc, null, pMem));
			memory = pMem.get(0);
			Vk.check("vkBindImageMemory (textureArray)", vkBindImageMemory(device.handle(), image, memory, 0));

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
				.sType$Default()
				.image(image)
				.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
				.format(FORMAT);
			viewInfo.subresourceRange()
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0).levelCount(MIP_LEVELS)
				.baseArrayLayer(0).layerCount(layerCount);
			LongBuffer pView = stack.mallocLong(1);
			Vk.check("vkCreateImageView (textureArray)", vkCreateImageView(device.handle(), viewInfo, null, pView));
			view = pView.get(0);

			float aniso = Math.max(1f, Math.min((float) requestedAnisotropy, device.maxSamplerAnisotropy()));
			boolean anisoOn = aniso > 1f && device.supportsSamplerAnisotropy();
			// LANDMINE: magFilter must stay NEAREST. OSRS encodes texture
			// transparency as rgb==0 → alpha==0; LINEAR mag bilinear-blends
			// that into adjacent opaque texels and the wall vanishes on
			// the alpha-discard test. NEAREST mag keeps the alpha binary.
			// LINEAR minFilter is safe — scene.frag discards at < 0.5 so
			// partial-alpha mip texels still render.
			VkSamplerCreateInfo sampInfo = VkSamplerCreateInfo.calloc(stack)
				.sType$Default()
				.magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_LINEAR)
				.mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
				.addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.anisotropyEnable(anisoOn)
				.maxAnisotropy(anisoOn ? aniso : 1.0f)
				.minLod(0f)
				.maxLod((float) (MIP_LEVELS - 1))
				.unnormalizedCoordinates(false);
			LongBuffer pSamp = stack.mallocLong(1);
			Vk.check("vkCreateSampler (textureArray)", vkCreateSampler(device.handle(), sampInfo, null, pSamp));
			sampler = pSamp.get(0);
		}

		// Transient command pool decouples upload from the main pool.
		long localPool;
		try (MemoryStack stack = stackPush())
		{
			VkCommandPoolCreateInfo cpInfo = VkCommandPoolCreateInfo.calloc(stack)
				.sType$Default()
				.flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
				.queueFamilyIndex(device.graphicsQueueFamily());
			LongBuffer pPool = stack.mallocLong(1);
			Vk.check("vkCreateCommandPool (textureArray)", vkCreateCommandPool(device.handle(), cpInfo, null, pPool));
			localPool = pPool.get(0);
		}
		try
		{
			// null tp = empty texture set (layer 0 reserve only), nothing to upload.
			if (tp != null)
			{
				// Load at brightness=1.0 so dark pixels don't clamp to 0x000000,
				// which the alpha-from-zero encoding below would mark transparent.
				// scene.frag re-applies brightness via push constant.
				double savedBrightness = tp.getBrightness();
				tp.setBrightness(1.0);
				try
				{
					uploadAllLayers(localPool, osrsTextures, tp);
				}
				finally
				{
					tp.setBrightness(savedBrightness);
				}
			}
		}
		finally
		{
			vkDestroyCommandPool(device.handle(), localPool, null);
		}

		animationUbo = buildAnimationUbo(osrsTextures);
	}

	/** LANDMINE: shader declares {@code vec4 anim[ANIM_UBO_COUNT]}; the
	 *  bound UBO must be at least this large or RADV hangs the GPU on
	 *  out-of-range accesses, even with a runtime shader guard. */
	private static final int ANIM_UBO_COUNT = 256;

	/** std140 header preceding anim[]: vec4 fogScene (scene.vert). */
	private static final int UBO_HEADER_BYTES = 16;

	private Buffer buildAnimationUbo(Texture[] osrsTextures)
	{
		final int bytesPerEntry = 16; // std140: vec2 padded to vec4
		long sizeBytes = UBO_HEADER_BYTES + (long) ANIM_UBO_COUNT * bytesPerEntry;
		Buffer ubo = new Buffer(device, sizeBytes,
			VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		ubo.mapPersistent();
		ByteBuffer mapped = ubo.mappedByteBuffer().order(java.nio.ByteOrder.nativeOrder());
		// fogScene header: default to the core-scene fog window (tiles 1..103);
		// setFogSceneEdges widens it once the expanded-map extent is known.
		mapped.putFloat(1f * 128f).putFloat(103f * 128f).putFloat(0).putFloat(0);
		// Zero-fill so unused layers read (0,0,0,0).
		for (int i = 0; i < ANIM_UBO_COUNT; i++)
		{
			mapped.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
		}
		mapped.position(UBO_HEADER_BYTES + 16); // skip layer 0 (white reserve)
		int written = Math.min(osrsTextures.length, ANIM_UBO_COUNT - 1);
		for (int i = 0; i < written; i++)
		{
			Texture t = osrsTextures[i];
			float u = 0f, v = 0f;
			if (t != null)
			{
				// OSRS animation directions: 1=N(-V), 2=W(-U), 3=S(+V), 4=E(+U).
				switch (t.getAnimationDirection())
				{
					case 1: v = -1f; break;
					case 2: u = -1f; break;
					case 3: v =  1f; break;
					case 4: u =  1f; break;
				}
				int speed = t.getAnimationSpeed();
				u *= speed;
				v *= speed;
			}
			mapped.putFloat(u).putFloat(v).putFloat(0).putFloat(0);
		}
		return ubo;
	}

	long animationUboHandle() { return animationUbo.handle(); }
	long animationUboSize()   { return UBO_HEADER_BYTES + (long) ANIM_UBO_COUNT * 16L; }

	/** Fog window clamp edges in world units (scene.vert fogScene.xy),
	 *  scaled with the engine's expanded-map-loading chunks like stock's
	 *  FOG_SCENE_EDGE_MIN/MAX. Written at scene capture: an 8-byte coherent
	 *  store of a value that only changes with the expanded-chunks config,
	 *  so in-flight readers can't observe a meaningful mix. */
	void setFogSceneEdges(float minWorld, float maxWorld)
	{
		ByteBuffer mapped = animationUbo.mappedByteBuffer().order(java.nio.ByteOrder.nativeOrder());
		mapped.putFloat(0, minWorld);
		mapped.putFloat(4, maxWorld);
	}

	long view() { return view; }
	long sampler() { return sampler; }
	int layerCount() { return layerCount; }

	private static final int BYTES_PER_LAYER = LAYER_SIZE * LAYER_SIZE * 4;

	private void uploadAllLayers(long commandPool, Texture[] osrsTextures, TextureProvider tp)
	{
		// Single staging buffer covers all layers; one copy region per layer.
		Buffer staging = new Buffer(device, (long) BYTES_PER_LAYER * layerCount,
			VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		try
		{
			staging.mapPersistent();
			fillLayerStaging(staging.mappedByteBuffer(), osrsTextures, tp);
			log.info("Texture array initialized: {} textures, {} layers", osrsTextures.length, layerCount);
			submitLayerUpload(commandPool, staging);
		}
		finally
		{
			staging.close();
		}
	}

	private void fillLayerStaging(ByteBuffer mapped, Texture[] osrsTextures, TextureProvider tp)
	{
		// Layer 0: solid white so untextured faces multiply through to HSL.
		for (int i = 0; i < BYTES_PER_LAYER / 4; i++)
		{
			mapped.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF);
		}

		for (int t = 0; t < osrsTextures.length; t++)
		{
			int[] argb = osrsTextures[t] == null ? null : tp.load(t);
			if (argb == null || argb.length < LAYER_SIZE * LAYER_SIZE)
			{
				// Engine hasn't loaded this texture — grey fallback so the
				// face doesn't render as a black hole.
				for (int p = 0; p < LAYER_SIZE * LAYER_SIZE; p++)
				{
					mapped.put((byte) 0x80).put((byte) 0x80).put((byte) 0x80).put((byte) 0xFF);
				}
			}
			if (argb != null && argb.length >= LAYER_SIZE * LAYER_SIZE)
			{
				// OSRS encodes transparency as rgb==0 → alpha 0; opaque
				// otherwise. scene.frag uses an alpha-threshold discard.
				for (int p = 0; p < LAYER_SIZE * LAYER_SIZE; p++)
				{
					int rgb = argb[p];
					if (rgb == 0)
					{
						mapped.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
					}
					else
					{
						mapped.put((byte) (rgb         & 0xFF))
							  .put((byte) ((rgb >>  8) & 0xFF))
							  .put((byte) ((rgb >> 16) & 0xFF))
							  .put((byte) 0xFF);
					}
				}
			}
		}
	}

	// One-shot command buffer: copy all layers, build the mip cascade,
	// leave every mip SHADER_READ_ONLY, wait for completion.
	private void submitLayerUpload(long commandPool, Buffer staging)
	{
		try (MemoryStack stack = stackPush())
		{
			VkCommandBufferAllocateInfo cbInfo = VkCommandBufferAllocateInfo.calloc(stack)
				.sType$Default()
				.commandPool(commandPool)
				.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
				.commandBufferCount(1);
			PointerBuffer pCmd = stack.mallocPointer(1);
			Vk.check("vkAllocateCommandBuffers (textureArray)", vkAllocateCommandBuffers(device.handle(), cbInfo, pCmd));
			VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device.handle());

			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
				.sType$Default()
				.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			vkBeginCommandBuffer(cmd, begin);

			// All mips → TRANSFER_DST. Base level gets memcpy'd; higher
			// levels are blit destinations in the mip cascade.
			transitionAllLayers(cmd, 0, MIP_LEVELS,
				VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
				0, VK_ACCESS_TRANSFER_WRITE_BIT);
			recordLayerCopies(cmd, staging);
			recordMipCascade(cmd);

			Vk.check("vkEndCommandBuffer (textureArray upload)", vkEndCommandBuffer(cmd));

			VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
				.sType$Default()
				.pCommandBuffers(stack.pointers(cmd));
			Vk.check("vkQueueSubmit (textureArray upload)",
				vkQueueSubmit(device.graphicsQueue(), submit, VK_NULL_HANDLE));
			Vk.check("vkQueueWaitIdle (textureArray upload)",
				vkQueueWaitIdle(device.graphicsQueue()));

			vkFreeCommandBuffers(device.handle(), commandPool, cmd);
		}
	}

	private void recordLayerCopies(VkCommandBuffer cmd, Buffer staging)
	{
		try (MemoryStack stack = stackPush())
		{
			VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(layerCount, stack);
			for (int layer = 0; layer < layerCount; layer++)
			{
				regions.get(layer)
					.bufferOffset((long) layer * BYTES_PER_LAYER)
					.bufferRowLength(0)
					.bufferImageHeight(0)
					.imageOffset(o -> o.set(0, 0, 0))
					.imageExtent(e -> e.width(LAYER_SIZE).height(LAYER_SIZE).depth(1));
				regions.get(layer).imageSubresource()
					.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
					.mipLevel(0)
					.baseArrayLayer(layer).layerCount(1);
			}
			vkCmdCopyBufferToImage(cmd, staging.handle(), image,
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);
		}
	}

	// Blit mip i-1 → i, transitioning each source to SHADER_READ_ONLY once
	// it has been read; the last mip transitions from TRANSFER_DST at the end.
	private void recordMipCascade(VkCommandBuffer cmd)
	{
		int srcW = LAYER_SIZE, srcH = LAYER_SIZE;
		for (int level = 1; level < MIP_LEVELS; level++)
		{
			int dstW = Math.max(1, srcW >> 1);
			int dstH = Math.max(1, srcH >> 1);

			transitionAllLayers(cmd, level - 1, 1,
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
				VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);

			try (MemoryStack stack = stackPush())
			{
				VkImageBlit.Buffer blits = VkImageBlit.calloc(layerCount, stack);
				for (int layer = 0; layer < layerCount; layer++)
				{
					VkImageBlit b = blits.get(layer);
					b.srcOffsets(0).set(0, 0, 0);
					b.srcOffsets(1).set(srcW, srcH, 1);
					b.srcSubresource()
						.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
						.mipLevel(level - 1)
						.baseArrayLayer(layer).layerCount(1);
					b.dstOffsets(0).set(0, 0, 0);
					b.dstOffsets(1).set(dstW, dstH, 1);
					b.dstSubresource()
						.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
						.mipLevel(level)
						.baseArrayLayer(layer).layerCount(1);
				}
				vkCmdBlitImage(cmd,
					image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
					image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
					blits, VK_FILTER_LINEAR);
			}

			transitionAllLayers(cmd, level - 1, 1,
				VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
				VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
				VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT);

			srcW = dstW;
			srcH = dstH;
		}

		transitionAllLayers(cmd, MIP_LEVELS - 1, 1,
			VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
			VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
			VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
	}

	private void transitionAllLayers(VkCommandBuffer cmd, int baseMip, int levelCount,
									 int oldLayout, int newLayout,
									 int srcStage, int dstStage,
									 int srcAccess, int dstAccess)
	{
		try (MemoryStack stack = stackPush())
		{
			VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
			barrier.get(0)
				.sType$Default()
				.srcAccessMask(srcAccess).dstAccessMask(dstAccess)
				.oldLayout(oldLayout).newLayout(newLayout)
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.image(image)
				.subresourceRange(r -> r
					.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
					.baseMipLevel(baseMip).levelCount(levelCount)
					.baseArrayLayer(0).layerCount(this.layerCount));
			vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
		}
	}

	@Override
	public void close()
	{
		// Defensive drain in case Disposables ordering changes — without
		// it a reorder can crash on destroy-while-in-use.
		vkDeviceWaitIdle(device.handle());
		if (animationUbo != null) animationUbo.close();
		if (sampler != VK_NULL_HANDLE)
		{
			vkDestroySampler(device.handle(), sampler, null);
			sampler = VK_NULL_HANDLE;
		}
		if (view != VK_NULL_HANDLE)
		{
			vkDestroyImageView(device.handle(), view, null);
			view = VK_NULL_HANDLE;
		}
		if (image != VK_NULL_HANDLE)
		{
			vkDestroyImage(device.handle(), image, null);
			image = VK_NULL_HANDLE;
		}
		if (memory != VK_NULL_HANDLE)
		{
			vkFreeMemory(device.handle(), memory, null);
			memory = VK_NULL_HANDLE;
		}
	}
}
