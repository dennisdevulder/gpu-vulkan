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

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRVideoEncodeQueue;
import org.lwjgl.vulkan.KHRVideoQueue;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceSynchronization2Features;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.util.HashSet;
import java.nio.IntBuffer;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Picks a physical device + queue family that supports both graphics and
 * present on the given {@link VulkanSurface}, and creates the matching
 * {@link VkDevice} with {@code VK_KHR_swapchain} enabled. We assume one
 * combined queue family — true on every consumer GPU; if a future device
 * splits them we'll widen to two queue indices.
 */
@Slf4j
final class VulkanDevice implements AutoCloseable
{
	private final VkPhysicalDevice physicalDevice;
	private final VkDevice handle;
	private final VkQueue graphicsQueue;
	private final VkQueue videoEncodeQueue;
	private final int graphicsQueueFamily;
	private final int videoEncodeQueueFamily;
	private final String deviceName;
	private final boolean supportsVideoQueue;
	private final boolean supportsVideoEncodeQueue;
	private final boolean supportsVideoEncodeH264;
	private final boolean supportsVideoEncodeH265;
	private final boolean supportsVideoEncodeAv1;
	private final float maxSamplerAnisotropy;
	private final int maxSampleCount;
	/** Reflects the {@link VkPhysicalDeviceFeatures} bits the device actually
	 *  reports — not what we'd like. Validation rejects {@code vkCreateDevice}
	 *  if we request a feature the implementation says no to; downstream code
	 *  (wireframe pipeline, anisotropic sampler) reads these and skips/clamps
	 *  cleanly when unsupported. Both are universally available on desktop
	 *  drivers but software/llvmpipe + a few embedded SoCs can be missing
	 *  either one. */
	private final boolean supportsFillModeNonSolid;
	private final boolean supportsSamplerAnisotropy;
	private final long nonCoherentAtomSize;
	/** {@code true} when VK_EXT_metal_objects is enabled (macOS only).
	 *  Populated at the device-extension-enable step. Other code branches
	 *  on this to pick between the KHR_swapchain present path (Linux) and
	 *  the custom MTLCommandQueue present path (macOS). */
	private final boolean supportsMetalObjects;
	private static final String VK_KHR_VIDEO_ENCODE_H264_EXTENSION_NAME = "VK_KHR_video_encode_h264";
	private static final String VK_KHR_VIDEO_ENCODE_H265_EXTENSION_NAME = "VK_KHR_video_encode_h265";
	private static final String VK_KHR_VIDEO_ENCODE_AV1_EXTENSION_NAME = "VK_KHR_video_encode_av1";
	/** Why no encode queue was created, for {@link VulkanEncodeContext#unavailableReason()}.
	 *  Null when {@link #videoEncodeQueue} is non-null. */
	private final String videoEncodeUnavailableDetail;
	/** {@code id<MTLCommandQueue>} pointer the custom present path uses to
	 *  schedule {@code [drawable present]} after our Vulkan render. Extracted
	 *  via vkExportMetalObjectsEXT in {@link #extractMetalCommandQueue}.
	 *  Zero when {@link #supportsMetalObjects} is false. */
	private long metalCommandQueue;

