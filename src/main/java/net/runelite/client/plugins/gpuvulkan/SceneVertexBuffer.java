package net.runelite.client.plugins.gpuvulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Owns the scene's host-visible vertex buffer and its descriptor set. The
 * buffer contains one static scene arena followed by {@code framesInFlight}
 * dynamic/overlay arenas. The CPU can write the next frame's dynamic geometry
 * while the GPU is still reading the previous frame's arena. Static scene
 * geometry lives once at the start of the buffer and is drawn with a zero
 * first-vertex base.
 *
 * <p>The descriptor set has two bindings: 0 = OSRS texture array (combined
 * image sampler), 1 = texture-animation UBO. Both are static for the plugin
 * lifetime — animation parameters are written into the UBO each frame, but
 * the binding itself does not change.
 */
final class SceneVertexBuffer implements AutoCloseable
{
	private final VulkanDevice device;
	private final Buffer vertexBuffer;
	private final ByteBuffer mapped;
	private final long slotBytes;
	private final long descriptorPool;
	private final long descriptorSet;

	SceneVertexBuffer(VulkanDevice device, long totalBytes, long slotBytes,
		long descriptorSetLayout, TextureArray textureArray)
	{
		this.device = device;
		this.slotBytes = slotBytes;
		this.vertexBuffer = new Buffer(device, totalBytes,
			VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
			VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		vertexBuffer.mapPersistent();
		this.mapped = vertexBuffer.mappedByteBuffer().order(ByteOrder.nativeOrder());

		try (MemoryStack stack = stackPush())
		{
			// Pool needs slots for both descriptor types:
			//   binding 0 = combined image sampler (texture array)
			//   binding 1 = uniform buffer (texture animations)
			VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
			poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
			poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)        .descriptorCount(1);
			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
				.sType$Default()
				.maxSets(1)
				.pPoolSizes(poolSizes);
			LongBuffer pPool = stack.mallocLong(1);
			Vk.check("vkCreateDescriptorPool (scene)", vkCreateDescriptorPool(device.handle(), poolInfo, null, pPool));
			descriptorPool = pPool.get(0);

			VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
				.sType$Default()
				.descriptorPool(descriptorPool)
				.pSetLayouts(stack.longs(descriptorSetLayout));
			LongBuffer pSet = stack.mallocLong(1);
			Vk.check("vkAllocateDescriptorSets (scene)", vkAllocateDescriptorSets(device.handle(), allocInfo, pSet));
			descriptorSet = pSet.get(0);

			VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
			imgInfo.get(0)
				.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
				.imageView(textureArray.view())
				.sampler(textureArray.sampler());

			VkDescriptorBufferInfo.Buffer animBufInfo = VkDescriptorBufferInfo.calloc(1, stack);
			animBufInfo.get(0)
				.buffer(textureArray.animationUboHandle())
				.offset(0)
				.range(textureArray.animationUboSize());

			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
			writes.get(0)
				.sType$Default()
				.dstSet(descriptorSet)
				.dstBinding(0)
				.dstArrayElement(0)
				.descriptorCount(1)
				.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(imgInfo);
			writes.get(1)
				.sType$Default()
				.dstSet(descriptorSet)
				.dstBinding(1)
				.dstArrayElement(0)
				.descriptorCount(1)
				.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
				.pBufferInfo(animBufInfo);
			vkUpdateDescriptorSets(device.handle(), writes, null);
		}
	}

	long handle() { return vertexBuffer.handle(); }
	ByteBuffer mapped() { return mapped; }
	long slotBytes() { return slotBytes; }
	long descriptorSet() { return descriptorSet; }

	@Override
	public void close()
	{
		// vkDestroyDescriptorPool implicitly frees all sets allocated from it.
		vkDestroyDescriptorPool(device.handle(), descriptorPool, null);
		vertexBuffer.close();
	}
}
