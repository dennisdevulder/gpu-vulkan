package net.runelite.client.plugins.gpuvulkan.gfx;

import static org.lwjgl.vulkan.VK13.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK13.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK13.VK_SHADER_STAGE_VERTEX_BIT;

/**
 * Shader stages a binding or push-constant range can be visible in. Combine
 * with {@code |} (the underlying values are Vulkan {@code VK_SHADER_STAGE_*}
 * bitmasks). Naming chosen to read at call sites:
 * {@code ShaderStage.VERTEX | ShaderStage.FRAGMENT}.
 */
public final class ShaderStage
{
	public static final int VERTEX = VK_SHADER_STAGE_VERTEX_BIT;
	public static final int FRAGMENT = VK_SHADER_STAGE_FRAGMENT_BIT;
	public static final int COMPUTE = VK_SHADER_STAGE_COMPUTE_BIT;

	private ShaderStage() {}
}
