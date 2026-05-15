package net.runelite.client.plugins.gpuvulkan;

import net.runelite.client.plugins.gpuvulkan.gfx.ShaderModule;

import static org.lwjgl.vulkan.VK13.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK13.vkDestroyShaderModule;

final class GfxShaderModule implements ShaderModule
{
	private final VulkanDevice device;
	private long handle;

	GfxShaderModule(VulkanDevice device, long handle)
	{
		this.device = device;
		this.handle = handle;
	}

	long handle() { return handle; }

	@Override
	public void close()
	{
		if (handle != VK_NULL_HANDLE)
		{
			vkDestroyShaderModule(device.handle(), handle, null);
			handle = VK_NULL_HANDLE;
		}
	}
}
