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
 * Wraps {@link VkInstance} creation. Optionally enables
 * {@code VK_LAYER_KHRONOS_validation} when validation is on, but only if the
 * layer is actually installed — silently falls back to no validation if the
 * SDK isn't on the host.
 *
 * <p>When validation is enabled, also wires up {@code VK_EXT_debug_utils} +
 * a {@link VkDebugUtilsMessengerEXT} so the layer has a real callback to
 * route messages through. Without a registered messenger the layer falls
 * back to its stderr-only code paths, which on plugin disable can hit a
 * use-after-free in the layer's own object-lifetime tracker (SIGSEGV in
 * {@code libVkLayer_khronos_validation.so}). Giving the layer a proper
 * callback keeps it on the well-tested message path AND surfaces any
 * actual VUID violations we have via slf4j so we can fix them.
 */
@Slf4j
final class VulkanInstance implements AutoCloseable
{
	private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

	private final VkInstance handle;
	/** Non-null only when validation+debug messenger are active. Held so we
	 *  can {@link VkDebugUtilsMessengerCallbackEXT#free()} the JNI-side
	 *  function-pointer wrapper at close time — otherwise the native callback
	 *  shim leaks across plugin enable/disable cycles, which is one of the
	 *  things the validation layer's tracker eventually trips over. */
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

				// Deliberately NOT chaining a pNext create-info onto
				// vkCreateInstance. That trick catches messages from inside
				// the create/destroy-instance calls themselves, but it creates
				// an implicit messenger that some layer builds have known
				// problems unwinding cleanly across multiple instance lifecycles.
				// The persistent messenger created post-instance below covers
				// everything else.
			}

			this.handle = createInstance(stack, info, cb);
			this.debugCallback = cb;
			this.debugMessenger = validationOn ? createDebugMessenger(stack, handle, cb) : VK_NULL_HANDLE;
		}
	}

	private static String[] filterSurfaceExtensions(MemoryStack stack, String[] requestedExts)
	{
		// Filter against what the implementation actually exposes.
		// VK_KHR_portability_enumeration is a loader-level extension: with
		// the official Vulkan SDK loader on macOS it IS advertised, but
		// when LWJGL bundles MoltenVK directly there's no loader in the
		// picture and the extension is missing. Requesting an extension
		// the impl doesn't advertise → vkCreateInstance returns
		// VK_ERROR_EXTENSION_NOT_PRESENT (-7). The matching create-flag
		// is also skipped in that case (set further down).
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

		// Opt into synchronization validation. The validation layer's default
		// checks catch API misuse (wrong types, bad handles, etc.) but NOT
		// missing barriers or CPU↔GPU host-memory races on persistently-mapped
		// buffers — exactly the bug class we're hunting. The extra cost is
		// fine for a development run; we leave it off in production by virtue
		// of validation being opt-in via -Dvkgpu.validation=true.
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
		// Surface extensions come from the platform layer (VK_KHR_surface
		// + the right platform-specific surface extension for X11 / Win32
		// / Metal). VK_EXT_debug_utils is added when validation is on so
		// we can register a messenger callback. VK_EXT_validation_features
		// is added so we can opt into synchronization validation, which is
		// the layer's race-condition / missing-barrier detector — off by
		// default because it's expensive at runtime.
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
		// VK_KHR_portability_enumeration is requested on platforms that run
		// on top of a portability-subset implementation (MoltenVK on macOS).
		// When the extension is present in the list, also set the matching
		// instance-create flag — without it MoltenVK refuses to enumerate
		// and vkCreateInstance returns VK_ERROR_INCOMPATIBLE_DRIVER.
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
			throw new RuntimeException("vkCreateInstance failed: " + r);
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
		// Order matters: messenger before instance (spec), callback shim
		// free()'d after the messenger is gone (otherwise the JNI dispatch
		// could still be invoked into a freed shim during teardown).
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
