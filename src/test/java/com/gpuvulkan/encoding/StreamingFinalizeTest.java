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

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers the frame-selection logic finalize and pre-roll replay share. No
 * Vulkan device is involved; these are the pure static helpers.
 */
public class StreamingFinalizeTest
{
	private static EncodedFrame frame(int segment, long ts, boolean idr)
	{
		return new EncodedFrame(segment, ts, idr, (int) ts, false, new byte[]{(byte) ts});
	}

	private static List<EncodedFrame> frames(EncodedFrame... f)
	{
		List<EncodedFrame> out = new ArrayList<>();
		for (EncodedFrame e : f)
		{
			out.add(e);
		}
		return out;
	}

	@Test
	public void picksTheLatestSegmentOverlappingTheWindow()
	{
		List<EncodedFrame> all = frames(
			frame(0, 100, true), frame(0, 200, false),
			frame(1, 300, true), frame(1, 400, false));
		assertEquals(1, StreamingVulkanEncoder.pickLatestSegment(all, 150, 500));
		assertEquals(0, StreamingVulkanEncoder.pickLatestSegment(all, 100, 250));
		assertEquals(-1, StreamingVulkanEncoder.pickLatestSegment(all, 900, 1000));
	}

	@Test
	public void startsAtTheLatestIdrAtOrBeforeTheWindow()
	{
		List<EncodedFrame> all = frames(
			frame(0, 100, true), frame(0, 150, false),
			frame(0, 200, true), frame(0, 250, false),
			frame(0, 300, false));
		// Pre-roll starts at 220: the IDR at 200 is the one that decodes it.
		assertEquals(2, StreamingVulkanEncoder.findIdrIndex(all, 0, 220, 400));
	}

	@Test
	public void fallsForwardWhenNoIdrPrecedesTheWindow()
	{
		List<EncodedFrame> all = frames(
			frame(0, 100, false), frame(0, 200, true), frame(0, 300, false));
		assertEquals(1, StreamingVulkanEncoder.findIdrIndex(all, 0, 50, 400));
	}

	@Test
	public void reportsNoIdrRatherThanReturningUndecodableFrames()
	{
		List<EncodedFrame> all = frames(frame(0, 100, false), frame(0, 200, false));
		assertEquals(-1, StreamingVulkanEncoder.findIdrIndex(all, 0, 50, 400));
	}

	@Test
	public void collectionStopsAtASegmentChange()
	{
		List<EncodedFrame> all = frames(
			frame(0, 100, true), frame(0, 200, false),
			frame(1, 300, true));
		List<EncodedFrame> chosen = StreamingVulkanEncoder.collectFromIdr(all, 0, 0, Long.MAX_VALUE);
		assertEquals(2, chosen.size());
		assertTrue(chosen.stream().allMatch(f -> f.segmentId == 0));
	}

	@Test
	public void collectionStopsAtTheEndOfTheWindow()
	{
		List<EncodedFrame> all = frames(
			frame(0, 100, true), frame(0, 200, false), frame(0, 300, false));
		assertEquals(2, StreamingVulkanEncoder.collectFromIdr(all, 0, 0, 250).size());
	}
}
