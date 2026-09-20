/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.gfx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Description used to build a concrete {@link BindGroup} against a
 * {@link BindGroupLayout}. The resources bound here must match the layout's
 * declared bindings.
 */
public final class BindGroupDesc
{
	public static final class StreamingImageEntry
	{
		public final int binding;
		public final StreamingImage image;

		StreamingImageEntry(int binding, StreamingImage image)
		{
			this.binding = binding;
			this.image = image;
		}
	}

	public static final class SampledImageEntry
	{
		public final int binding;
		public final long imageView;
		public final long sampler;

		SampledImageEntry(int binding, long imageView, long sampler)
		{
			this.binding = binding;
			this.imageView = imageView;
			this.sampler = sampler;
		}
	}

	public static final class RenderTargetEntry
	{
		public final int binding;
		public final RenderTarget target;

		RenderTargetEntry(int binding, RenderTarget target)
		{
			this.binding = binding;
			this.target = target;
		}
	}

	public static final class BufferEntry
	{
		public final int binding;
		public final GpuBuffer buffer;
		public final long offset;
		public final long range;

		BufferEntry(int binding, GpuBuffer buffer, long offset, long range)
		{
			this.binding = binding;
			this.buffer = buffer;
			this.offset = offset;
			this.range = range;
		}
	}

	private final BindGroupLayout layout;
	private final List<StreamingImageEntry> streamingImages;
	private final List<SampledImageEntry> sampledImages;
	private final List<RenderTargetEntry> renderTargets;
	private final List<BufferEntry> buffers;

	private BindGroupDesc(BindGroupLayout layout, List<StreamingImageEntry> streamingImages,
		List<SampledImageEntry> sampledImages, List<RenderTargetEntry> renderTargets,
		List<BufferEntry> buffers)
	{
		this.layout = layout;
		this.streamingImages = Collections.unmodifiableList(streamingImages);
		this.sampledImages = Collections.unmodifiableList(sampledImages);
		this.renderTargets = Collections.unmodifiableList(renderTargets);
		this.buffers = Collections.unmodifiableList(buffers);
	}

	public BindGroupLayout layout() { return layout; }
	public List<StreamingImageEntry> streamingImages() { return streamingImages; }
	public List<SampledImageEntry> sampledImages() { return sampledImages; }
	public List<RenderTargetEntry> renderTargets() { return renderTargets; }
	public List<BufferEntry> buffers() { return buffers; }

	public static Builder builder(BindGroupLayout layout) { return new Builder(layout); }

	public static final class Builder
	{
		private final BindGroupLayout layout;
		private final List<StreamingImageEntry> streamingImages = new ArrayList<>();
		private final List<SampledImageEntry> sampledImages = new ArrayList<>();
		private final List<RenderTargetEntry> renderTargets = new ArrayList<>();
		private final List<BufferEntry> buffers = new ArrayList<>();

		Builder(BindGroupLayout layout) { this.layout = layout; }

		public Builder streamingImage(int binding, StreamingImage image)
		{
			streamingImages.add(new StreamingImageEntry(binding, image));
			return this;
		}

		public Builder sampledImage(int binding, long imageView, long sampler)
		{
			sampledImages.add(new SampledImageEntry(binding, imageView, sampler));
			return this;
		}

		/** Binds the target's color image as a combined image sampler. */
		public Builder sampledImage(int binding, RenderTarget target)
		{
			renderTargets.add(new RenderTargetEntry(binding, target));
			return this;
		}

		/** Binds the buffer's full range as a uniform buffer. */
		public Builder uniformBuffer(int binding, GpuBuffer buffer)
		{
			buffers.add(new BufferEntry(binding, buffer, 0, buffer.size()));
			return this;
		}

		/** Binds the buffer's full range as a storage buffer. */
		public Builder storageBuffer(int binding, GpuBuffer buffer)
		{
			buffers.add(new BufferEntry(binding, buffer, 0, buffer.size()));
			return this;
		}

		public BindGroupDesc build()
		{
			return new BindGroupDesc(layout, new ArrayList<>(streamingImages),
				new ArrayList<>(sampledImages), new ArrayList<>(renderTargets),
				new ArrayList<>(buffers));
		}
	}
}
