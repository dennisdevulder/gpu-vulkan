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
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.EXTValidationFeatures;
import org.lwjgl.vulkan.KHRPortabilityEnumeration;
import org.lwjgl.vulkan.VkValidationFeaturesEXT;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;

import java.util.HashSet;
import java.util.Set;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Wraps {@link VkInstance} creation. Validation requires a registered debug
 * messenger — without one the layer's stderr path can SIGSEGV on disable.
 */
@Slf4j
final class VulkanInstance implements AutoCloseable
{
	private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

	private final VkInstance handle;
	/** Held so the JNI callback shim can be free()'d at close — it leaks
	 *  across enable/disable cycles otherwise. */
	private final VkDebugUtilsMessengerCallbackEXT debugCallback;
	/** {@code VK_NULL_HANDLE} when validation is off OR the messenger create
	 *  call failed. Must be destroyed BEFORE {@link #handle} per the spec. */
	private final long debugMessenger;

	VulkanInstance(boolean enableValidation, PlatformSurface platform)
	{
		try (MemoryStack stack = stackPush())
		{
			String[] surfaceExts = filterSurfaceExtensions(stack, platform.requiredInstanceExtensions());

			boolean validationOn = enableValidation && hasValidationLayer(stack);
			if (enableValidation && !validationOn)
			{
				log.warn("Validation requested but {} not installed — running without", VALIDATION_LAYER);
			}

			VkInstanceCreateInfo info = buildInstanceCreateInfo(stack, surfaceExts, validationOn);

			VkDebugUtilsMessengerCallbackEXT cb = null;
			if (validationOn)
			{
				info.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)));
				log.info("Vulkan validation layers enabled (with VK_EXT_debug_utils messenger — messages will appear in this log)");

				cb = createDebugCallback();

