/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan;

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
