package net.runelite.client.plugins.gpuvulkan.gfx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Description used to build a {@link BindGroupLayout}. Construct via
 * {@link #builder()}, list the bindings, hand to
 * {@link Renderer#createBindGroupLayout(BindGroupLayoutDesc)}.
 *
 * <p>Today's binding kinds are limited to what the migrated consumers need
 * (combined image samplers). Add new {@link BindingKind}s as future
 * pipelines demand them.
 */
public final class BindGroupLayoutDesc
{
	public enum BindingKind
	{
		COMBINED_IMAGE_SAMPLER
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

		public BindGroupLayoutDesc build()
		{
			return new BindGroupLayoutDesc(new ArrayList<>(entries));
		}
	}
}
