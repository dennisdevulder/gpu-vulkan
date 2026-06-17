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

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceSynchronization2Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVideoMaintenance1FeaturesKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.util.HashSet;
import java.nio.IntBuffer;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRVideoEncodeH264.VK_KHR_VIDEO_ENCODE_H264_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.VK_KHR_VIDEO_ENCODE_QUEUE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.VK_QUEUE_VIDEO_ENCODE_BIT_KHR;
import static org.lwjgl.vulkan.KHRVideoMaintenance1.VK_KHR_VIDEO_MAINTENANCE_1_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoQueue.VK_KHR_VIDEO_QUEUE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Picks a physical device + queue family (graphics and present must share one
 * family) and creates the {@link VkDevice} with {@code VK_KHR_swapchain}.
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
	private final float maxSamplerAnisotropy;
	private final int maxSampleCount;
	/** What the device actually reports — llvmpipe and some embedded SoCs lack
	 *  these; downstream code must skip/clamp when unsupported. */
	private final boolean supportsFillModeNonSolid;
	private final boolean supportsSamplerAnisotropy;
	private final long nonCoherentAtomSize;
	/** VK_EXT_metal_objects enabled (macOS only); selects the custom
	 *  MTLCommandQueue present path over KHR_swapchain. */
	private final boolean supportsMetalObjects;
	private final boolean supportsVideoEncode;
	private final String h264EncodeExtensionName;
	/** {@code id<MTLCommandQueue>} for the custom present path; zero when
	 *  {@link #supportsMetalObjects} is false. */
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

			// Query features BEFORE requesting any — asking for an unsupported
			// one fails vkCreateDevice with FEATURE_NOT_PRESENT.
			VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
			vkGetPhysicalDeviceFeatures(physicalDevice, supported);
			this.supportsFillModeNonSolid  = supported.fillModeNonSolid();
			this.supportsSamplerAnisotropy = supported.samplerAnisotropy();
			logUnsupportedFeatures();

			this.maxSamplerAnisotropy = clampMaxSamplerAnisotropy(props);
			this.maxSampleCount = pickMaxSampleCount(props);

			// Spec: a device advertising VK_KHR_portability_subset MUST have it
			// enabled at vkCreateDevice (MoltenVK always advertises it).
			Set<String> deviceExtensions = enumerateDeviceExtensions(stack, physicalDevice);
			boolean hasPortabilitySubset = deviceExtensions.contains("VK_KHR_portability_subset");
			// VK_EXT_metal_objects exposes our VkQueue's MTLCommandQueue so we
			// can present CAMetalDrawables ourselves (macOS only).
			boolean hasMetalObjects = deviceExtensions.contains("VK_EXT_metal_objects")
				&& !Boolean.getBoolean("vkgpu.disableCustomPresent");
			this.supportsMetalObjects = hasMetalObjects;
			int encodeFamily = findQueueFamily(physicalDevice, VK_QUEUE_VIDEO_ENCODE_BIT_KHR, stack);
			String h264Ext = selectH264EncodeExtension(deviceExtensions);
			boolean hasVideoEncode = encodeFamily >= 0
				&& deviceExtensions.contains(VK_KHR_VIDEO_QUEUE_EXTENSION_NAME)
				&& deviceExtensions.contains(VK_KHR_VIDEO_ENCODE_QUEUE_EXTENSION_NAME)
				&& deviceExtensions.contains(VK_KHR_VIDEO_MAINTENANCE_1_EXTENSION_NAME)
				&& h264Ext != null
				&& !hasPortabilitySubset;
			this.supportsVideoEncode = hasVideoEncode;
			this.videoEncodeQueueFamily = hasVideoEncode ? encodeFamily : -1;
			this.h264EncodeExtensionName = h264Ext;
			if (hasVideoEncode)
			{
				log.info("Enabling Vulkan Video H.264 encode on queue family {}", encodeFamily);
			}

			this.handle = createLogicalDevice(stack, hasPortabilitySubset, hasMetalObjects, hasVideoEncode);

			PointerBuffer pQueue = stack.mallocPointer(1);
			vkGetDeviceQueue(handle, graphicsQueueFamily, 0, pQueue);
			this.graphicsQueue = new VkQueue(pQueue.get(0), handle);
			if (hasVideoEncode)
			{
				vkGetDeviceQueue(handle, videoEncodeQueueFamily, 0, pQueue);
				this.videoEncodeQueue = new VkQueue(pQueue.get(0), handle);
			}
			else
			{
				this.videoEncodeQueue = null;
			}

			this.metalCommandQueue = initMetalCommandQueue(stack);
		}
	}

	private void logUnsupportedFeatures()
	{
		if (!supportsFillModeNonSolid)
		{
			log.info("Device does not support fillModeNonSolid — wireframe toggles will be no-ops");
		}
		if (!supportsSamplerAnisotropy)
		{
			log.info("Device does not support samplerAnisotropy — anisotropic filter clamped to 1×");
		}
	}

	private float clampMaxSamplerAnisotropy(VkPhysicalDeviceProperties props)
	{
		// Some drivers report a 0 limit; clamp to 1 so disabled samplers
		// don't trip validation.
		return supportsSamplerAnisotropy
			? Math.max(1.0f, props.limits().maxSamplerAnisotropy())
			: 1.0f;
	}

	private static int pickMaxSampleCount(VkPhysicalDeviceProperties props)
	{
		// MSAA needs the count supported for color AND depth at once;
		// pick the highest bit they share.
		int counts = props.limits().framebufferColorSampleCounts()
			& props.limits().framebufferDepthSampleCounts();
		return highestSampleBit(counts);
	}

	private VkDevice createLogicalDevice(MemoryStack stack, boolean hasPortabilitySubset, boolean hasMetalObjects,
		boolean hasVideoEncode)
	{
		boolean sharedVideoQueue = hasVideoEncode && videoEncodeQueueFamily == graphicsQueueFamily;
		int queueInfoCount = hasVideoEncode && !sharedVideoQueue ? 2 : 1;
		VkDeviceQueueCreateInfo.Buffer qInfo = VkDeviceQueueCreateInfo.calloc(queueInfoCount, stack);
		qInfo.get(0).sType$Default()
			.queueFamilyIndex(graphicsQueueFamily)
			.pQueuePriorities(stack.floats(1.0f));
		if (queueInfoCount == 2)
		{
			qInfo.get(1).sType$Default()
				.queueFamilyIndex(videoEncodeQueueFamily)
				.pQueuePriorities(stack.floats(1.0f));
		}

		int extCount = 1
			+ (hasPortabilitySubset ? 1 : 0)
			+ (hasMetalObjects ? 1 : 0)
			+ (hasVideoEncode ? 4 : 0);
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
		if (hasVideoEncode)
		{
			devExtensions.put(stack.UTF8(VK_KHR_VIDEO_QUEUE_EXTENSION_NAME));
			devExtensions.put(stack.UTF8(VK_KHR_VIDEO_ENCODE_QUEUE_EXTENSION_NAME));
			devExtensions.put(stack.UTF8(VK_KHR_VIDEO_MAINTENANCE_1_EXTENSION_NAME));
			devExtensions.put(stack.UTF8(h264EncodeExtensionName));
		}
		devExtensions.flip();

		// Enable only what's both wanted AND supported.
		VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack)
			.fillModeNonSolid(supportsFillModeNonSolid)
			.samplerAnisotropy(supportsSamplerAnisotropy);

		long pNext = 0L;
		if (hasVideoEncode)
		{
			VkPhysicalDeviceSynchronization2Features sync2 =
				VkPhysicalDeviceSynchronization2Features.calloc(stack)
					.sType$Default()
					.synchronization2(true);
			VkPhysicalDeviceVideoMaintenance1FeaturesKHR videoMaint1 =
				VkPhysicalDeviceVideoMaintenance1FeaturesKHR.calloc(stack)
					.sType$Default()
					.pNext(sync2.address())
					.videoMaintenance1(true);
			pNext = videoMaint1.address();
		}

		VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack)
			.sType$Default()
			.pNext(pNext)
			.pQueueCreateInfos(qInfo)
			.ppEnabledExtensionNames(devExtensions)
			.pEnabledFeatures(features);

		PointerBuffer pDev = stack.mallocPointer(1);
		Vk.check("vkCreateDevice", vkCreateDevice(physicalDevice, info, null, pDev));
		return new VkDevice(pDev.get(0), physicalDevice, info);
	}

	private long initMetalCommandQueue(MemoryStack stack)
	{
		if (!supportsMetalObjects)
		{
			return 0L;
		}
		long queue = extractMetalCommandQueue(stack);
		log.info("Extracted MTLCommandQueue 0x{} for custom-present path",
			Long.toHexString(queue));
		return queue;
	}

	/** Returned pointer is owned by MoltenVK — never release it; its lifetime
	 *  is the device's. */
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

	boolean supportsMetalObjects()
	{
		return supportsMetalObjects;
	}

	boolean supportsVideoEncode()
	{
		return supportsVideoEncode;
	}

	String h264EncodeExtensionName()
	{
		return h264EncodeExtensionName;
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

	/** Highest {@code VK_SAMPLE_COUNT_*_BIT} supported for color and depth
	 *  attachments simultaneously. */
	int maxSampleCount()
	{
		return maxSampleCount;
	}

	/** {@code desired} is the raw sample count (1, 2, 4, 8, …), not a bitmask. */
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

	private static int findQueueFamily(VkPhysicalDevice pd, int requiredFlags, MemoryStack stack)
	{
		IntBuffer count = stack.mallocInt(1);
		vkGetPhysicalDeviceQueueFamilyProperties(pd, count, null);
		VkQueueFamilyProperties.Buffer fams = VkQueueFamilyProperties.calloc(count.get(0), stack);
		vkGetPhysicalDeviceQueueFamilyProperties(pd, count, fams);
		for (int i = 0; i < fams.capacity(); i++)
		{
			if ((fams.get(i).queueFlags() & requiredFlags) == requiredFlags)
			{
				return i;
			}
		}
		return -1;
	}

	private static String selectH264EncodeExtension(Set<String> extensions)
	{
		if (extensions.contains(VK_KHR_VIDEO_ENCODE_H264_EXTENSION_NAME))
		{
			return VK_KHR_VIDEO_ENCODE_H264_EXTENSION_NAME;
		}
		if (extensions.contains("VK_EXT_video_encode_h264"))
		{
			return "VK_EXT_video_encode_h264";
		}
		return null;
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
