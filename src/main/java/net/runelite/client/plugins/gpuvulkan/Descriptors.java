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
 * Single combined-image-sampler descriptor: binding 0 in the fragment shader.
 * One pool, one layout, one set — no per-frame variation yet (M4 always
 * samples the same UI texture).
 *
 * <p>{@link #updateBinding} is called whenever the texture is recreated
 * (canvas resize → new VkImageView → set must be rewritten).
 */
final class Descriptors implements AutoCloseable
{
	private final VulkanDevice device;
	private final long pool;
	private final long layout;
	private final long descriptorSet;

	Descriptors(VulkanDevice device)
	{
		this.device = device;
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
				.descriptorCount(1);

			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
				.sType$Default()
				.maxSets(1)
				.pPoolSizes(sizes);
			LongBuffer pPool = stack.mallocLong(1);
			if (vkCreateDescriptorPool(device.handle(), poolInfo, null, pPool) != VK_SUCCESS)
			{
				vkDestroyDescriptorSetLayout(device.handle(), layout, null);
				throw new RuntimeException("vkCreateDescriptorPool failed");
			}
			pool = pPool.get(0);

			VkDescriptorSetAllocateInfo alloc = VkDescriptorSetAllocateInfo.calloc(stack)
				.sType$Default()
				.descriptorPool(pool)
				.pSetLayouts(stack.longs(layout));
			LongBuffer pSet = stack.mallocLong(1);
			if (vkAllocateDescriptorSets(device.handle(), alloc, pSet) != VK_SUCCESS)
			{
				vkDestroyDescriptorPool(device.handle(), pool, null);
				vkDestroyDescriptorSetLayout(device.handle(), layout, null);
				throw new RuntimeException("vkAllocateDescriptorSets failed");
			}
			descriptorSet = pSet.get(0);
		}
	}

	long layout()
	{
		return layout;
	}

	long descriptorSet()
	{
		return descriptorSet;
	}

	void updateBinding(Texture texture)
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
				.dstSet(descriptorSet)
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
