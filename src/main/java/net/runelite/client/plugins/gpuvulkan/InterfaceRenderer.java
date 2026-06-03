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
package net.runelite.client.plugins.gpuvulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroup;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupDesc;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupLayout;
import net.runelite.client.plugins.gpuvulkan.gfx.BindGroupLayoutDesc;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderPipeline;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderPipelineDesc;
import net.runelite.client.plugins.gpuvulkan.gfx.RenderDevice;
import net.runelite.client.plugins.gpuvulkan.gfx.ShaderModule;
import net.runelite.client.plugins.gpuvulkan.gfx.ShaderStage;
import net.runelite.client.plugins.gpuvulkan.gfx.StreamingImage;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * UI overlay renderer. Owns a {@link StreamingImage} that rings textures
 * across FRAMES_IN_FLIGHT slots, one bind group pointed at it, and a
 * single pipeline. Entry points: {@code uploadPixels} (host-side memcpy),
 * {@code recordCopyToImage} (transfer), {@code recordDraw} (fullscreen
 * quad).
 */
final class InterfaceRenderer implements AutoCloseable
{
	private final RenderDevice renderer;
	private final BindGroupLayout bgl;
	private final ShaderModule vertex;
	private final ShaderModule fragment;
	private final RenderPipeline pipeline;

	private GfxStreamingImage uiImage;
	private BindGroup bindGroup;
	private int width;
	private int height;

	InterfaceRenderer(RenderDevice renderer)
	{
		this.renderer = renderer;
		this.bgl = renderer.createBindGroupLayout(BindGroupLayoutDesc.builder()
			.combinedImageSampler(0, ShaderStage.FRAGMENT)
			.build());
		this.vertex = renderer.createShaderModule(loadResource("ui.vert.spv"));
		this.fragment = renderer.createShaderModule(loadResource("ui.frag.spv"));
		// Premultiplied alpha over-blend, no depth test; gl_VertexIndex
		// drives a single fullscreen triangle from ui.vert.
		this.pipeline = renderer.createRenderPipeline(RenderPipelineDesc.builder()
			.vertex(vertex)
			.fragment(fragment)
			.blendMode(RenderPipelineDesc.BlendMode.PREMUL_ALPHA)
			.depthTest(RenderPipelineDesc.DepthTest.OFF)
			.addBindGroupLayout(bgl)
			.addPushConstantRange(ShaderStage.FRAGMENT, 0, 32)
			.build());
	}

	void uploadPixels(int[] pixels, int width, int height)
	{
		ensureStreamingImage(width, height);
		uiImage.uploadPixels(pixels);
	}

	void recordCopyToImage(VkCommandBuffer cmd)
	{
		if (uiImage != null)
		{
			uiImage.recordCopyToImage(cmd);
		}
	}

	void recordDraw(VulkanFrameContext frame)
	{
		if (bindGroup == null) return;
		try (MemoryStack stack = MemoryStack.stackPush())
		{
			int targetWidth = frame.targetWidth();
			int targetHeight = frame.targetHeight();
			VkCommandBuffer cmd = frame.commandBuffer();
			int overlayColor = frame.overlayColor();
			// Engine ARGB → shader vec4 (rgb tint, a blend factor).
			// overlayColor == 0 → all-zero push → ui.frag mix is a no-op.
			float a = ((overlayColor >>> 24) & 0xFF) / 255f;
			float r = ((overlayColor >>> 16) & 0xFF) / 255f;
			float g = ((overlayColor >>>  8) & 0xFF) / 255f;
			float b = ( overlayColor         & 0xFF) / 255f;
			ByteBuffer push = stack.malloc(32);
			push.putFloat(r).putFloat(g).putFloat(b).putFloat(a);
			push.putFloat((float) frame.colorBlindMode());
			push.putFloat(frame.colorBlindIntensity());
			push.putFloat(0f).putFloat(0f);
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
		if (bindGroup != null) { bindGroup.close(); bindGroup = null; }
		if (uiImage != null) { uiImage.close(); uiImage = null; }
		pipeline.close();
		vertex.close();
		fragment.close();
		bgl.close();
	}

	private void ensureStreamingImage(int w, int h)
	{
		if (uiImage != null && this.width == w && this.height == h)
		{
			return;
		}
		// Bind group has the texture handles baked in, so it can't survive
		// streaming-image teardown — recreate both on resize.
		if (bindGroup != null)
		{
			bindGroup.close();
			bindGroup = null;
		}
		if (uiImage != null)
		{
			uiImage.close();
			uiImage = null;
		}

		this.width = w;
		this.height = h;
		uiImage = (GfxStreamingImage) renderer.createStreamingImage(w, h);
		bindGroup = renderer.createBindGroup(BindGroupDesc.builder(bgl)
			.streamingImage(0, uiImage)
			.build());
	}

	private static byte[] loadResource(String resource)
	{
		try (InputStream in = InterfaceRenderer.class.getResourceAsStream(resource))
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
