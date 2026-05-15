package net.runelite.client.plugins.gpuvulkan.gfx;

/**
 * Describes the *shape* of a {@link BindGroup} — the bindings (combined
 * image samplers, uniform buffers, storage buffers) and which shader
 * stages can read them. The matching pipeline declares the same layout
 * so the GPU knows what to expect at each set index. Equivalent to
 * Vulkan's {@code VkDescriptorSetLayout}; WebGPU's
 * {@code GPUBindGroupLayout} carries the same meaning.
 *
 * <p>Built once at init time; reused for as many {@link BindGroup}s and
 * pipelines as need that shape.
 */
public interface BindGroupLayout extends AutoCloseable
{
	@Override
	void close();
}
