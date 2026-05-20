package net.runelite.client.plugins.gpuvulkan.gfx;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Non-owning rendering-device facade exposed to extensions.
 *
 * <p>Resources returned by {@code create*} are owned by the caller and must be
 * closed by the caller. The backend-owned device facade itself is borrowed and
 * intentionally has no {@code close()} method.
 */
public interface RenderDevice
{
	ShaderModule createShaderModule(byte[] spirv);

	BindGroupLayout createBindGroupLayout(BindGroupLayoutDesc desc);

	BindGroup createBindGroup(BindGroupDesc desc);

	RenderPipeline createRenderPipeline(RenderPipelineDesc desc);

	StreamingImage createStreamingImage(int width, int height);

	int currentSlot();

	RenderEncoder encodeInto(VkCommandBuffer cmd);
}
