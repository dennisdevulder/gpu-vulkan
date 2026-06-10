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
 * Description used to build a {@link ComputePipeline}.
 */
public final class ComputePipelineDesc
{
	private final ShaderModule compute;
	private final List<BindGroupLayout> bindGroupLayouts;
	private final List<RenderPipelineDesc.PushConstantRange> pushConstants;

	private ComputePipelineDesc(Builder b)
	{
		this.compute = b.compute;
		this.bindGroupLayouts = Collections.unmodifiableList(new ArrayList<>(b.bindGroupLayouts));
		this.pushConstants = Collections.unmodifiableList(new ArrayList<>(b.pushConstants));
	}

	public ShaderModule compute() { return compute; }
	public List<BindGroupLayout> bindGroupLayouts() { return bindGroupLayouts; }
	public List<RenderPipelineDesc.PushConstantRange> pushConstants() { return pushConstants; }

	public static Builder builder() { return new Builder(); }

	public static final class Builder
	{
		private ShaderModule compute;
		private final List<BindGroupLayout> bindGroupLayouts = new ArrayList<>();
		private final List<RenderPipelineDesc.PushConstantRange> pushConstants = new ArrayList<>();

		public Builder compute(ShaderModule c) { this.compute = c; return this; }

		public Builder addBindGroupLayout(BindGroupLayout l)
		{
			bindGroupLayouts.add(l);
			return this;
		}

		public Builder addPushConstantRange(int stages, int offset, int size)
		{
			pushConstants.add(new RenderPipelineDesc.PushConstantRange(stages, offset, size));
			return this;
		}

		public ComputePipelineDesc build() { return new ComputePipelineDesc(this); }
	}
}
