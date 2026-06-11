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
package com.gpuvulkan.gfx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Description used to build a {@link BindGroupLayout}.
 */
public final class BindGroupLayoutDesc
{
	public enum BindingKind
	{
		COMBINED_IMAGE_SAMPLER,
		UNIFORM_BUFFER,
		STORAGE_BUFFER
	}

	public static final class Entry
	{
		public final int binding;
		public final BindingKind kind;
		public final int stages;

		Entry(int binding, BindingKind kind, int stages)
		{
			this.binding = binding;
			this.kind = kind;
			this.stages = stages;
		}
	}

	private final List<Entry> entries;

	private BindGroupLayoutDesc(List<Entry> entries)
	{
		this.entries = Collections.unmodifiableList(entries);
	}

	public List<Entry> entries() { return entries; }

	public static Builder builder() { return new Builder(); }

	public static final class Builder
	{
		private final List<Entry> entries = new ArrayList<>();

		/** Declares a combined image+sampler at {@code binding} visible to
		 *  the given {@code stages} (use {@link ShaderStage} constants). */
		public Builder combinedImageSampler(int binding, int stages)
		{
			entries.add(new Entry(binding, BindingKind.COMBINED_IMAGE_SAMPLER, stages));
			return this;
		}

		public Builder uniformBuffer(int binding, int stages)
		{
			entries.add(new Entry(binding, BindingKind.UNIFORM_BUFFER, stages));
			return this;
		}

		public Builder storageBuffer(int binding, int stages)
		{
			entries.add(new Entry(binding, BindingKind.STORAGE_BUFFER, stages));
			return this;
		}

		public BindGroupLayoutDesc build()
		{
			return new BindGroupLayoutDesc(new ArrayList<>(entries));
		}
	}
}
