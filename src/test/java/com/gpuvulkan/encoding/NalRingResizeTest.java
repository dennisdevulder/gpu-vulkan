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

/**
 * Growing the buffer must not throw away what it already holds: a clip that
 * asks for a longer tail than the configured default grows the ring at trigger
 * time, and the pre-roll it is about to save is exactly the content at risk.
 */
public class NalRingResizeTest
{
	@Test
	public void growingKeepsBufferedFrames()
	{
		NalRing ring = new NalRing(4, 1 << 20);
		for (int i = 0; i < 4; i++)
		{
			ring.put(new EncodedFrame(0, i, i == 0, i, false, new byte[10]));
		}

		NalRing grown = ring.resized(8);

		List<EncodedFrame> out = grown.snapshot();
		assertEquals(4, out.size());
		assertEquals(0L, out.get(0).timestampMs);
		assertEquals(3L, out.get(3).timestampMs);
		assertEquals(8, grown.capacity());
		assertEquals(40L, grown.bytes());
	}

	@Test
	public void shrinkingKeepsTheNewestFramesThatStillFit()
	{
		NalRing ring = new NalRing(8, 1 << 20);
		for (int i = 0; i < 8; i++)
		{
			ring.put(new EncodedFrame(0, i, false, i, false, new byte[10]));
		}

		List<EncodedFrame> out = ring.resized(3).snapshot();
		assertEquals(3, out.size());
		assertEquals(5L, out.get(0).timestampMs);
		assertEquals(7L, out.get(2).timestampMs);
	}

	@Test
	public void resizeCarriesTheByteBudgetOver()
	{
		NalRing ring = new NalRing(4, 250);
		for (int i = 0; i < 4; i++)
		{
			ring.put(new EncodedFrame(0, i, false, i, false, new byte[100]));
		}
		assertEquals(2, ring.size());

		NalRing grown = ring.resized(100);
		assertEquals(250L, grown.byteBudget());
		assertEquals(2, grown.size());
	}
}
