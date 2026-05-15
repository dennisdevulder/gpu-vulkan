package net.runelite.client.plugins.gpuvulkan;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroup;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupDesc;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupLayout;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupLayoutDesc;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderEncoder;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderPipeline;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderPipelineDesc;
import net.runelite.client.plugins.gpuvulkan.gfx.Renderer;
import net.runelite.client.plugins.gpuvulkan.gfx.ShaderModule;
import net.runelite.client.plugins.gpuvulkan.gfx.StreamingImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Wrapper implementation of the {@code gfx} {@link Renderer} interface.
 * Adopts an already-constructed {@link VulkanDevice} / {@link FrameSync} /
 * {@link RenderPass}; produces resources backed by the existing
 * {@link Texture} / {@link Buffer} wrappers plus the gfx-local
 * {@link GfxRenderPipeline} / {@link GfxBindGroup} / {@link GfxStreamingImage}.
 */
final class GfxRenderer implements Renderer
{
	private final VulkanDevice device;
	private final FrameSync frameSync;
	private final RenderPass renderPass;

	GfxRenderer(VulkanDevice device, FrameSync frameSync, RenderPass renderPass)
	{
		this.device = device;
		this.frameSync = frameSync;
		this.renderPass = renderPass;
	}

	VulkanDevice device() { return device; }
	FrameSync frameSync() { return frameSync; }
	RenderPass renderPass() { return renderPass; }

	@Override
	public ShaderModule createShaderModule(byte[] spirv)
	{
		try (MemoryStack stack = stackPush())
		{
			ByteBuffer code = MemoryUtil.memAlloc(spirv.length).put(spirv).flip();
			try
			{
				VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
					.sType$Default()
					.pCode(code);
				LongBuffer p = stack.mallocLong(1);
				Vk.check("vkCreateShaderModule (gfx)",
					vkCreateShaderModule(device.handle(), info, null, p));
				return new GfxShaderModule(device, p.get(0));
			}
			finally
			{
				MemoryUtil.memFree(code);
			}
		}
	}

	@Override
	public BindGroupLayout createBindGroupLayout(BindGroupLayoutDesc desc)
	{
		try (MemoryStack stack = stackPush())
		{
			int n = desc.entries().size();
			VkDescriptorSetLayoutBinding.Buffer bindings =
				VkDescriptorSetLayoutBinding.calloc(n, stack);
			for (int i = 0; i < n; i++)
			{
				BindGroupLayoutDesc.Entry e = desc.entries().get(i);
				int dType = vulkanDescriptorType(e.kind);
				bindings.get(i)
					.binding(e.binding)
					.descriptorType(dType)
					.descriptorCount(1)
					.stageFlags(e.stages);
			}
			VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pBindings(bindings);
			LongBuffer p = stack.mallocLong(1);
			Vk.check("vkCreateDescriptorSetLayout (gfx)",
				vkCreateDescriptorSetLayout(device.handle(), info, null, p));
			return new GfxBindGroupLayout(device, p.get(0), desc);
		}
	}

	@Override
	public BindGroup createBindGroup(BindGroupDesc desc)
	{
		return new GfxBindGroup(this, desc);
	}

	@Override
	public RenderPipeline createRenderPipeline(RenderPipelineDesc desc)
	{
		return new GfxRenderPipeline(this, desc);
	}

	@Override
	public StreamingImage createStreamingImage(int width, int height)
	{
		return new GfxStreamingImage(device, frameSync, width, height);
	}

	@Override
	public int currentSlot()
	{
		return frameSync.currentFrame();
	}

	@Override
	public RenderEncoder encodeInto(VkCommandBuffer cmd)
	{
		return new GfxRenderEncoder(cmd);
	}

	@Override
	public void close()
	{
		// Adopted handles are not closed here; their owners (Disposables
		// stack in GpuVulkanPlugin) handle that. Future Phase 2 work moves
		// instance/device/swapchain ownership into this layer; at that
		// point close() destroys them.
	}

	private static int vulkanDescriptorType(BindGroupLayoutDesc.BindingKind kind)
	{
		switch (kind)
		{
			case COMBINED_IMAGE_SAMPLER:
				return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
			default:
				throw new IllegalArgumentException("Unhandled binding kind: " + kind);
		}
	}
}
