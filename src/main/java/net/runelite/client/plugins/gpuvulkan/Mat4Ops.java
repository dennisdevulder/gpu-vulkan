package net.runelite.client.plugins.gpuvulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Helper for pushing a float[16] mat4 (column-major) into a Vulkan push-constant
 * buffer. The matrix construction itself uses {@link net.runelite.client.plugins.gpu.Mat4}
 * — same code path stock GpuPlugin uses, so a typo in our copy can't drift from it.
 */
final class Mat4Ops
{
	private Mat4Ops() {}

	/** Writes the 16 floats into a ByteBuffer at its current position, advancing by 64 bytes. */
	static void writeTo(ByteBuffer dst, float[] m)
	{
		assert dst.order() == ByteOrder.nativeOrder();
		for (int i = 0; i < 16; i++)
		{
			dst.putFloat(m[i]);
		}
	}
}
