package net.runelite.client.plugins.gpuvulkan;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Generic {@code VkBuffer + VkDeviceMemory} wrapper. M4 uses it for host-visible
 * staging (UI pixel upload). Map persistently — saves the per-frame map/unmap
 * round-trip that {@code GpuPlugin}'s {@code glMapBuffer} suffers.
 */
final class Buffer implements AutoCloseable
{
	private final VulkanDevice device;
	private final long handle;
	private final long memory;
	private final long size;
	private long mappedAddress;

	Buffer(VulkanDevice device, long size, int usage, int memoryProperties)
	{
		this.device = device;
		this.size = size;
		try (MemoryStack stack = stackPush())
		{
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
				.sType$Default()
				.size(size)
				.usage(usage)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer pBuf = stack.mallocLong(1);
			if (vkCreateBuffer(device.handle(), info, null, pBuf) != VK_SUCCESS)
			{
				throw new RuntimeException("vkCreateBuffer failed");
			}
			handle = pBuf.get(0);

			VkMemoryRequirements memReq = VkMemoryRequirements.calloc(stack);
			vkGetBufferMemoryRequirements(device.handle(), handle, memReq);

			int memType = findMemoryType(device, memReq.memoryTypeBits(), memoryProperties, stack);
			VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
				.sType$Default()
				.allocationSize(memReq.size())
				.memoryTypeIndex(memType);

			LongBuffer pMem = stack.mallocLong(1);
			if (vkAllocateMemory(device.handle(), alloc, null, pMem) != VK_SUCCESS)
			{
				vkDestroyBuffer(device.handle(), handle, null);
				throw new RuntimeException("vkAllocateMemory failed");
			}
			memory = pMem.get(0);
			vkBindBufferMemory(device.handle(), handle, memory, 0);
		}
	}

	long handle()
	{
		return handle;
	}

	long size()
	{
		return size;
	}

	void mapPersistent()
	{
		try (MemoryStack stack = stackPush())
		{
			PointerBuffer pp = stack.mallocPointer(1);
			if (vkMapMemory(device.handle(), memory, 0, size, 0, pp) != VK_SUCCESS)
			{
				throw new RuntimeException("vkMapMemory failed");
			}
			mappedAddress = pp.get(0);
		}
	}

	/** Direct access to the mapped region as a ByteBuffer. */
	ByteBuffer mappedByteBuffer()
	{
		if (mappedAddress == 0L) throw new IllegalStateException("buffer not mapped");
		return memByteBuffer(mappedAddress, (int) size);
	}

	/** Convenience: copy an int[] (one pixel = one int) into the mapped region. */
	void writeInts(int[] src, int srcOffset, int count)
	{
		IntBuffer dst = mappedByteBuffer().asIntBuffer();
		dst.put(src, srcOffset, count);
	}

	@Override
	public void close()
	{
		if (mappedAddress != 0L)
		{
			vkUnmapMemory(device.handle(), memory);
			mappedAddress = 0L;
		}
		vkDestroyBuffer(device.handle(), handle, null);
		vkFreeMemory(device.handle(), memory, null);
	}

	static int findMemoryType(VulkanDevice device, int typeBits, int properties, MemoryStack stack)
	{
		VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
		vkGetPhysicalDeviceMemoryProperties(device.physicalDevice(), memProps);
		for (int i = 0; i < memProps.memoryTypeCount(); i++)
		{
			boolean typeOk = (typeBits & (1 << i)) != 0;
			boolean propsOk = (memProps.memoryTypes(i).propertyFlags() & properties) == properties;
			if (typeOk && propsOk)
			{
				return i;
			}
		}
		throw new RuntimeException("No memory type matches typeBits=" + typeBits + " props=" + properties);
	}
}