	VulkanDevice(VulkanInstance instance, VulkanSurface surface)
	{
		try (MemoryStack stack = stackPush())
		{
			Picked picked = pickDeviceAndQueueFamily(instance, surface, stack);
			this.physicalDevice = picked.device;
			this.graphicsQueueFamily = picked.queueFamily;

			VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
			vkGetPhysicalDeviceProperties(physicalDevice, props);
			this.deviceName = props.deviceNameString();
			this.nonCoherentAtomSize = props.limits().nonCoherentAtomSize();

			// Query what the device actually offers BEFORE asking for any
			// feature. Requesting an unsupported feature is a spec violation
			// and most drivers fail vkCreateDevice with FEATURE_NOT_PRESENT.
			VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
			vkGetPhysicalDeviceFeatures(physicalDevice, supported);
			this.supportsFillModeNonSolid  = supported.fillModeNonSolid();
			this.supportsSamplerAnisotropy = supported.samplerAnisotropy();
			if (!supportsFillModeNonSolid)
			{
				log.info("Device does not support fillModeNonSolid — wireframe toggles will be no-ops");
			}
			if (!supportsSamplerAnisotropy)
			{
				log.info("Device does not support samplerAnisotropy — anisotropic filter clamped to 1×");
			}

			// Some drivers report 0 if anisotropy isn't supported even though
			// the feature bit says yes — clamp to 1 so disabled samplers don't
			// trip validation. Otherwise we go up to whatever the device allows
			// (usually 16 on modern hardware). If the feature itself isn't
			// supported, force 1 regardless of the limit.
			this.maxSamplerAnisotropy = supportsSamplerAnisotropy
				? Math.max(1.0f, props.limits().maxSamplerAnisotropy())
				: 1.0f;

			// Sample counts the device supports for both color and depth
			// attachments at once — this is what we can actually request for
			// an MSAA renderpass. Pick the highest bit they share.
			int counts = props.limits().framebufferColorSampleCounts()
				& props.limits().framebufferDepthSampleCounts();
			this.maxSampleCount = highestSampleBit(counts);

			// Spec: if a device advertises VK_KHR_portability_subset, the app
			// MUST enable it at vkCreateDevice or device creation fails with
			// VK_ERROR_EXTENSION_NOT_PRESENT. MoltenVK always reports it.
			// Stock desktop drivers (NVIDIA / AMD / Intel / Mesa) never do,
			// so this is a no-op for them.
			Set<String> deviceExtensions = enumerateDeviceExtensions(stack, physicalDevice);
			boolean hasPortabilitySubset = deviceExtensions.contains("VK_KHR_portability_subset");
			// VK_EXT_metal_objects lets us extract the MTLCommandQueue our
			// VkQueue maps to, so we can present CAMetalDrawables ourselves
			// (bypassing MoltenVK's vkQueuePresentKHR — see MacOSMetalHelper
			// + VulkanRenderer.drawFrame). macOS only; advertised by MoltenVK.
			boolean hasMetalObjects = deviceExtensions.contains("VK_EXT_metal_objects")
				&& !Boolean.getBoolean("vkgpu.disableCustomPresent");
			this.supportsMetalObjects = hasMetalObjects;
			this.supportsVideoQueue = deviceExtensions.contains(KHRVideoQueue.VK_KHR_VIDEO_QUEUE_EXTENSION_NAME);
			this.supportsVideoEncodeQueue = deviceExtensions.contains(KHRVideoEncodeQueue.VK_KHR_VIDEO_ENCODE_QUEUE_EXTENSION_NAME);
			this.supportsVideoEncodeH264 = deviceExtensions.contains(VK_KHR_VIDEO_ENCODE_H264_EXTENSION_NAME);
			this.supportsVideoEncodeH265 = deviceExtensions.contains(VK_KHR_VIDEO_ENCODE_H265_EXTENSION_NAME);
			this.supportsVideoEncodeAv1 = deviceExtensions.contains(VK_KHR_VIDEO_ENCODE_AV1_EXTENSION_NAME);
			this.videoEncodeQueueFamily = findVideoEncodeQueueFamily(physicalDevice, stack);

			// Video encode queue: created up front because queue families are
			// fixed at vkCreateDevice — a recorder plugin can't add one later.
			// The queue is unused by the renderer itself; consumers reach it
			// through VulkanEncodeContext.
			boolean hasEncodeCodec = supportsVideoEncodeH264 || supportsVideoEncodeH265 || supportsVideoEncodeAv1;
			boolean wantEncodeQueue = supportsVideoQueue && supportsVideoEncodeQueue
				&& hasEncodeCodec && videoEncodeQueueFamily >= 0;
			String encodeDetail = null;
			if (wantEncodeQueue && Boolean.getBoolean("vkgpu.disableVideoEncode"))
			{
				wantEncodeQueue = false;
				encodeDetail = "Video encode queue disabled via -Dvkgpu.disableVideoEncode.";
			}
			// VK_KHR_video_queue's device-level functionality requires the
			// synchronization2 feature, which is mandatory in Vulkan 1.3 —
			// gate on the device's API version so the feature request below
			// can't fail vkCreateDevice on an older driver.
			if (wantEncodeQueue && props.apiVersion() < VK_API_VERSION_1_3)
			{
				wantEncodeQueue = false;
				encodeDetail = "Device reports Vulkan " + VK_API_VERSION_MAJOR(props.apiVersion())
					+ "." + VK_API_VERSION_MINOR(props.apiVersion())
					+ "; encode queue creation requires 1.3 (synchronization2).";
			}
			boolean encodeSharesGraphicsFamily = videoEncodeQueueFamily == graphicsQueueFamily;
			int encodeQueueIndex = 0;
			if (wantEncodeQueue && encodeSharesGraphicsFamily)
			{
				// Sharing the render VkQueue with a recorder thread would need
				// external synchronization on every submit — take a second
				// queue from the family instead, or none at all.
				if (queueFamilyQueueCount(physicalDevice, graphicsQueueFamily, stack) >= 2)
				{
					encodeQueueIndex = 1;
				}
				else
				{
					wantEncodeQueue = false;
					encodeDetail = "Encode bit is on the graphics queue family but it exposes only one queue; "
						+ "sharing the render queue is unsafe.";
				}
			}

			VkDeviceQueueCreateInfo.Buffer qInfo;
			if (wantEncodeQueue && !encodeSharesGraphicsFamily)
			{
				qInfo = VkDeviceQueueCreateInfo.calloc(2, stack);
				qInfo.get(1).sType$Default()
					.queueFamilyIndex(videoEncodeQueueFamily)
					.pQueuePriorities(stack.floats(1.0f));
			}
			else if (wantEncodeQueue)
			{
				qInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
				// Encode at lower priority so background recording can't starve rendering.
				qInfo.get(0).pQueuePriorities(stack.floats(1.0f, 0.5f));
			}
			else
			{
				qInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
			}
			qInfo.get(0).sType$Default()
				.queueFamilyIndex(graphicsQueueFamily);
			if (!(wantEncodeQueue && encodeSharesGraphicsFamily))
			{
				qInfo.get(0).pQueuePriorities(stack.floats(1.0f));
			}

			int videoExtCount = wantEncodeQueue
				? 2 + (supportsVideoEncodeH264 ? 1 : 0)
					+ (supportsVideoEncodeH265 ? 1 : 0)
					+ (supportsVideoEncodeAv1 ? 1 : 0)
				: 0;
			int extCount = 1
				+ (hasPortabilitySubset ? 1 : 0)
				+ (hasMetalObjects ? 1 : 0)
				+ videoExtCount;
			PointerBuffer devExtensions = stack.mallocPointer(extCount);
			devExtensions.put(stack.UTF8(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME));
			if (hasPortabilitySubset)
			{
				devExtensions.put(stack.UTF8("VK_KHR_portability_subset"));
				log.info("Enabling VK_KHR_portability_subset (MoltenVK / portability impl)");
			}
			if (hasMetalObjects)
			{
				devExtensions.put(stack.UTF8("VK_EXT_metal_objects"));
				log.info("Enabling VK_EXT_metal_objects (custom-present path)");
			}
			if (wantEncodeQueue)
			{
				devExtensions.put(stack.UTF8(KHRVideoQueue.VK_KHR_VIDEO_QUEUE_EXTENSION_NAME));
				devExtensions.put(stack.UTF8(KHRVideoEncodeQueue.VK_KHR_VIDEO_ENCODE_QUEUE_EXTENSION_NAME));
				if (supportsVideoEncodeH264)
				{
					devExtensions.put(stack.UTF8(VK_KHR_VIDEO_ENCODE_H264_EXTENSION_NAME));
				}
				if (supportsVideoEncodeH265)
				{
					devExtensions.put(stack.UTF8(VK_KHR_VIDEO_ENCODE_H265_EXTENSION_NAME));
				}
				if (supportsVideoEncodeAv1)
				{
					devExtensions.put(stack.UTF8(VK_KHR_VIDEO_ENCODE_AV1_EXTENSION_NAME));
				}
			}
			devExtensions.flip();

			// Enable only what's both wanted AND supported. Downstream code
			// (ScenePipeline wireframe, TextureArray sampler) reads the
			// corresponding supports* flag and degrades gracefully.
			VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack)
				.fillModeNonSolid(supportsFillModeNonSolid)
				.samplerAnisotropy(supportsSamplerAnisotropy);

			VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack)
				.sType$Default()
				.pQueueCreateInfos(qInfo)
				.ppEnabledExtensionNames(devExtensions)
				.pEnabledFeatures(features);

