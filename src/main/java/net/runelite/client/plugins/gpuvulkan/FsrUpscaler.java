/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package net.runelite.client.plugins.gpuvulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroup;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupDesc;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupLayout;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupLayoutDesc;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderDevice;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderPipeline;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderPipelineDesc;
import net.runelite.client.plugins.gpuvulkan.gfx.ShaderModule;
import net.runelite.client.plugins.gpuvulkan.gfx.ShaderStage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

final class FsrUpscaler implements AutoCloseable
{
	private final RenderDevice renderer;
	private final BindGroupLayout bgl;
	private final ShaderModule vertex;
	private final ShaderModule fragment;
	private final RenderPipeline pipeline;

	private BindGroup bindGroup;
	private long boundView;
	private long boundSampler;

	FsrUpscaler(RenderDevice renderer)
	{
		this.renderer = renderer;
		this.bgl = renderer.createBindGroupLayout(BindGroupLayoutDesc.builder()
			.combinedImageSampler(0, ShaderStage.FRAGMENT)
			.build());
		this.vertex = renderer.createShaderModule(loadResource("ui.vert.spv"));
		this.fragment = renderer.createShaderModule(loadResource("fsr1.frag.spv"));
		this.pipeline = renderer.createRenderPipeline(RenderPipelineDesc.builder()
			.vertex(vertex)
			.fragment(fragment)
			.blendMode(RenderPipelineDesc.BlendMode.NONE)
			.depthTest(RenderPipelineDesc.DepthTest.OFF)
			.addBindGroupLayout(bgl)
			.addPushConstantRange(ShaderStage.FRAGMENT, 0, 16)
			.build());
	}

	void bindSource(long imageView, long sampler)
	{
		if (bindGroup != null && boundView == imageView && boundSampler == sampler)
		{
			return;
		}
		if (bindGroup != null)
		{
			bindGroup.close();
		}
		bindGroup = renderer.createBindGroup(BindGroupDesc.builder(bgl)
			.sampledImage(0, imageView, sampler)
			.build());
		boundView = imageView;
		boundSampler = sampler;
	}

	void recordDraw(VkCommandBuffer cmd, int targetWidth, int targetHeight,
		int sourceWidth, int sourceHeight, float sharpness)
	{
		if (bindGroup == null)
		{
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			ByteBuffer push = stack.malloc(16);
			push.putFloat(sourceWidth).putFloat(sourceHeight).putFloat(sharpness).putFloat(0f);
			push.flip();
			renderer.encodeInto(cmd)
				.setViewport(0, 0, targetWidth, targetHeight)
				.setScissor(0, 0, targetWidth, targetHeight)
				.bindPipeline(pipeline)
				.bindBindGroup(0, bindGroup)
				.pushConstants(ShaderStage.FRAGMENT, 0, push)
				.draw(3, 1, 0, 0);
		}
	}

	@Override
	public void close()
	{
		if (bindGroup != null)
		{
			bindGroup.close();
			bindGroup = null;
		}
		pipeline.close();
		vertex.close();
		fragment.close();
		bgl.close();
	}

	private static byte[] loadResource(String resource)
	{
		try (InputStream in = FsrUpscaler.class.getResourceAsStream(resource))
		{
			if (in == null) throw new RuntimeException("missing resource: " + resource);
			return in.readAllBytes();
		}
		catch (IOException e)
		{
			throw new RuntimeException("failed to read " + resource, e);
		}
	}
}
