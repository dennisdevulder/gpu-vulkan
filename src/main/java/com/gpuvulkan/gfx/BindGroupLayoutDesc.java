/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
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
