/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The audio ring is what lets a clip reach backwards for sound. Without it a
 * recording's pre-roll would be silent while its video was not.
 */
public class AudioRingTest
{
	private static byte[] block(int size, byte fill)
	{
		byte[] b = new byte[size];
		java.util.Arrays.fill(b, fill);
		return b;
	}

	@Test
	public void windowReturnsOnlyWhatFallsInTheRange()
	{
		AudioRing ring = new AudioRing(1 << 20);
		for (int i = 0; i < 10; i++)
		{
			ring.put(i * 100L, block(4, (byte) i), 4);
		}
		// Inclusive at both ends: 300..600 is four blocks.
		assertEquals(16, ring.window(300, 600).length);
		assertEquals(0, ring.window(5000, 6000).length);
	}

	@Test
	public void drainIsExclusiveOfTheCursorSoNothingIsWrittenTwice()
	{
		AudioRing ring = new AudioRing(1 << 20);
		ring.put(100, block(4, (byte) 1), 4);
		ring.put(200, block(4, (byte) 2), 4);
		ring.put(300, block(4, (byte) 3), 4);

		List<AudioRing.Block> first = ring.drain(99, 200);
		assertEquals(2, first.size());
		List<AudioRing.Block> second = ring.drain(200, 300);
		assertEquals(1, second.size());
		assertEquals(300L, second.get(0).timestampMs);
	}

	@Test
	public void theBudgetDropsOldestAudioFirst()
	{
		AudioRing ring = new AudioRing(40);
		for (int i = 0; i < 20; i++)
		{
			ring.put(i * 10L, block(10, (byte) i), 10);
		}
		assertTrue(ring.bytes() <= 40);
		// Only recent audio survives, matching how the NAL ring behaves.
		assertEquals(0, ring.window(0, 100).length);
		assertTrue(ring.window(150, 200).length > 0);
	}

	@Test
	public void loweringTheBudgetTrimsImmediately()
	{
		AudioRing ring = new AudioRing(1 << 20);
		for (int i = 0; i < 10; i++)
		{
			ring.put(i * 10L, block(100, (byte) i), 100);
		}
		assertEquals(1000L, ring.bytes());
		ring.setByteBudget(250);
		assertTrue(ring.bytes() <= 250);
	}

	@Test
	public void storedBlocksAreCopiedSoTheCaptureBufferCanBeReused()
	{
		AudioRing ring = new AudioRing(1 << 20);
		byte[] shared = block(4, (byte) 7);
		ring.put(100, shared, 4);
		java.util.Arrays.fill(shared, (byte) 0);
		assertEquals(7, ring.window(100, 100)[0]);
	}
}
