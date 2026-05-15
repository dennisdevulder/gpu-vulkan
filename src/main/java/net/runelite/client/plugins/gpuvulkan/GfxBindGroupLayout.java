package net.runelite.client.plugins.gpuvulkan;

import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupLayout;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupLayoutDesc;

import static org.lwjgl.vulkan.VK13.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK13.vkDestroyDescriptorSetLayout;

final class GfxBindGroupLayout implements BindGroupLayout
{
	private final VulkanDevice device;
	private long handle;
	private final BindGroupLayoutDesc desc;

	GfxBindGroupLayout(VulkanDevice device, long handle, BindGroupLayoutDesc desc)
	{
		this.device = device;
		this.handle = handle;
		this.desc = desc;
	}

	long handle() { return handle; }
	BindGroupLayoutDesc desc() { return desc; }

	@Override
	public void close()
	{
		if (handle != VK_NULL_HANDLE)
		{
			vkDestroyDescriptorSetLayout(device.handle(), handle, null);
			handle = VK_NULL_HANDLE;
		}
	}
}
