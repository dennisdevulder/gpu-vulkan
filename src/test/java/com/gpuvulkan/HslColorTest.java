/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class HslColorTest
{
	@Test
	public void modelAndSceneOverridesAreSequential()
	{
		int original = (42 << 10) | (5 << 7) | 76;
		int modelTint = HslColor.applyOverride(original,
			(byte) 12, (byte) 3, (byte) 48, (byte) 64);
		int expected = HslColor.applyOverride(modelTint,
			(byte) 2, (byte) 7, (byte) 20, (byte) 96);
		int replacementOnly = HslColor.applyOverride(original,
			(byte) 2, (byte) 7, (byte) 20, (byte) 96);

		assertEquals((8 << 10) | (6 << 7) | 30, expected);
		assertNotEquals(replacementOnly, expected);
	}
}
