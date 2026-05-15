package net.runelite.client.plugins.gpuvulkan;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
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
 * Static 2D-array texture for the OSRS texture set. Layer N holds texture
 * with internal ID N. The shader uses the (per-vertex) {@code textureId}
 * attribute as the array layer to sample. Layer 0 is reserved as a
 * "no-texture" white tile so faces with {@code textureId == 0} get a 1×
 * tint of their HSL colour.
 *
 * <p>Built once on plugin start and never resized — OSRS texture pixels can
 * change (e.g. animated water) but their slots are stable.
 */
@lombok.extern.slf4j.Slf4j
final class TextureArray implements AutoCloseable
{
	static final int LAYER_SIZE = 128;            // OSRS textures are 128×128
	private static final int FORMAT = VK_FORMAT_B8G8R8A8_UNORM;

	private final VulkanDevice device;
	private final long image;
	private final long memory;
	private final long view;
	private final long sampler;
	private final int layerCount;
	/** Per-texture-layer scroll vector (units = texels-per-tick). One vec2 per
	 *  layer, std140-padded to vec4 (so 16 bytes per layer = layerCount*16
	 *  total). Bound as set 0 binding 1 by SceneRenderer. */
	private final Buffer animationUbo;

	TextureArray(VulkanDevice device, TextureProvider tp, int requestedAnisotropy)
	{
		this.device = device;
		Texture[] osrsTextures = tp == null ? new Texture[0] : tp.getTextures();
		// Reserve layer 0 for the white "no-texture" tile so 0 means "no texture".
		// OSRS texture id N goes into layer N+1.
		this.layerCount = osrsTextures.length + 1;

		try (MemoryStack stack = stackPush())
		{
			VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
				.sType$Default()
				.imageType(VK_IMAGE_TYPE_2D)
				.format(FORMAT)
				.extent(e -> e.width(LAYER_SIZE).height(LAYER_SIZE).depth(1))
				.mipLevels(1)
				.arrayLayers(layerCount)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
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
			vkBindImageMemory(device.handle(), image, memory, 0);

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
				.sType$Default()
				.image(image)
				.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
				.format(FORMAT);
			viewInfo.subresourceRange()
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0).levelCount(1)
				.baseArrayLayer(0).layerCount(layerCount);
			LongBuffer pView = stack.mallocLong(1);
			Vk.check("vkCreateImageView (textureArray)", vkCreateImageView(device.handle(), viewInfo, null, pView));
			view = pView.get(0);

			// Anisotropic filtering — clamped to whatever the GPU allows.
			// Caller passes the desired level (1 = off, up to 16). Stock
			// GpuPlugin exposes the same config; default is 1 (off).
			float aniso = Math.max(1f, Math.min((float) requestedAnisotropy, device.maxSamplerAnisotropy()));
			// NEAREST filtering, matching stock GpuPlugin's TextureManager.java:69-70
			// — "pixel nature of the game means that nearest filtering looks best
			// for objects up close." With LINEAR, bilinear interpolation between an
			// opaque texel (alpha=1.0) and an adjacent rgb==0/alpha==0 texel
			// produces alpha < 1.0; the fragment shader's
			// `if (texSample.a < 1.0) discard;` then eats the surface. The visible
			// symptom is solid textured walls vanishing because OSRS textures
			// commonly have rgb==0 pixels scattered as seam/edge markers, and
			// linear filtering halos them across the wall. Stock avoids this by
			// using GL_NEAREST. Anisotropy stays off — it has no effect with
			// NEAREST filter and the spec disallows the combination on some
			// implementations.
			VkSamplerCreateInfo sampInfo = VkSamplerCreateInfo.calloc(stack)
				.sType$Default()
				.magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
				.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
				.addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.anisotropyEnable(false)
				.maxAnisotropy(1.0f)
				.unnormalizedCoordinates(false);
			LongBuffer pSamp = stack.mallocLong(1);
			Vk.check("vkCreateSampler (textureArray)", vkCreateSampler(device.handle(), sampInfo, null, pSamp));
			sampler = pSamp.get(0);
		}

		// Self-allocated transient command pool — keeps the texture upload
		// decoupled from VulkanRenderer's main rendering pool.
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
			// Match stock GpuPlugin's TextureManager.uploadTextures: load at
			// brightness=1.0 so we get unmodified RGB. The user's brightness
			// slider would otherwise darken texture pixels and clamp the
			// dark ones to 0x000000, which the alpha-from-zero encoding
			// below would then mark as transparent. Brightness is re-applied
			// per-fragment in scene.frag via the brightness push constant.
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
		finally
		{
			vkDestroyCommandPool(device.handle(), localPool, null);
		}

