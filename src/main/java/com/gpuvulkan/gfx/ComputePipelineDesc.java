/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

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