			if (wantEncodeQueue)
			{
				VkPhysicalDeviceSynchronization2Features sync2 =
					VkPhysicalDeviceSynchronization2Features.calloc(stack)
						.sType$Default()
						.synchronization2(true);
				info.pNext(sync2.address());
			}

			PointerBuffer pDev = stack.mallocPointer(1);
			Vk.check("vkCreateDevice", vkCreateDevice(physicalDevice, info, null, pDev));
			this.handle = new VkDevice(pDev.get(0), physicalDevice, info);

			PointerBuffer pQueue = stack.mallocPointer(1);
			vkGetDeviceQueue(handle, graphicsQueueFamily, 0, pQueue);
			this.graphicsQueue = new VkQueue(pQueue.get(0), handle);
			if (wantEncodeQueue)
			{
				PointerBuffer pEncodeQueue = stack.mallocPointer(1);
				vkGetDeviceQueue(handle, videoEncodeQueueFamily, encodeQueueIndex, pEncodeQueue);
				this.videoEncodeQueue = new VkQueue(pEncodeQueue.get(0), handle);
				log.info("Created video encode queue (family {}, index {}; H.264={}, H.265={}, AV1={})",
					videoEncodeQueueFamily, encodeQueueIndex,
					supportsVideoEncodeH264, supportsVideoEncodeH265, supportsVideoEncodeAv1);
			}
			else
			{
				this.videoEncodeQueue = null;
			}
			this.videoEncodeUnavailableDetail = encodeDetail;

