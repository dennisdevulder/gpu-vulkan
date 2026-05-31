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
package net.runelite.client.plugins.gpuvulkan.gfx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Description used to build a {@link RenderPipeline}. Only the subset of
 * Vulkan pipeline state actually used by the migrated consumers is exposed
 * here — vertex layout, shader stages, depth/blend state, target attachment
 * info. Add more knobs as future consumers need them; don't expose every
 * Vulkan toggle preemptively.
 */
public final class RenderPipelineDesc
{
	public enum Topology { TRIANGLE_LIST }

	public enum BlendMode
	{
		/** Opaque — no blending, last write wins. */
		NONE,
		/** Premultiplied alpha — {@code (src.rgb, src.a) over dst}. UI default. */
		PREMUL_ALPHA
	}

	public enum DepthTest
	{
		/** Disabled. UI quads typically. */
		OFF,
		/** Reverse-Z: closer = larger NDC.z, depthCompareOp = GREATER. Scene default. */
		REVERSE_Z
	}

	private final ShaderModule vertex;
	private final ShaderModule fragment;
	private final Topology topology;
	private final BlendMode blendMode;
	private final DepthTest depthTest;
	private final List<BindGroupLayout> bindGroupLayouts;
	private final List<PushConstantRange> pushConstants;
	private final boolean useSwapchainRenderPass;

	private RenderPipelineDesc(Builder b)
	{
		this.vertex = b.vertex;
		this.fragment = b.fragment;
		this.topology = b.topology;
		this.blendMode = b.blendMode;
		this.depthTest = b.depthTest;
		this.bindGroupLayouts = Collections.unmodifiableList(new ArrayList<>(b.bindGroupLayouts));
		this.pushConstants = Collections.unmodifiableList(new ArrayList<>(b.pushConstants));
		this.useSwapchainRenderPass = b.useSwapchainRenderPass;
	}

	public ShaderModule vertex() { return vertex; }
	public ShaderModule fragment() { return fragment; }
	public Topology topology() { return topology; }
	public BlendMode blendMode() { return blendMode; }
	public DepthTest depthTest() { return depthTest; }
	public List<BindGroupLayout> bindGroupLayouts() { return bindGroupLayouts; }
	public List<PushConstantRange> pushConstants() { return pushConstants; }
	public boolean useSwapchainRenderPass() { return useSwapchainRenderPass; }

	public static final class PushConstantRange
	{
		public final int stages;
		public final int offset;
		public final int size;

		public PushConstantRange(int stages, int offset, int size)
		{
			this.stages = stages;
			this.offset = offset;
			this.size = size;
		}
	}

	public static Builder builder() { return new Builder(); }

	public static final class Builder
	{
		private ShaderModule vertex;
		private ShaderModule fragment;
		private Topology topology = Topology.TRIANGLE_LIST;
		private BlendMode blendMode = BlendMode.NONE;
		private DepthTest depthTest = DepthTest.OFF;
		private final List<BindGroupLayout> bindGroupLayouts = new ArrayList<>();
		private final List<PushConstantRange> pushConstants = new ArrayList<>();
		private boolean useSwapchainRenderPass = true;

		public Builder vertex(ShaderModule v) { this.vertex = v; return this; }
		public Builder fragment(ShaderModule f) { this.fragment = f; return this; }
		public Builder topology(Topology t) { this.topology = t; return this; }
		public Builder blendMode(BlendMode m) { this.blendMode = m; return this; }
		public Builder depthTest(DepthTest d) { this.depthTest = d; return this; }

		public Builder addBindGroupLayout(BindGroupLayout l)
		{
			bindGroupLayouts.add(l);
			return this;
		}

		public Builder addPushConstantRange(int stages, int offset, int size)
		{
			pushConstants.add(new PushConstantRange(stages, offset, size));
			return this;
		}

		/** True (default) = pipeline targets the swapchain's main render pass.
		 *  Today that's the only render pass the layer manages, so changing
		 *  this is a no-op; included to keep the API honest about coupling. */
		public Builder useSwapchainRenderPass(boolean on)
		{
			this.useSwapchainRenderPass = on;
			return this;
		}

		public RenderPipelineDesc build() { return new RenderPipelineDesc(this); }
	}
}
