package net.runelite.client.plugins.gpuvulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK13.*;

final class ModelComputePipeline implements AutoCloseable
{
	private final VulkanDevice device;
	private final long[] descriptorSets = new long[FrameSync.FRAMES_IN_FLIGHT];
	private long descriptorSetLayout;
	private long descriptorPool;
	private long pipelineLayout;
	private long pipeline;

	ModelComputePipeline(VulkanDevice device, long meshBuffer, long meshBytes,
		long instanceBuffer, long instanceFrameBytes, long outputBuffer, long outputFrameBytes)
	{
		this.device = device;
		long shaderModule = VK_NULL_HANDLE;
		try (MemoryStack stack = stackPush())
		{
			shaderModule = createShaderModule(loadResource("model_instance.comp.spv"));
			ByteBuffer entry = stack.UTF8("main");

			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
			bindings.get(0)
				.binding(0)
				.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
				.descriptorCount(1)
				.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			bindings.get(1)
				.binding(1)
				.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
				.descriptorCount(1)
				.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			bindings.get(2)
				.binding(2)
				.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
				.descriptorCount(1)
				.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

			VkDescriptorSetLayoutCreateInfo dsInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pBindings(bindings);
			LongBuffer pDsl = stack.mallocLong(1);
			Vk.check("vkCreateDescriptorSetLayout (model compute)",
				vkCreateDescriptorSetLayout(device.handle(), dsInfo, null, pDsl));
			descriptorSetLayout = pDsl.get(0);
			createDescriptorSets(meshBuffer, meshBytes, instanceBuffer, instanceFrameBytes,
				outputBuffer, outputFrameBytes, stack);

			VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(1, stack);
			pc.get(0)
				.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
				.offset(0)
				.size(4);

			VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pSetLayouts(stack.longs(descriptorSetLayout))
				.pPushConstantRanges(pc);
			LongBuffer pLayout = stack.mallocLong(1);
			Vk.check("vkCreatePipelineLayout (model compute)",
				vkCreatePipelineLayout(device.handle(), layoutInfo, null, pLayout));
			pipelineLayout = pLayout.get(0);

			VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
				.sType$Default()
				.stage(VK_SHADER_STAGE_COMPUTE_BIT)
				.module(shaderModule)
				.pName(entry);

			VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
			info.get(0)
				.sType$Default()
				.stage(stage)
				.layout(pipelineLayout);

			LongBuffer pPipeline = stack.mallocLong(1);
			Vk.check("vkCreateComputePipelines (model compute)",
				vkCreateComputePipelines(device.handle(), VK_NULL_HANDLE, info, null, pPipeline));
			pipeline = pPipeline.get(0);
		}
		catch (RuntimeException e)
		{
			close();
			throw e;
		}
		finally
		{
			if (shaderModule != VK_NULL_HANDLE)
			{
				vkDestroyShaderModule(device.handle(), shaderModule, null);
			}
		}
	}

