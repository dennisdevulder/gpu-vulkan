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

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Mp4WriterTest
{
	private static byte[] annexB(byte[]... nals)
	{
		int size = 0;
		for (byte[] nal : nals)
		{
			size += 4 + nal.length;
		}
		ByteBuffer buf = ByteBuffer.allocate(size);
		for (byte[] nal : nals)
		{
			buf.put(new byte[]{0, 0, 0, 1}).put(nal);
		}
		return buf.array();
	}

	@Test
	public void writesParseableBoxStructure() throws IOException
	{
		// Fake SPS (type 7) / PPS (type 8) / IDR slice (type 5) payloads.
		byte[] sps = {0x67, 0x4D, 0x40, 0x28, (byte) 0x84};
		byte[] pps = {0x68, (byte) 0xCE, 0x38, (byte) 0x80};
		byte[] header = annexB(sps, pps);

		List<Mp4Writer.Sample> samples = new ArrayList<>();
		long t0 = 1_000_000_000L;
		int[] sliceSizes = {500, 700, 600};
		for (int i = 0; i < sliceSizes.length; i++)
		{
			byte[] slice = new byte[sliceSizes[i]];
			slice[0] = 0x65; // IDR NAL
			samples.add(new Mp4Writer.Sample(annexB(slice), t0 + i * 33_333_000L));
		}

		File file = File.createTempFile("mp4writer", ".mp4");
		file.deleteOnExit();
		Mp4Writer.write(file, header, samples, 1920, 1080);
		byte[] bytes = Files.readAllBytes(file.toPath());

		// Top-level boxes must tile the file exactly.
		Map<String, Integer> boxes = new HashMap<>();
		int pos = 0;
		long mdatPayload = 0;
		while (pos < bytes.length)
		{
			ByteBuffer buf = ByteBuffer.wrap(bytes, pos, 8);
			int size = buf.getInt();
			String type = new String(bytes, pos + 4, 4, StandardCharsets.US_ASCII);
			assertTrue("box size positive: " + type, size >= 8);
			assertTrue("box fits file: " + type, pos + size <= bytes.length);
			boxes.put(type, size);
			if ("mdat".equals(type))
			{
				mdatPayload = size - 8;
			}
			pos += size;
		}
		assertEquals(bytes.length, pos);
		assertTrue(boxes.containsKey("ftyp"));
		assertTrue(boxes.containsKey("mdat"));
		assertTrue(boxes.containsKey("moov"));

		// mdat payload = samples in AVCC form (4-byte length per NAL).
		long expected = 0;
		for (int size : sliceSizes)
		{
			expected += 4 + size;
		}
		assertEquals(expected, mdatPayload);

		// avcC must carry the SPS/PPS verbatim.
		assertTrue(indexOf(bytes, sps) >= 0);
		assertTrue(indexOf(bytes, pps) >= 0);
	}

	private static int indexOf(byte[] haystack, byte[] needle)
	{
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++)
		{
			for (int j = 0; j < needle.length; j++)
			{
				if (haystack[i + j] != needle[j])
				{
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
