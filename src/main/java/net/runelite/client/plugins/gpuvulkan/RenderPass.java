package net.runelite.client.plugins.gpuvulkan;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Single-subpass render pass with three attachments when MSAA is on:
 * <ul>
 *   <li>0: multi-sampled color (rendered into; not stored, drivers may keep
 *       it in tile memory).</li>
 *   <li>1: multi-sampled depth (rendered into; not stored).</li>
 *   <li>2: single-sampled resolve target = the swapchain image (driver
 *       writes the resolved color into this at end-of-subpass; stored and
 *       transitioned to PRESENT_SRC).</li>
 * </ul>
 *
 * <p>When {@code samples == VK_SAMPLE_COUNT_1_BIT} we drop the resolve and
 * render directly into the swapchain image (attachment 0). Same renderpass
 * shape used by stock GpuPlugin's FBO path.
 */
final class RenderPass implements AutoCloseable
{
	private final VulkanDevice device;
	private final long handle;
	private final int samples;

	RenderPass(VulkanDevice device, int colorFormat, int samples)
	{
		this(device, colorFormat, samples, true);
	}

	RenderPass(VulkanDevice device, int colorFormat, int samples, boolean presentFinalLayout)
	{
		this.device = device;
		this.samples = samples;
		try (MemoryStack stack = stackPush())
		{
			boolean msaa = samples != VK_SAMPLE_COUNT_1_BIT;
			int attachmentCount = msaa ? 3 : 2;
			VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(attachmentCount, stack);

			// Attachment 0: color. With MSAA we render into the multi-sampled
			// image then resolve out, so we don't need to store it. Without
			// MSAA we render directly into the swapchain image so storeOp is
			// STORE and finalLayout is PRESENT_SRC.
			attachments.get(0)
				.format(colorFormat)
				.samples(samples)
				.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
				.storeOp(msaa ? VK_ATTACHMENT_STORE_OP_DONT_CARE : VK_ATTACHMENT_STORE_OP_STORE)
				.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
				.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
				.finalLayout(msaa
					? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
					: presentFinalLayout
						? KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
						: VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

			// Attachment 1: depth. Same sample count as color. Never stored.
			attachments.get(1)
				.format(DepthBuffer.FORMAT)
				.samples(samples)
				.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
				.storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
				.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
				.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
				.finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

			VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
			colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

			VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
				.attachment(1)
				.layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

			VkAttachmentReference.Buffer resolveRef = null;
			if (msaa)
			{
				// Attachment 2: resolve = single-sample swapchain image.
				attachments.get(2)
					.format(colorFormat)
					.samples(VK_SAMPLE_COUNT_1_BIT)
					.loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
					.storeOp(VK_ATTACHMENT_STORE_OP_STORE)
					.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
					.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
					.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
					.finalLayout(presentFinalLayout
						? KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
						: VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

				resolveRef = VkAttachmentReference.calloc(1, stack);
				resolveRef.get(0).attachment(2).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
			}

			VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
			subpass.get(0)
				.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
				.colorAttachmentCount(1)
				.pColorAttachments(colorRef)
				.pDepthStencilAttachment(depthRef)
				.pResolveAttachments(resolveRef);

			// Frame-to-frame sync. The color attachment for swapchain images
			// is per-image (acquired via the semaphore — UNDEFINED initial
			// layout absorbs that side). But the depth attachment is a single
			// image reused across frames, so frame N+1's depth write hazards
			// against frame N's LATE_FRAGMENT_TESTS write unless we declare
			// the dependency here. srcAccessMask = 0 (the previous value)
			// trips validation's WRITE_AFTER_WRITE check on every frame.
			//
			// Cover both attachments on the src side so the same dep works
			// regardless of whether the swapchain image happens to be the
			// same one we used a few frames ago (UNDEFINED handles the
			// content-discard half; this handles the write-ordering half).
			VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, stack);
			dep.get(0)
				.srcSubpass(VK_SUBPASS_EXTERNAL)
				.dstSubpass(0)
				.srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
					| VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
				.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
					| VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
				.dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
					| VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
				.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
					| VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

			VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack)
				.sType$Default()
				.pAttachments(attachments)
				.pSubpasses(subpass)
				.pDependencies(dep);

			LongBuffer p = stack.mallocLong(1);
			Vk.check("vkCreateRenderPass", vkCreateRenderPass(device.handle(), info, null, p));
			handle = p.get(0);
		}
	}

	long handle()
	{
		return handle;
	}

	int samples()
	{
		return samples;
	}

	@Override
	public void close()
	{
		vkDestroyRenderPass(device.handle(), handle, null);
	}
}
