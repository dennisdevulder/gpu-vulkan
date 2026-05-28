package net.runelite.client.plugins.gpuvulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK13.*;

final class ModelComputePipeline implements AutoCloseable
{
	private final VulkanDevice device;
	private long descriptorSetLayout;
	private long pipelineLayout;
	private long pipeline;

	ModelComputePipeline(VulkanDevice device)
	{
		this.device = device;
		long shaderModule = VK_NULL_HANDLE;
		try (MemoryStack stack = stackPush())
		{
			shaderModule = createShaderModule(loadResource("model_instance.comp.spv"));
			ByteBuffer entry = stack.UTF8("main");

			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
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

			VkDescriptorSetLayoutCreateInfo dsInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pBindings(bindings);
			LongBuffer pDsl = stack.mallocLong(1);
			Vk.check("vkCreateDescriptorSetLayout (model compute)",
				vkCreateDescriptorSetLayout(device.handle(), dsInfo, null, pDsl));
			descriptorSetLayout = pDsl.get(0);

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

	long descriptorSetLayout()
	{
		return descriptorSetLayout;
	}

	long pipelineLayout()
	{
		return pipelineLayout;
	}

	long pipeline()
	{
		return pipeline;
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
		if (descriptorSetLayout != VK_NULL_HANDLE)
		{
			vkDestroyDescriptorSetLayout(device.handle(), descriptorSetLayout, null);
			descriptorSetLayout = VK_NULL_HANDLE;
		}
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
