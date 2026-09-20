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
package com.gpuvulkan.encoding;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NalRingTest
{
	private static EncodedFrame frame(long ts, boolean idr, int bytes)
	{
		return new EncodedFrame(0, ts, idr, (int) ts, false, new byte[bytes]);
	}

	@Test
	public void keepsFramesInChronologicalOrder()
	{
		NalRing ring = new NalRing(4, 1 << 20);
		for (int i = 0; i < 3; i++)
		{
			ring.put(frame(i, i == 0, 10));
		}
		List<EncodedFrame> out = ring.snapshot();
		assertEquals(3, out.size());
		assertEquals(0L, out.get(0).timestampMs);
		assertEquals(2L, out.get(2).timestampMs);
	}

	@Test
	public void oldestFramesFallOutWhenSlotsWrap()
	{
		NalRing ring = new NalRing(3, 1 << 20);
		for (int i = 0; i < 5; i++)
		{
			ring.put(frame(i, false, 10));
		}
		List<EncodedFrame> out = ring.snapshot();
		assertEquals(3, out.size());
		assertEquals(2L, out.get(0).timestampMs);
		assertEquals(4L, out.get(2).timestampMs);
		assertEquals(30L, ring.bytes());
	}

	@Test
	public void byteBudgetEvictsOldestBeforeSlotsAreFull()
	{
		// 100 slots would hold every frame; the budget is what binds.
		NalRing ring = new NalRing(100, 250);
		for (int i = 0; i < 10; i++)
		{
			ring.put(frame(i, false, 100));
		}
		assertTrue(ring.bytes() <= 250);
		assertEquals(2, ring.size());
		List<EncodedFrame> out = ring.snapshot();
		assertEquals(8L, out.get(0).timestampMs);
		assertEquals(9L, out.get(1).timestampMs);
		assertEquals(8L, ring.droppedToBudget());
	}

	@Test
	public void aFrameLargerThanTheWholeBudgetIsStillKept()
	{
		// Better a one-frame clip than an empty one.
		NalRing ring = new NalRing(10, 100);
		ring.put(frame(0, true, 4096));
		assertEquals(1, ring.size());
		assertEquals(4096L, ring.bytes());
	}

	@Test
	public void loweringTheBudgetEvictsImmediately()
	{
		NalRing ring = new NalRing(100, 1 << 20);
		for (int i = 0; i < 10; i++)
		{
			ring.put(frame(i, false, 100));
		}
		assertEquals(10, ring.size());
		ring.setByteBudget(350);
		assertEquals(3, ring.size());
		assertEquals(9L, ring.snapshot().get(2).timestampMs);
	}

	@Test
	public void byteAccountingSurvivesWrapAndReset()
	{
		NalRing ring = new NalRing(3, 1 << 20);
		for (int i = 0; i < 9; i++)
		{
			ring.put(frame(i, false, 7));
		}
		assertEquals(21L, ring.bytes());
		ring.reset();
		assertEquals(0L, ring.bytes());
		assertEquals(0, ring.size());
		ring.put(frame(99, true, 5));
		assertEquals(5L, ring.bytes());
	}

	@Test
	public void sinkEntryPointGoesThroughTheSameBounds()
	{
		NalRing ring = new NalRing(100, 250);
		NalSink sink = ring;
		sink.segmentStarted(new EncodedSegmentInfo(0, 16, 16, 16, 16, 30, new byte[]{1}));
		for (int i = 0; i < 10; i++)
		{
			sink.frame(frame(i, false, 100));
		}
		assertEquals(2, ring.size());
	}
}