			if (supportsMetalObjects)
			{
				this.metalCommandQueue = extractMetalCommandQueue(stack);
				log.info("Extracted MTLCommandQueue 0x{} for custom-present path",
					Long.toHexString(metalCommandQueue));
			}
			else
			{
				this.metalCommandQueue = 0L;
			}
		}
	}

	/**
	 * Calls {@code vkExportMetalObjectsEXT} with a
	 * {@link org.lwjgl.vulkan.VkExportMetalCommandQueueInfoEXT} pNext chain to
	 * pull out the {@code id<MTLCommandQueue>} MoltenVK created for our
	 * {@link VkQueue}. The returned pointer is owned by MoltenVK; we don't
	 * release it — the queue's lifetime is the device's lifetime.
	 */
	private long extractMetalCommandQueue(MemoryStack stack)
	{
		org.lwjgl.vulkan.VkExportMetalCommandQueueInfoEXT qInfo =
			org.lwjgl.vulkan.VkExportMetalCommandQueueInfoEXT.calloc(stack)
				.sType$Default()
				.queue(graphicsQueue);

		org.lwjgl.vulkan.VkExportMetalObjectsInfoEXT info =
			org.lwjgl.vulkan.VkExportMetalObjectsInfoEXT.calloc(stack)
				.sType$Default()
				.pNext(qInfo.address());

		org.lwjgl.vulkan.EXTMetalObjects.vkExportMetalObjectsEXT(handle, info);
		return qInfo.mtlCommandQueue();
	}

	VkDevice handle()
	{
		return handle;
	}

	VkPhysicalDevice physicalDevice()
	{
		return physicalDevice;
	}

	VkQueue graphicsQueue()
	{
		return graphicsQueue;
	}

	VkQueue videoEncodeQueue()
	{
		return videoEncodeQueue;
	}

	int graphicsQueueFamily()
	{
		return graphicsQueueFamily;
	}

	int videoEncodeQueueFamily()
	{
		return videoEncodeQueueFamily;
	}

	boolean supportsVideoEncodeQueue()
	{
		return supportsVideoQueue
			&& supportsVideoEncodeQueue
			&& videoEncodeQueueFamily >= 0;
	}

	/** Why the encode queue wasn't created despite extension-level capability;
	 *  null when it was, or when the device lacks capability entirely. */
	String videoEncodeUnavailableDetail()
	{
		return videoEncodeUnavailableDetail;
	}

	boolean supportsVideoEncodeH264()
	{
		return supportsVideoEncodeQueue() && supportsVideoEncodeH264;
	}

	boolean supportsVideoEncodeH265()
	{
		return supportsVideoEncodeQueue() && supportsVideoEncodeH265;
	}

	boolean supportsVideoEncodeAv1()
	{
		return supportsVideoEncodeQueue() && supportsVideoEncodeAv1;
	}

	boolean supportsMetalObjects()
	{
		return supportsMetalObjects;
	}

	/** {@code id<MTLCommandQueue>} for the custom-present path. Zero on Linux
	 *  or if the device didn't advertise VK_EXT_metal_objects. */
	long metalCommandQueue()
	{
		return metalCommandQueue;
	}

	String deviceName()
	{
		return deviceName;
	}

	float maxSamplerAnisotropy()
	{
		return maxSamplerAnisotropy;
	}

	boolean supportsFillModeNonSolid()
	{
		return supportsFillModeNonSolid;
	}

	boolean supportsSamplerAnisotropy()
	{
		return supportsSamplerAnisotropy;
	}

	long nonCoherentAtomSize()
	{
		return nonCoherentAtomSize;
	}

	/** Highest {@code VK_SAMPLE_COUNT_*_BIT} the device supports for both
	 *  color and depth attachments simultaneously. Use {@link #pickSampleCount}
	 *  to clamp a desired value against this. */
	int maxSampleCount()
	{
		return maxSampleCount;
	}

	/** Returns the largest {@code VK_SAMPLE_COUNT_*_BIT} value &le; {@code desired}
	 *  and &le; {@link #maxSampleCount()}. {@code desired} is the raw sample count
	 *  (1, 2, 4, 8, …), not a bitmask. */
	int pickSampleCount(int desired)
	{
		int wanted = highestSampleBit(desired);
		return Math.min(wanted, maxSampleCount);
	}

	private static int highestSampleBit(int counts)
	{
		if ((counts & VK_SAMPLE_COUNT_64_BIT) != 0) return VK_SAMPLE_COUNT_64_BIT;
		if ((counts & VK_SAMPLE_COUNT_32_BIT) != 0) return VK_SAMPLE_COUNT_32_BIT;
		if ((counts & VK_SAMPLE_COUNT_16_BIT) != 0) return VK_SAMPLE_COUNT_16_BIT;
		if ((counts & VK_SAMPLE_COUNT_8_BIT)  != 0) return VK_SAMPLE_COUNT_8_BIT;
		if ((counts & VK_SAMPLE_COUNT_4_BIT)  != 0) return VK_SAMPLE_COUNT_4_BIT;
		if ((counts & VK_SAMPLE_COUNT_2_BIT)  != 0) return VK_SAMPLE_COUNT_2_BIT;
		return VK_SAMPLE_COUNT_1_BIT;
	}

	@Override
	public void close()
	{
		vkDestroyDevice(handle, null);
	}

	// ---- helpers ---------------------------------------------------------

	private static final class Picked
	{
		final VkPhysicalDevice device;
		final int queueFamily;

		Picked(VkPhysicalDevice device, int queueFamily)
		{
			this.device = device;
			this.queueFamily = queueFamily;
		}
	}

	private static Picked pickDeviceAndQueueFamily(VulkanInstance instance, VulkanSurface surface, MemoryStack stack)
	{
		IntBuffer count = stack.mallocInt(1);
		Vk.check("vkEnumeratePhysicalDevices (count)",
			vkEnumeratePhysicalDevices(instance.handle(), count, null));
		if (count.get(0) == 0)
		{
			throw new RuntimeException("No Vulkan-capable GPU found");
		}
		PointerBuffer devs = stack.mallocPointer(count.get(0));
		Vk.check("vkEnumeratePhysicalDevices",
			vkEnumeratePhysicalDevices(instance.handle(), count, devs));

		// Hybrid laptops often enumerate the integrated GPU first.
		Picked fallback = null;
		for (int i = 0; i < devs.capacity(); i++)
		{
			VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance.handle());
			int qf = findGraphicsAndPresentQueueFamily(pd, surface, stack);
			if (qf < 0)
			{
				continue;
			}
			VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
			vkGetPhysicalDeviceProperties(pd, props);
			if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)
			{
				return new Picked(pd, qf);
			}
			if (fallback == null)
			{
				fallback = new Picked(pd, qf);
			}
		}
		if (fallback != null)
		{
			return fallback;
		}
		throw new RuntimeException("No Vulkan device with combined graphics+present queue on this surface");
	}

	private static int findGraphicsAndPresentQueueFamily(VkPhysicalDevice pd, VulkanSurface surface, MemoryStack stack)
	{
		IntBuffer count = stack.mallocInt(1);
		vkGetPhysicalDeviceQueueFamilyProperties(pd, count, null);
		VkQueueFamilyProperties.Buffer fams = VkQueueFamilyProperties.calloc(count.get(0), stack);
		vkGetPhysicalDeviceQueueFamilyProperties(pd, count, fams);

		IntBuffer pSupport = stack.mallocInt(1);
		for (int i = 0; i < fams.capacity(); i++)
		{
			boolean graphics = (fams.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;
			Vk.check("vkGetPhysicalDeviceSurfaceSupportKHR",
				vkGetPhysicalDeviceSurfaceSupportKHR(pd, i, surface.handle(), pSupport));
			boolean present = pSupport.get(0) == VK_TRUE;
			if (graphics && present)
			{
				return i;
			}
		}
		return -1;
	}

	private static int queueFamilyQueueCount(VkPhysicalDevice pd, int family, MemoryStack stack)
	{
		IntBuffer count = stack.mallocInt(1);
		vkGetPhysicalDeviceQueueFamilyProperties(pd, count, null);
		VkQueueFamilyProperties.Buffer fams = VkQueueFamilyProperties.calloc(count.get(0), stack);
		vkGetPhysicalDeviceQueueFamilyProperties(pd, count, fams);
		return family >= 0 && family < fams.capacity() ? fams.get(family).queueCount() : 0;
	}

	private static int findVideoEncodeQueueFamily(VkPhysicalDevice pd, MemoryStack stack)
	{
		IntBuffer count = stack.mallocInt(1);
		vkGetPhysicalDeviceQueueFamilyProperties(pd, count, null);
		VkQueueFamilyProperties.Buffer fams = VkQueueFamilyProperties.calloc(count.get(0), stack);
		vkGetPhysicalDeviceQueueFamilyProperties(pd, count, fams);

		for (int i = 0; i < fams.capacity(); i++)
		{
			if ((fams.get(i).queueFlags() & KHRVideoEncodeQueue.VK_QUEUE_VIDEO_ENCODE_BIT_KHR) != 0)
			{
				return i;
			}
		}
		return -1;
	}

	private static Set<String> enumerateDeviceExtensions(MemoryStack stack, VkPhysicalDevice pd)
	{
		IntBuffer count = stack.mallocInt(1);
		Vk.check("vkEnumerateDeviceExtensionProperties (count)",
			vkEnumerateDeviceExtensionProperties(pd, (CharSequence) null, count, null));
		Set<String> extensions = new HashSet<>();
		if (count.get(0) == 0) return extensions;
		VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(count.get(0), stack);
		Vk.check("vkEnumerateDeviceExtensionProperties",
			vkEnumerateDeviceExtensionProperties(pd, (CharSequence) null, count, props));
		for (int i = 0; i < props.capacity(); i++)
		{
			extensions.add(props.get(i).extensionNameString());
		}
		return extensions;
	}

}
