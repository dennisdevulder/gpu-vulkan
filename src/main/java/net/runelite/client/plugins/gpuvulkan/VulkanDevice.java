package net.runelite.client.plugins.gpuvulkan;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;

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
	private final int graphicsQueueFamily;
	private final String deviceName;
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

			VkDeviceQueueCreateInfo.Buffer qInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
			qInfo.get(0).sType$Default()
				.queueFamilyIndex(graphicsQueueFamily)
				.pQueuePriorities(stack.floats(1.0f));

			PointerBuffer devExtensions = stack.pointers(
				stack.UTF8(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME)
			);

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

			PointerBuffer pDev = stack.mallocPointer(1);
			int r = vkCreateDevice(physicalDevice, info, null, pDev);
			if (r != VK_SUCCESS)
			{
				throw new RuntimeException("vkCreateDevice failed: " + r);
			}
			this.handle = new VkDevice(pDev.get(0), physicalDevice, info);

			PointerBuffer pQueue = stack.mallocPointer(1);
			vkGetDeviceQueue(handle, graphicsQueueFamily, 0, pQueue);
			this.graphicsQueue = new VkQueue(pQueue.get(0), handle);
		}
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

	int graphicsQueueFamily()
	{
		return graphicsQueueFamily;
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
		vkEnumeratePhysicalDevices(instance.handle(), count, null);
		if (count.get(0) == 0)
		{
			throw new RuntimeException("No Vulkan-capable GPU found");
		}
		PointerBuffer devs = stack.mallocPointer(count.get(0));
		vkEnumeratePhysicalDevices(instance.handle(), count, devs);

		for (int i = 0; i < devs.capacity(); i++)
		{
			VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance.handle());
			int qf = findGraphicsAndPresentQueueFamily(pd, surface, stack);
			if (qf >= 0)
			{
				return new Picked(pd, qf);
			}
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
			vkGetPhysicalDeviceSurfaceSupportKHR(pd, i, surface.handle(), pSupport);
			boolean present = pSupport.get(0) == VK_TRUE;
			if (graphics && present)
			{
				return i;
			}
		}
		return -1;
	}

}
