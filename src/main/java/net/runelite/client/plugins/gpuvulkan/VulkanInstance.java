package net.runelite.client.plugins.gpuvulkan;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;

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
			String[] surfaceExts = platform.requiredInstanceExtensions();
			VkApplicationInfo app = VkApplicationInfo.calloc(stack)
				.sType$Default()
				.pApplicationName(stack.UTF8("RuneLite GPU (Vulkan)"))
				.applicationVersion(VK_MAKE_VERSION(0, 1, 0))
				.pEngineName(stack.UTF8("gpuvulkan"))
				.engineVersion(VK_MAKE_VERSION(0, 1, 0))
				.apiVersion(VK_API_VERSION_1_3);

			boolean validationOn = enableValidation && hasValidationLayer(stack);
			if (enableValidation && !validationOn)
			{
				log.warn("Validation requested but {} not installed — running without", VALIDATION_LAYER);
			}

			// Surface extensions come from the platform layer (VK_KHR_surface
			// + the right platform-specific surface extension for X11 / Win32
			// / Metal). VK_EXT_debug_utils is added when validation is on so
			// we can register a messenger callback.
			int extCount = surfaceExts.length + (validationOn ? 1 : 0);
			PointerBuffer extNames = stack.mallocPointer(extCount);
			for (String name : surfaceExts)
			{
				extNames.put(stack.UTF8(name));
			}
			if (validationOn)
			{
				extNames.put(stack.UTF8(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
			}
			extNames.flip();

			VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack)
				.sType$Default()
				.pApplicationInfo(app)
				.ppEnabledExtensionNames(extNames);

			VkDebugUtilsMessengerCallbackEXT cb = null;
			if (validationOn)
			{
				info.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)));
				log.info("Vulkan validation layers enabled (with VK_EXT_debug_utils messenger — messages will appear in this log)");

				cb = VkDebugUtilsMessengerCallbackEXT.create((severity, type, pCallbackData, pUserData) ->
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

				// Deliberately NOT chaining a pNext create-info onto
				// vkCreateInstance. That trick catches messages from inside
				// the create/destroy-instance calls themselves, but it creates
				// an implicit messenger that some layer builds have known
				// problems unwinding cleanly across multiple instance lifecycles.
				// The persistent messenger created post-instance below covers
				// everything else.
			}

			PointerBuffer pInst = stack.mallocPointer(1);
			int r = vkCreateInstance(info, null, pInst);
			if (r != VK_SUCCESS)
			{
				if (cb != null) cb.free();
				throw new RuntimeException("vkCreateInstance failed: " + r);
			}
			this.handle = new VkInstance(pInst.get(0), info);

			if (validationOn)
			{
				this.debugCallback = cb;
				VkDebugUtilsMessengerCreateInfoEXT persistentInfo = makeCreateInfo(stack, cb);
				LongBuffer pMessenger = stack.mallocLong(1);
				int mr = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(handle, persistentInfo, null, pMessenger);
				if (mr != VK_SUCCESS)
				{
					log.warn("vkCreateDebugUtilsMessengerEXT failed: {} — continuing without persistent messenger", mr);
					this.debugMessenger = VK_NULL_HANDLE;
				}
				else
				{
					this.debugMessenger = pMessenger.get(0);
				}
			}
			else
			{
				this.debugCallback = null;
				this.debugMessenger = VK_NULL_HANDLE;
			}
		}
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
