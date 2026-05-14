package net.runelite.client.plugins.gpuvulkan;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Single combined-image-sampler descriptor (binding 0 in the UI fragment
 * shader), allocated once per in-flight frame slot. Each slot's set is bound
 * to its own UI texture so frame N's GPU sampling can't conflict with frame
 * N+1's GPU texture write — without per-slot sets we'd be rebinding the
 * SAME set's image while the previous frame might still be reading it.
 */
final class Descriptors implements AutoCloseable
{
	private final VulkanDevice device;
	private final long pool;
	private final long layout;
	private final long[] descriptorSets;

	Descriptors(VulkanDevice device)
	{
		this.device = device;
		this.descriptorSets = new long[FrameSync.FRAMES_IN_FLIGHT];
		try (MemoryStack stack = stackPush())
		{
			VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
			binding.get(0)
				.binding(0)
				.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.descriptorCount(1)
				.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);

			VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pBindings(binding);
			LongBuffer pLayout = stack.mallocLong(1);
			if (vkCreateDescriptorSetLayout(device.handle(), layoutInfo, null, pLayout) != VK_SUCCESS)
			{
				throw new RuntimeException("vkCreateDescriptorSetLayout failed");
			}
			layout = pLayout.get(0);

			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
			sizes.get(0)
				.type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.descriptorCount(FrameSync.FRAMES_IN_FLIGHT);

			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
				.sType$Default()
				.maxSets(FrameSync.FRAMES_IN_FLIGHT)
				.pPoolSizes(sizes);
			LongBuffer pPool = stack.mallocLong(1);
			if (vkCreateDescriptorPool(device.handle(), poolInfo, null, pPool) != VK_SUCCESS)
			{
				vkDestroyDescriptorSetLayout(device.handle(), layout, null);
				throw new RuntimeException("vkCreateDescriptorPool failed");
			}
			pool = pPool.get(0);

			LongBuffer pLayouts = stack.mallocLong(FrameSync.FRAMES_IN_FLIGHT);
			for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
			{
				pLayouts.put(i, layout);
			}
			VkDescriptorSetAllocateInfo alloc = VkDescriptorSetAllocateInfo.calloc(stack)
				.sType$Default()
				.descriptorPool(pool)
				.pSetLayouts(pLayouts);
			LongBuffer pSets = stack.mallocLong(FrameSync.FRAMES_IN_FLIGHT);
			if (vkAllocateDescriptorSets(device.handle(), alloc, pSets) != VK_SUCCESS)
			{
				vkDestroyDescriptorPool(device.handle(), pool, null);
				vkDestroyDescriptorSetLayout(device.handle(), layout, null);
				throw new RuntimeException("vkAllocateDescriptorSets failed");
			}
			for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
			{
				descriptorSets[i] = pSets.get(i);
			}
		}
	}

	long layout()
	{
		return layout;
	}

	long descriptorSet(int slot)
	{
		return descriptorSets[slot];
	}

	void updateBinding(int slot, Texture texture)
	{
		try (MemoryStack stack = stackPush())
		{
			VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
			imgInfo.get(0)
				.sampler(texture.sampler())
				.imageView(texture.view())
				.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

			VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
			write.get(0)
				.sType$Default()
				.dstSet(descriptorSets[slot])
				.dstBinding(0)
				.dstArrayElement(0)
				.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.descriptorCount(1)
				.pImageInfo(imgInfo);

			vkUpdateDescriptorSets(device.handle(), write, null);
		}
	}

	@Override
	public void close()
	{
		vkDestroyDescriptorPool(device.handle(), pool, null);
		vkDestroyDescriptorSetLayout(device.handle(), layout, null);
	}
}