				// Deliberately NOT chaining a pNext messenger onto vkCreateInstance:
				// some layer builds can't unwind it across multiple instance lifecycles.
			}

			this.handle = createInstance(stack, info, cb);
			this.debugCallback = cb;
			this.debugMessenger = validationOn ? createDebugMessenger(stack, handle, cb) : VK_NULL_HANDLE;
		}
	}

	private static String[] filterSurfaceExtensions(MemoryStack stack, String[] requestedExts)
	{
		// Filter against what the impl exposes: VK_KHR_portability_enumeration is
		// missing when LWJGL bundles MoltenVK directly (no loader in the picture).
		Set<String> available = enumerateInstanceExtensions(stack);
		java.util.ArrayList<String> surfaceExtList = new java.util.ArrayList<>(requestedExts.length);
		for (String e : requestedExts)
		{
			if (available.contains(e))
			{
				surfaceExtList.add(e);
			}
			else
			{
				log.info("Requested instance extension {} not exposed by the Vulkan implementation — skipping", e);
			}
		}
		return surfaceExtList.toArray(new String[0]);
	}

	private static VkInstanceCreateInfo buildInstanceCreateInfo(MemoryStack stack, String[] surfaceExts,
		boolean validationOn)
	{
		VkApplicationInfo app = VkApplicationInfo.calloc(stack)
			.sType$Default()
			.pApplicationName(stack.UTF8("RuneLite GPU (Vulkan)"))
			.applicationVersion(VK_MAKE_VERSION(0, 1, 0))
			.pEngineName(stack.UTF8("gpuvulkan"))
			.engineVersion(VK_MAKE_VERSION(0, 1, 0))
			.apiVersion(VK_API_VERSION_1_3);

		VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack)
			.sType$Default()
			.flags(portabilityInstanceFlags(surfaceExts))
			.pApplicationInfo(app)
			.ppEnabledExtensionNames(buildExtensionNames(stack, surfaceExts, validationOn));

		// Sync validation: default checks don't catch missing barriers or
		// CPU/GPU races on persistently-mapped buffers.
		if (validationOn)
		{
			IntBuffer enables = stack.ints(
				EXTValidationFeatures.VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT);
			VkValidationFeaturesEXT vfeatures = VkValidationFeaturesEXT.calloc(stack)
				.sType$Default()
				.pEnabledValidationFeatures(enables);
			info.pNext(vfeatures.address());
		}
		return info;
	}

	private static PointerBuffer buildExtensionNames(MemoryStack stack, String[] surfaceExts, boolean validationOn)
	{
		int validationExtCount = validationOn ? 2 : 0;
		int extCount = surfaceExts.length + validationExtCount;
		PointerBuffer extNames = stack.mallocPointer(extCount);
		for (String name : surfaceExts)
		{
			extNames.put(stack.UTF8(name));
		}
		if (validationOn)
		{
			extNames.put(stack.UTF8(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
			extNames.put(stack.UTF8(EXTValidationFeatures.VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME));
		}
		extNames.flip();
		return extNames;
	}

	private static int portabilityInstanceFlags(String[] surfaceExts)
	{
		// The extension needs its matching instance-create flag, or MoltenVK
		// fails vkCreateInstance with VK_ERROR_INCOMPATIBLE_DRIVER.
		int instanceFlags = 0;
		for (String name : surfaceExts)
		{
			if (KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME.equals(name))
			{
				instanceFlags |= KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
				break;
			}
		}
		return instanceFlags;
	}

	private static VkDebugUtilsMessengerCallbackEXT createDebugCallback()
	{
		return VkDebugUtilsMessengerCallbackEXT.create((severity, type, pCallbackData, pUserData) ->
		{
			VkDebugUtilsMessengerCallbackDataEXT data =
				VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
			String text = data.pMessageString();
			if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0)
			{
				log.error("[VK] {}", text);
			}
			else if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0)
			{
				log.warn("[VK] {}", text);
			}
			else
			{
				log.info("[VK] {}", text);
			}
			return VK_FALSE;
		});
	}

	private static VkInstance createInstance(MemoryStack stack, VkInstanceCreateInfo info,
		VkDebugUtilsMessengerCallbackEXT cb)
	{
		PointerBuffer pInst = stack.mallocPointer(1);
		int r = vkCreateInstance(info, null, pInst);
		if (r != VK_SUCCESS)
		{
			if (cb != null) cb.free();
			throw Vk.fail("vkCreateInstance", r);
		}
		return new VkInstance(pInst.get(0), info);
	}

	private static long createDebugMessenger(MemoryStack stack, VkInstance handle,
		VkDebugUtilsMessengerCallbackEXT cb)
	{
		VkDebugUtilsMessengerCreateInfoEXT persistentInfo = makeCreateInfo(stack, cb);
		LongBuffer pMessenger = stack.mallocLong(1);
		int mr = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(handle, persistentInfo, null, pMessenger);
		if (mr != VK_SUCCESS)
		{
			log.warn("vkCreateDebugUtilsMessengerEXT failed: {} — continuing without persistent messenger", mr);
			return VK_NULL_HANDLE;
		}
		return pMessenger.get(0);
	}

	VkInstance handle()
	{
		return handle;
	}

	@Override
	public void close()
	{
		// Order: messenger before instance (spec); callback shim free()'d
		// only after the messenger is gone.
		if (debugMessenger != VK_NULL_HANDLE)
		{
			EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(handle, debugMessenger, null);
		}
		vkDestroyInstance(handle, null);
		if (debugCallback != null)
		{
			debugCallback.free();
		}
	}

	private static VkDebugUtilsMessengerCreateInfoEXT makeCreateInfo(MemoryStack stack,
		VkDebugUtilsMessengerCallbackEXT cb)
	{
		return VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
			.sType$Default()
			.messageSeverity(
				EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
					| EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
			.messageType(
				EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
					| EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
					| EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
			.pfnUserCallback(cb);
	}

	private static Set<String> enumerateInstanceExtensions(MemoryStack stack)
	{
		Set<String> out = new HashSet<>();
		IntBuffer count = stack.mallocInt(1);
		vkEnumerateInstanceExtensionProperties((CharSequence) null, count, null);
		if (count.get(0) == 0) return out;
		VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(count.get(0), stack);
		vkEnumerateInstanceExtensionProperties((CharSequence) null, count, props);
		for (int i = 0; i < props.capacity(); i++)
		{
			out.add(props.get(i).extensionNameString());
		}
		return out;
	}

	private static boolean hasValidationLayer(MemoryStack stack)
	{
		IntBuffer count = stack.mallocInt(1);
		vkEnumerateInstanceLayerProperties(count, null);
		if (count.get(0) == 0)
		{
			return false;
		}
		VkLayerProperties.Buffer props = VkLayerProperties.calloc(count.get(0), stack);
		vkEnumerateInstanceLayerProperties(count, props);
		for (int i = 0; i < props.capacity(); i++)
		{
			// layerNameString() handles NUL-termination on the fixed-size char[]
			// buffer; raw layerName() includes the padding bytes and breaks .equals().
			if (VALIDATION_LAYER.equals(props.get(i).layerNameString()))
			{
				return true;
			}
		}
		return false;
	}
}
