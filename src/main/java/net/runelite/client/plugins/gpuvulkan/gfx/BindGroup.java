package net.runelite.client.plugins.gpuvulkan.gfx;

/**
 * A concrete set of resources bound to a {@link BindGroupLayout}. Used at
 * draw time via {@link RenderEncoder#bindBindGroup(int, BindGroup)}.
 * Equivalent to a Vulkan {@code VkDescriptorSet}.
 *
 * <p>For streaming resources (e.g. {@link StreamingImage} whose underlying
 * texture cycles per frame) the consumer creates a single {@code BindGroup}
 * containing the streaming image and rebinds the same {@code BindGroup}
 * each frame — the layer dispatches to the correct per-slot Vulkan
 * descriptor set internally.
 */
public interface BindGroup extends AutoCloseable
{
	@Override
	void close();
}
