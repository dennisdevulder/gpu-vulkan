package net.runelite.client.plugins.gpuvulkan;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * One {@link org.lwjgl.vulkan.VK13#vkCreateFramebuffer VkFramebuffer} per
 * swapchain image, all sharing the same render pass. Each framebuffer pairs
 * the per-image colour view with the (single) depth view from
 * {@link DepthBuffer}.
 *
 * <p>{@link #recreate} rebuilds in-place on swapchain resize so any external
 * reference (e.g. a Disposables entry) stays valid.
 */
final class Framebuffers implements AutoCloseable
{
	private final VulkanDevice device;
	private long[] handles = new long[0];

	Framebuffers(VulkanDevice device, RenderPass renderPass, Swapchain swapchain,
		DepthBuffer depthBuffer, MsaaColorBuffer msaaColor)
	{
		this.device = device;
		create(renderPass, swapchain, depthBuffer, msaaColor);
	}

	void recreate(RenderPass renderPass, Swapchain swapchain,
		DepthBuffer depthBuffer, MsaaColorBuffer msaaColor)
	{
		destroy();
		create(renderPass, swapchain, depthBuffer, msaaColor);
	}

	/** Destroy current framebuffers without recreating. Callers that need
	 *  fine-grained ordering (e.g. {@code rebuildSwapchain} wants to drop
	 *  framebuffers BEFORE the swapchain destroys the image views they
	 *  reference) use this + {@link #recreate} explicitly. */
	void destroyAll()
	{
		destroy();
	}

	long get(int i)
	{
		return handles[i];
	}

	@Override
	public void close()
	{
		destroy();
	}

	private void create(RenderPass renderPass, Swapchain swapchain,
		DepthBuffer depthBuffer, MsaaColorBuffer msaaColor)
	{
		long[] views = swapchain.imageViews();
		handles = new long[views.length];
		try (MemoryStack stack = stackPush())
		{
			for (int i = 0; i < views.length; i++)
			{
				// MSAA layout matches RenderPass: [msaaColor, depth, swapchain
				// resolve target]. Without MSAA the renderpass declares only
				// two attachments and the swapchain image IS the color target.
				LongBuffer attachments = msaaColor != null
					? stack.longs(msaaColor.view(), depthBuffer.view(), views[i])
					: stack.longs(views[i], depthBuffer.view());
				VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
					.sType$Default()
					.renderPass(renderPass.handle())
					.pAttachments(attachments)
					.width(swapchain.width())
					.height(swapchain.height())
					.layers(1);
				LongBuffer p = stack.mallocLong(1);
				if (vkCreateFramebuffer(device.handle(), info, null, p) != VK_SUCCESS)
				{
					throw new RuntimeException("vkCreateFramebuffer failed");
				}
				handles[i] = p.get(0);
			}
		}
	}

	private void destroy()
	{
		for (long fb : handles)
		{
			if (fb != VK_NULL_HANDLE)
			{
				vkDestroyFramebuffer(device.handle(), fb, null);
			}
		}
		handles = new long[0];
	}
}
