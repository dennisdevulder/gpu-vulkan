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
 * Description used to build a concrete {@link BindGroup} against a
 * {@link BindGroupLayout}. The resources bound here must match the layout's
 * declared bindings — currently only {@link StreamingImage}; other resource
 * kinds (uniform/storage buffers, sampled images) will be added as
 * consumers need them.
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

	private final BindGroupLayout layout;
	private final List<StreamingImageEntry> streamingImages;

	private BindGroupDesc(BindGroupLayout layout, List<StreamingImageEntry> streamingImages)
	{
		this.layout = layout;
		this.streamingImages = Collections.unmodifiableList(streamingImages);
	}

	public BindGroupLayout layout() { return layout; }
	public List<StreamingImageEntry> streamingImages() { return streamingImages; }

	public static Builder builder(BindGroupLayout layout) { return new Builder(layout); }

	public static final class Builder
	{
		private final BindGroupLayout layout;
		private final List<StreamingImageEntry> streamingImages = new ArrayList<>();

		Builder(BindGroupLayout layout) { this.layout = layout; }

		public Builder streamingImage(int binding, StreamingImage image)
		{
			streamingImages.add(new StreamingImageEntry(binding, image));
			return this;
		}

		public BindGroupDesc build()
		{
			return new BindGroupDesc(layout, new ArrayList<>(streamingImages));
		}
	}
}
