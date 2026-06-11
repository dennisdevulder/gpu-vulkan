/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.gpuvulkan;

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
 * Single-subpass render pass. MSAA: [msaa color, msaa depth, single-sample
 * resolve = final image]; at 1x the resolve is dropped and attachment 0 is
 * the final color image.
 */
final class RenderPass implements AutoCloseable
{
	private final VulkanDevice device;
	private long handle;
	private final int samples;

	RenderPass(VulkanDevice device, int colorFormat, int samples, boolean swapchainPresent)
	{
		this.device = device;
		this.samples = samples;
		try (MemoryStack stack = stackPush())
		{
			boolean msaa = samples != VK_SAMPLE_COUNT_1_BIT;
			int finalColorLayout = swapchainPresent
				? KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
				: VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
			VkAttachmentDescription.Buffer attachments =
				buildAttachments(stack, colorFormat, msaa, finalColorLayout);
			VkSubpassDescription.Buffer subpass = buildSubpass(stack, msaa);
			VkSubpassDependency.Buffer dep = buildFrameDependency(stack, swapchainPresent);

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

	private VkAttachmentDescription.Buffer buildAttachments(MemoryStack stack, int colorFormat,
		boolean msaa, int finalColorLayout)
	{
		int attachmentCount = msaa ? 3 : 2;
		VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(attachmentCount, stack);

		// Attachment 0: color. MSAA resolves out (no store); without MSAA this
		// IS the final image, so STORE + present-path finalLayout.
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
				: finalColorLayout);

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

		if (msaa)
		{
			// Attachment 2: resolve = single-sample final color image.
			attachments.get(2)
				.format(colorFormat)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
				.storeOp(VK_ATTACHMENT_STORE_OP_STORE)
				.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
				.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
				.finalLayout(finalColorLayout);
		}
		return attachments;
	}

	private static VkSubpassDescription.Buffer buildSubpass(MemoryStack stack, boolean msaa)
	{
		VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
		colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

		VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
			.attachment(1)
			.layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

		VkAttachmentReference.Buffer resolveRef = null;
		if (msaa)
		{
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
		return subpass;
	}

	private static VkSubpassDependency.Buffer buildFrameDependency(MemoryStack stack, boolean swapchainPresent)
	{
		// Frame-to-frame sync: the depth image is reused across frames, so frame
		// N+1's depth write hazards frame N's LATE_FRAGMENT_TESTS write
		// (WRITE_AFTER_WRITE) unless declared here. Offscreen adds WAR vs post-pass reads.
		int srcStages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
			| VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
		if (!swapchainPresent)
		{
			srcStages |= VK_PIPELINE_STAGE_TRANSFER_BIT
				| VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
		}
		VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, stack);
		dep.get(0)
			.srcSubpass(VK_SUBPASS_EXTERNAL)
			.dstSubpass(0)
			.srcStageMask(srcStages)
			.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
				| VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
			.dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
				| VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
			.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
				| VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
		return dep;
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
		if (handle != VK_NULL_HANDLE)
		{
			vkDestroyRenderPass(device.handle(), handle, null);
			handle = VK_NULL_HANDLE;
		}
	}
}
