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
	private final RenderDevice easuRenderer;
	private final RenderDevice rcasRenderer;
	private final BindGroupLayout bgl;
	private final ShaderModule vertex;
	private final ShaderModule easuFragment;
	private final ShaderModule rcasFragment;
	private final RenderPipeline easuPipeline;
	private final RenderPipeline rcasPipeline;

	private BindGroup easuBindGroup;
	private long easuBoundView;
	private long easuBoundSampler;
	private BindGroup rcasBindGroup;
	private long rcasBoundView;
	private long rcasBoundSampler;

	FsrUpscaler(RenderDevice easuRenderer, RenderDevice rcasRenderer)
	{
		this.easuRenderer = easuRenderer;
		this.rcasRenderer = rcasRenderer;
		this.bgl = rcasRenderer.createBindGroupLayout(BindGroupLayoutDesc.builder()
			.combinedImageSampler(0, ShaderStage.FRAGMENT)
			.build());
		this.vertex = rcasRenderer.createShaderModule(loadResource("ui.vert.spv"));
		this.easuFragment = rcasRenderer.createShaderModule(loadResource("fsr1_easu.frag.spv"));
		this.rcasFragment = rcasRenderer.createShaderModule(loadResource("fsr1_rcas.frag.spv"));
		this.easuPipeline = easuRenderer.createRenderPipeline(RenderPipelineDesc.builder()
			.vertex(vertex)
			.fragment(easuFragment)
			.blendMode(RenderPipelineDesc.BlendMode.NONE)
			.depthTest(RenderPipelineDesc.DepthTest.OFF)
			.addBindGroupLayout(bgl)
			.addPushConstantRange(ShaderStage.FRAGMENT, 0, 16)
			.build());
		this.rcasPipeline = rcasRenderer.createRenderPipeline(RenderPipelineDesc.builder()
			.vertex(vertex)
			.fragment(rcasFragment)
			.blendMode(RenderPipelineDesc.BlendMode.NONE)
			.depthTest(RenderPipelineDesc.DepthTest.OFF)
			.addBindGroupLayout(bgl)
			.addPushConstantRange(ShaderStage.FRAGMENT, 0, 16)
			.build());
	}

	void bindEasuSource(long imageView, long sampler)
	{
		if (easuBindGroup != null && easuBoundView == imageView && easuBoundSampler == sampler)
		{
			return;
		}
		if (easuBindGroup != null)
		{
			easuBindGroup.close();
		}
		easuBindGroup = easuRenderer.createBindGroup(BindGroupDesc.builder(bgl)
			.sampledImage(0, imageView, sampler)
			.build());
		easuBoundView = imageView;
		easuBoundSampler = sampler;
	}

	void bindRcasSource(long imageView, long sampler)
	{
		if (rcasBindGroup != null && rcasBoundView == imageView && rcasBoundSampler == sampler)
		{
			return;
		}
		if (rcasBindGroup != null)
		{
			rcasBindGroup.close();
		}
		rcasBindGroup = rcasRenderer.createBindGroup(BindGroupDesc.builder(bgl)
			.sampledImage(0, imageView, sampler)
			.build());
		rcasBoundView = imageView;
		rcasBoundSampler = sampler;
	}

	void recordEasu(VkCommandBuffer cmd, int outputWidth, int outputHeight,
		int sourceWidth, int sourceHeight)
	{
		if (easuBindGroup == null)
		{
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			ByteBuffer push = stack.malloc(16);
			push.putFloat(sourceWidth).putFloat(sourceHeight).putFloat(outputWidth).putFloat(outputHeight);
			push.flip();
			easuRenderer.encodeInto(cmd)
				.setViewport(0, 0, outputWidth, outputHeight)
				.setScissor(0, 0, outputWidth, outputHeight)
				.bindPipeline(easuPipeline)
				.bindBindGroup(0, easuBindGroup)
				.pushConstants(ShaderStage.FRAGMENT, 0, push)
				.draw(3, 1, 0, 0);
		}
	}

	void recordRcas(VkCommandBuffer cmd, int targetWidth, int targetHeight,
		float sharpness)
	{
		if (rcasBindGroup == null)
		{
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			ByteBuffer push = stack.malloc(16);
			push.putFloat(targetWidth).putFloat(targetHeight).putFloat(sharpness).putFloat(0f);
			push.flip();
			rcasRenderer.encodeInto(cmd)
				.setViewport(0, 0, targetWidth, targetHeight)
				.setScissor(0, 0, targetWidth, targetHeight)
				.bindPipeline(rcasPipeline)
				.bindBindGroup(0, rcasBindGroup)
				.pushConstants(ShaderStage.FRAGMENT, 0, push)
				.draw(3, 1, 0, 0);
		}
	}

	void clearBindings()
	{
		if (easuBindGroup != null)
		{
			easuBindGroup.close();
			easuBindGroup = null;
		}
		if (rcasBindGroup != null)
		{
			rcasBindGroup.close();
			rcasBindGroup = null;
		}
		easuBoundView = 0L;
		easuBoundSampler = 0L;
		rcasBoundView = 0L;
		rcasBoundSampler = 0L;
	}

	@Override
	public void close()
	{
		clearBindings();
		rcasPipeline.close();
		easuPipeline.close();
		vertex.close();
		easuFragment.close();
		rcasFragment.close();
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
