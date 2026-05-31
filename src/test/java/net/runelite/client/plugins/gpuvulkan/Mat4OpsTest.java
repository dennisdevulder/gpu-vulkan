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
package net.runelite.client.plugins.gpuvulkan;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class Mat4OpsTest
{
	private static final float EPSILON = 0.000001f;

	@Test
	public void projectionMatchesExpectedLayout()
	{
		assertArrayEquals(new float[]
			{
				0.01f, 0f, 0f, 0f,
				0f, -0.02f, 0f, 0f,
				0f, 0f, 0f, 1f,
				0f, 0f, 100f, 0f
			}, Mat4Ops.projection(200f, 100f, 50f), EPSILON);
	}

	@Test
	public void multiplyAppliesRightHandTransform()
	{
		float[] m = Mat4Ops.scale(2f, 3f, 4f);
		Mat4Ops.mul(m, Mat4Ops.translate(5f, 6f, 7f));

		assertArrayEquals(new float[]
			{
				2f, 0f, 0f, 0f,
				0f, 3f, 0f, 0f,
				0f, 0f, 4f, 0f,
				10f, 18f, 28f, 1f
			}, m, EPSILON);
	}

	@Test
	public void writeToWritesNativeOrderFloats()
	{
		float[] m = Mat4Ops.identity();
		ByteBuffer out = ByteBuffer.allocate(16 * Float.BYTES).order(ByteOrder.nativeOrder());

		Mat4Ops.writeTo(out, m);
		out.flip();

		for (int i = 0; i < 16; i++)
		{
			assertEquals(m[i], out.getFloat(), EPSILON);
		}
	}
}
