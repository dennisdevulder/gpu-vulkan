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