	void recordDispatch(VkCommandBuffer cmd, int frameSlot, int instanceCount)
	{
		if (instanceCount <= 0)
		{
			return;
		}

		try (MemoryStack stack = stackPush())
		{
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
			vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
				pipelineLayout, 0, stack.longs(descriptorSets[frameSlot]), null);

			ByteBuffer push = stack.malloc(4);
			push.putInt(instanceCount).flip();
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
			vkCmdDispatch(cmd, (instanceCount + 63) / 64, 1, 1);
		}
	}

	@Override
	public void close()
	{
		if (pipeline != VK_NULL_HANDLE)
		{
			vkDestroyPipeline(device.handle(), pipeline, null);
			pipeline = VK_NULL_HANDLE;
		}
		if (pipelineLayout != VK_NULL_HANDLE)
		{
			vkDestroyPipelineLayout(device.handle(), pipelineLayout, null);
			pipelineLayout = VK_NULL_HANDLE;
		}
		if (descriptorPool != VK_NULL_HANDLE)
		{
			vkDestroyDescriptorPool(device.handle(), descriptorPool, null);
			descriptorPool = VK_NULL_HANDLE;
		}
		if (descriptorSetLayout != VK_NULL_HANDLE)
		{
			vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
			descriptorSetLayout = VK_NULL_HANDLE;
		}
	}

	private void createDescriptorSets(long meshBuffer, long meshBytes,
		long instanceBuffer, long instanceFrameBytes, long outputBuffer, long outputFrameBytes,
		MemoryStack stack)
	{
		VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
		poolSizes.get(0)
			.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
			.descriptorCount(3 * FrameSync.FRAMES_IN_FLIGHT);

		VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
			.sType$Default()
			.maxSets(FrameSync.FRAMES_IN_FLIGHT)
			.pPoolSizes(poolSizes);
		LongBuffer pPool = stack.mallocLong(1);
		Vk.check("vkCreateDescriptorPool (model compute)",
			vkCreateDescriptorPool(device.handle(), poolInfo, null, pPool));
		descriptorPool = pPool.get(0);

		LongBuffer layouts = stack.mallocLong(FrameSync.FRAMES_IN_FLIGHT);
		for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
		{
			layouts.put(i, descriptorSetLayout);
		}

		VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
			.sType$Default()
			.descriptorPool(descriptorPool)
			.pSetLayouts(layouts);
		LongBuffer sets = stack.mallocLong(FrameSync.FRAMES_IN_FLIGHT);
		Vk.check("vkAllocateDescriptorSets (model compute)",
			vkAllocateDescriptorSets(device.handle(), allocInfo, sets));
		for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
		{
			descriptorSets[i] = sets.get(i);
		}

		VkDescriptorBufferInfo.Buffer bufferInfo =
			VkDescriptorBufferInfo.calloc(3 * FrameSync.FRAMES_IN_FLIGHT, stack);
		VkWriteDescriptorSet.Buffer writes =
			VkWriteDescriptorSet.calloc(3 * FrameSync.FRAMES_IN_FLIGHT, stack);
		for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
		{
			int meshInfo = i * 3;
			int instanceInfo = meshInfo + 1;
			int outputInfo = meshInfo + 2;

			bufferInfo.get(meshInfo)
				.buffer(meshBuffer)
				.offset(0)
				.range(meshBytes);
			bufferInfo.get(instanceInfo)
				.buffer(instanceBuffer)
				.offset(instanceFrameBytes * i)
				.range(instanceFrameBytes);
			bufferInfo.get(outputInfo)
				.buffer(outputBuffer)
				.offset(outputFrameBytes * i)
				.range(outputFrameBytes);

			writes.get(meshInfo)
				.sType$Default()
				.dstSet(descriptorSets[i])
				.dstBinding(0)
				.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
				.pBufferInfo(VkDescriptorBufferInfo.create(bufferInfo.get(meshInfo).address(), 1));
			writes.get(instanceInfo)
				.sType$Default()
				.dstSet(descriptorSets[i])
				.dstBinding(1)
				.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
				.pBufferInfo(VkDescriptorBufferInfo.create(bufferInfo.get(instanceInfo).address(), 1));
			writes.get(outputInfo)
				.sType$Default()
				.dstSet(descriptorSets[i])
				.dstBinding(2)
				.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
				.pBufferInfo(VkDescriptorBufferInfo.create(bufferInfo.get(outputInfo).address(), 1));
		}
		vkUpdateDescriptorSets(device.handle(), writes, null);
	}

	private long createShaderModule(ByteBuffer code)
	{
		try (MemoryStack stack = stackPush())
		{
			VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
				.sType$Default()
				.pCode(code);
			LongBuffer p = stack.mallocLong(1);
			Vk.check("vkCreateShaderModule (model compute)",
				vkCreateShaderModule(device.handle(), info, null, p));
			return p.get(0);
		}
		finally
		{
			memFree(code);
		}
	}

	private static ByteBuffer loadResource(String resource)
	{
		try (InputStream in = ModelComputePipeline.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				throw new RuntimeException("missing resource: " + resource);
			}
			byte[] bytes = in.readAllBytes();
			ByteBuffer buf = memAlloc(bytes.length);
			buf.put(bytes).flip();
			return buf;
		}
		catch (IOException e)
		{
			throw new RuntimeException("failed to read " + resource, e);
		}
	}
}
