package net.runelite.client.plugins.gpuvulkan.gfx;

/**
 * A compiled SPIR-V shader. Created via {@link Renderer#createShaderModule}.
 * Lifetime is tied to the calling consumer; {@code close()} destroys the
 * underlying {@code VkShaderModule}. Stage (vertex / fragment / compute) is
 * declared at pipeline-build time, not on the module itself, so the same
 * SPIR-V can be reused across stages where that makes sense.
 */
public interface ShaderModule extends AutoCloseable
{
	@Override
	void close();
}
