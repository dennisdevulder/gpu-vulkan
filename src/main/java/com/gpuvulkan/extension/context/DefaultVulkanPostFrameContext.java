/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

import org.lwjgl.vulkan.VkCommandBuffer;

final class DefaultVulkanPostFrameContext implements VulkanPostFrameContext
{
	private final VkCommandBuffer commandBuffer;
	private final long colorImage;
	private final int width;
	private final int height;
	private final int imageLayout;
	private final int imageFormat;
	private final int frameIndex;

	DefaultVulkanPostFrameContext(VkCommandBuffer commandBuffer, long colorImage,
		int width, int height, int imageLayout, int imageFormat, int frameIndex)
	{
		this.commandBuffer = commandBuffer;
		this.colorImage = colorImage;
		this.width = width;
		this.height = height;
		this.imageLayout = imageLayout;
		this.imageFormat = imageFormat;
		this.frameIndex = frameIndex;
	}

	@Override
	public VkCommandBuffer commandBuffer()
	{
		return commandBuffer;
	}

	@Override
	public long colorImage()
	{
		return colorImage;
	}

	@Override
	public int width()
	{
		return width;
	}

	@Override
	public int height()
	{
		return height;
	}

	@Override
	public int imageLayout()
	{
		return imageLayout;
	}

	@Override
	public int imageFormat()
	{
		return imageFormat;
	}

	@Override
	public int frameIndex()
	{
		return frameIndex;
	}
}