		// Animation UBO: vec2 per layer, std140-padded to vec4 (16 B each).
		// Vert shader uses these to scroll UV by tick * anim * (1/128) per
		// frame, matching stock GpuPlugin's textureAnimations uniform.
		animationUbo = buildAnimationUbo(osrsTextures);
	}

	/** Shader declares {@code vec4 anim[ANIM_UBO_COUNT]}; the bound UBO must
	 *  be at least this large or some drivers (RADV in particular) hang the
	 *  GPU on out-of-range accesses even when the shader has a runtime guard. */
	private static final int ANIM_UBO_COUNT = 256;

	private Buffer buildAnimationUbo(Texture[] osrsTextures)
	{
		final int bytesPerEntry = 16; // std140: vec2 padded to vec4
		long sizeBytes = (long) ANIM_UBO_COUNT * bytesPerEntry;
		Buffer ubo = new Buffer(device, sizeBytes,
			VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		ubo.mapPersistent();
		ByteBuffer mapped = ubo.mappedByteBuffer().order(java.nio.ByteOrder.nativeOrder());
		// Zero-fill the whole buffer first so any layer beyond the actual
		// OSRS texture count reads (0,0,0,0).
		for (int i = 0; i < ANIM_UBO_COUNT; i++)
		{
			mapped.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
		}
		mapped.position(16); // skip layer 0 (white reserve)
		int written = Math.min(osrsTextures.length, ANIM_UBO_COUNT - 1);
		for (int i = 0; i < written; i++)
		{
			Texture t = osrsTextures[i];
			float u = 0f, v = 0f;
			if (t != null)
			{
				// Stock TextureManager.computeTextureAnimations:
				//   1 = north (-V), 2 = west (-U), 3 = south (+V), 4 = east (+U).
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
	long animationUboSize()   { return (long) ANIM_UBO_COUNT * 16L; }

	long view() { return view; }
	long sampler() { return sampler; }
	int layerCount() { return layerCount; }

	private void uploadAllLayers(long commandPool, Texture[] osrsTextures, TextureProvider tp)
	{
		final int bytesPerLayer = LAYER_SIZE * LAYER_SIZE * 4;
		final long totalBytes = (long) bytesPerLayer * layerCount;

		// Single staging buffer for all layers; copy each layer's pixels into a
		// distinct offset and one vkCmdCopyBufferToImage per layer.
		Buffer staging = new Buffer(device, totalBytes,
			VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		try
		{
			staging.mapPersistent();
			ByteBuffer mapped = staging.mappedByteBuffer();

			// Layer 0: solid white (1.0, 1.0, 1.0, 1.0) — applied to faces that
			// don't reference a texture so the HSL colour shows through unchanged.
			for (int i = 0; i < bytesPerLayer / 4; i++)
			{
				mapped.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF);
			}

			for (int t = 0; t < osrsTextures.length; t++)
			{
				int[] argb = osrsTextures[t] == null ? null : tp.load(t);
				if (argb == null || argb.length < LAYER_SIZE * LAYER_SIZE)
				{
					// Texture not yet loaded by the engine; fill with grey so faces
					// referring to it don't render black holes.
					for (int p = 0; p < LAYER_SIZE * LAYER_SIZE; p++)
					{
						mapped.put((byte) 0x80).put((byte) 0x80).put((byte) 0x80).put((byte) 0xFF);
					}
				}
				if (argb != null && argb.length >= LAYER_SIZE * LAYER_SIZE)
				{
					// OSRS encodes texture transparency as `rgb == 0`. Stock's
					// TextureManager.convertPixels writes 0,0,0,0 for those
					// pixels and 0xFF alpha otherwise; the fragment shader's
					// `if (textureColor.a < 1.0) discard;` then drops them.
					for (int p = 0; p < LAYER_SIZE * LAYER_SIZE; p++)
					{
						int rgb = argb[p];
						if (rgb == 0)
						{
							mapped.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
						}
						else
						{
							mapped.put((byte) (rgb         & 0xFF))   // B
								  .put((byte) ((rgb >>  8) & 0xFF))   // G
								  .put((byte) ((rgb >> 16) & 0xFF))   // R
								  .put((byte) 0xFF);                  // A
						}
					}
				}
			}
			log.info("Texture array initialized: {} textures, {} layers", osrsTextures.length, layerCount);

			// One-shot command buffer to copy + transition.
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

				transitionAllLayers(cmd, stack, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
					VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
					0, VK_ACCESS_TRANSFER_WRITE_BIT);

				VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(layerCount, stack);
				for (int layer = 0; layer < layerCount; layer++)
				{
					regions.get(layer)
						.bufferOffset((long) layer * bytesPerLayer)
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

				transitionAllLayers(cmd, stack, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
					VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
					VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

				vkEndCommandBuffer(cmd);

				VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
					.sType$Default()
					.pCommandBuffers(stack.pointers(cmd));
				vkQueueSubmit(device.graphicsQueue(), submit, VK_NULL_HANDLE);
				vkQueueWaitIdle(device.graphicsQueue());

				vkFreeCommandBuffers(device.handle(), commandPool, cmd);
			}
		}
		finally
		{
			staging.close();
		}
	}

	private void transitionAllLayers(VkCommandBuffer cmd, MemoryStack stack,
									 int oldLayout, int newLayout,
									 int srcStage, int dstStage,
									 int srcAccess, int dstAccess)
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
				.baseMipLevel(0).levelCount(1)
				.baseArrayLayer(0).layerCount(layerCount));
		vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
	}

	@Override
	public void close()
	{
		// Make sure no in-flight command buffer references our image/sampler/UBO.
		// The Disposables LIFO closes VulkanRenderer + InterfaceRenderer +
		// SceneRenderer first (each does its own vkDeviceWaitIdle), but be
		// defensive — if anything reorders, this prevents native crashes from
		// destroy-while-in-use.
		vkDeviceWaitIdle(device.handle());
		if (animationUbo != null) animationUbo.close();
		vkDestroySampler(device.handle(), sampler, null);
		vkDestroyImageView(device.handle(), view, null);
		vkDestroyImage(device.handle(), image, null);
		vkFreeMemory(device.handle(), memory, null);
	}
}
