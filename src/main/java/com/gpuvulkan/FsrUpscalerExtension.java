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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import com.gpuvulkan.gfx.BindGroup;
import com.gpuvulkan.gfx.BindGroupDesc;
import com.gpuvulkan.gfx.BindGroupLayout;
import com.gpuvulkan.gfx.BindGroupLayoutDesc;
import com.gpuvulkan.gfx.RenderDevice;
import com.gpuvulkan.gfx.RenderPipeline;
import com.gpuvulkan.gfx.RenderPipelineDesc;
import com.gpuvulkan.gfx.RenderTarget;
import com.gpuvulkan.gfx.ShaderModule;
import com.gpuvulkan.gfx.ShaderStage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * AMD FSR 1.0 upscaling, implemented entirely against the public extension
 * API — the reference example for {@link ScenePassRedirect}:
 *
 * <ol>
 *   <li>The backend renders the 3D scene into a reduced-resolution
 *       {@link RenderTarget} this extension owns.</li>
 *   <li>{@link #recordAfterScene}: an EASU pass upscales it into a
 *       full-resolution intermediate target.</li>
 *   <li>{@link #recordResolve}: an RCAS sharpening pass draws the result
 *       into the on-screen pass, before the UI composite.</li>
 * </ol>
 */
public final class FsrUpscalerExtension implements VulkanRenderExtension, ScenePassRedirect
{
	private GpuVulkanPluginConfig config;
	private RenderDevice mainDevice;
	private RenderTarget sceneTarget;
	private RenderTarget intermediate;
	private BindGroupLayout bgl;
	private ShaderModule vertex;
	private ShaderModule easuFragment;
	private ShaderModule rcasFragment;
	private RenderPipeline easuPipeline;
	private RenderPipeline rcasPipeline;
	private BindGroup easuBindGroup;
	private BindGroup rcasBindGroup;

	@Override
	public void onRegistered(VulkanRenderContext context)
	{
		config = context.config();
		mainDevice = context.renderer();
		sceneTarget = mainDevice.createRenderTarget(1, 1, context.renderPassSamples());
		intermediate = mainDevice.createRenderTarget(1, 1, 1);

		bgl = mainDevice.createBindGroupLayout(BindGroupLayoutDesc.builder()
			.combinedImageSampler(0, ShaderStage.FRAGMENT)
			.build());
		vertex = mainDevice.createShaderModule(loadResource("ui.vert.spv"));
		easuFragment = mainDevice.createShaderModule(loadResource("fsr1_easu.frag.spv"));
		rcasFragment = mainDevice.createShaderModule(loadResource("fsr1_rcas.frag.spv"));
		easuPipeline = intermediate.device().createRenderPipeline(RenderPipelineDesc.builder()
			.vertex(vertex)
			.fragment(easuFragment)
			.blendMode(RenderPipelineDesc.BlendMode.NONE)
			.depthTest(RenderPipelineDesc.DepthTest.OFF)
			.addBindGroupLayout(bgl)
			.addPushConstantRange(ShaderStage.FRAGMENT, 0, 16)
			.build());
		rcasPipeline = mainDevice.createRenderPipeline(RenderPipelineDesc.builder()
			.vertex(vertex)
			.fragment(rcasFragment)
			.blendMode(RenderPipelineDesc.BlendMode.NONE)
			.depthTest(RenderPipelineDesc.DepthTest.OFF)
			.addBindGroupLayout(bgl)
			.addPushConstantRange(ShaderStage.FRAGMENT, 0, 16)
			.build());
	}

	@Override
	public ScenePassRedirect scenePassRedirect()
	{
		return config.upscalingMode() == GpuVulkanPluginConfig.UpscalingMode.FSR1
			&& config.renderScale() < 100 ? this : null;
	}

	@Override
	public RenderTarget sceneTarget(int targetWidth, int targetHeight)
	{
		int scalePct = Math.max(50, Math.min(100, config.renderScale()));
		int lowWidth = Math.max(1, (targetWidth * scalePct) / 100);
		int lowHeight = Math.max(1, (targetHeight * scalePct) / 100);

		if (sceneTarget.resize(lowWidth, lowHeight) && easuBindGroup != null)
		{
			easuBindGroup.close();
			easuBindGroup = null;
		}
		if (intermediate.resize(targetWidth, targetHeight) && rcasBindGroup != null)
		{
			rcasBindGroup.close();
			rcasBindGroup = null;
		}
		if (easuBindGroup == null)
		{
			easuBindGroup = mainDevice.createBindGroup(BindGroupDesc.builder(bgl)
				.sampledImage(0, sceneTarget)
				.build());
		}
		if (rcasBindGroup == null)
		{
			rcasBindGroup = mainDevice.createBindGroup(BindGroupDesc.builder(bgl)
				.sampledImage(0, intermediate)
				.build());
		}
		return sceneTarget;
	}

	@Override
	public void recordAfterScene(VkCommandBuffer cmd)
	{
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			ByteBuffer push = stack.malloc(16);
			push.putFloat(sceneTarget.width()).putFloat(sceneTarget.height())
				.putFloat(intermediate.width()).putFloat(intermediate.height());
			push.flip();
			mainDevice.encodeInto(cmd)
				.prepareForSampling(sceneTarget)
				.beginPass(intermediate, 0f, 0f, 0f, 1f)
				.bindPipeline(easuPipeline)
				.bindBindGroup(0, easuBindGroup)
				.pushConstants(ShaderStage.FRAGMENT, 0, push)
				.draw(3, 1, 0, 0)
				.endPass()
				.prepareForSampling(intermediate);
		}
	}

	@Override
	public void recordResolve(VulkanFrameContext frame)
	{
		float sharpness = Math.max(0, Math.min(100, config.fsrSharpness())) / 100f;
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			ByteBuffer push = stack.malloc(16);
			push.putFloat(frame.targetWidth()).putFloat(frame.targetHeight())
				.putFloat(sharpness).putFloat(0f);
			push.flip();
			mainDevice.encodeInto(frame.commandBuffer())
				.setViewport(0, 0, frame.targetWidth(), frame.targetHeight())
				.setScissor(0, 0, frame.targetWidth(), frame.targetHeight())
				.bindPipeline(rcasPipeline)
				.bindBindGroup(0, rcasBindGroup)
				.pushConstants(ShaderStage.FRAGMENT, 0, push)
				.draw(3, 1, 0, 0);
		}
	}

	@Override
	public void close()
	{
		if (easuBindGroup != null) { easuBindGroup.close(); easuBindGroup = null; }
		if (rcasBindGroup != null) { rcasBindGroup.close(); rcasBindGroup = null; }
		if (rcasPipeline != null) { rcasPipeline.close(); rcasPipeline = null; }
		if (easuPipeline != null) { easuPipeline.close(); easuPipeline = null; }
		if (vertex != null) { vertex.close(); vertex = null; }
		if (easuFragment != null) { easuFragment.close(); easuFragment = null; }
		if (rcasFragment != null) { rcasFragment.close(); rcasFragment = null; }
		if (bgl != null) { bgl.close(); bgl = null; }
		if (intermediate != null) { intermediate.close(); intermediate = null; }
		if (sceneTarget != null) { sceneTarget.close(); sceneTarget = null; }
	}

	private static byte[] loadResource(String resource)
	{
		try (InputStream in = FsrUpscalerExtension.class.getResourceAsStream(resource))
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
