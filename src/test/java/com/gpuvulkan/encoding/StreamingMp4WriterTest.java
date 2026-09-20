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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StreamingMp4WriterTest
{
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	/** Mirrors the session path: open the file, then supply parameters. */
	private static StreamingMp4Writer open(Path path) throws IOException
	{
		StreamingMp4Writer writer = new StreamingMp4Writer(path);
		writer.segment(info());
		return writer;
	}

	private static final byte[] SPS = {0x67, 0x42, (byte) 0xC0, 0x1E, 0x11, 0x22};
	private static final byte[] PPS = {0x68, (byte) 0xCE, 0x3C, (byte) 0x80};

	private static EncodedSegmentInfo info()
	{
		ByteArrayOutputStream blob = new ByteArrayOutputStream();
		writeStartCode(blob);
		blob.write(SPS, 0, SPS.length);
		writeStartCode(blob);
		blob.write(PPS, 0, PPS.length);
		return new EncodedSegmentInfo(0, 1920, 1080, 1920, 1088, 30, blob.toByteArray());
	}

	private static void writeStartCode(ByteArrayOutputStream out)
	{
		out.write(0);
		out.write(0);
		out.write(0);
		out.write(1);
	}

	/** IDR access unit: SPS, PPS, then the slice -- what the encoder emits. */
	private static EncodedFrame idr(long ts, int payloadBytes)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeStartCode(out);
		out.write(SPS, 0, SPS.length);
		writeStartCode(out);
		out.write(PPS, 0, PPS.length);
		writeStartCode(out);
		out.write(0x65); // nal_unit_type 5, IDR slice
		for (int i = 0; i < payloadBytes; i++)
		{
			out.write(0x42);
		}
		return new EncodedFrame(0, ts, true, 0, false, out.toByteArray());
	}

	private static EncodedFrame inter(long ts, int payloadBytes)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeStartCode(out);
		out.write(0x41); // nal_unit_type 1, non-IDR slice
		for (int i = 0; i < payloadBytes; i++)
		{
			out.write(0x43);
		}
		return new EncodedFrame(0, ts, false, 1, false, out.toByteArray());
	}

	@Test
	public void producesAStructurallyCompleteMp4() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("session.mp4");
		StreamingMp4Writer writer = open(file);
		writer.write(idr(1000, 500));
		for (int i = 1; i < 30; i++)
		{
			writer.write(inter(1000 + i * 33L, 100));
		}
		writer.finish();

		byte[] mp4 = Files.readAllBytes(file);
		Map<String, int[]> boxes = topLevelBoxes(mp4);
		assertTrue(boxes.containsKey("ftyp"));
		assertTrue(boxes.containsKey("mdat"));
		assertTrue(boxes.containsKey("moov"));

		int[] mdat = boxes.get("mdat");
		// mdat size must cover exactly its payload, and moov must start right after.
		assertEquals(mdat[0] + mdat[1], boxes.get("moov")[0]);
		assertEquals(30, sampleCount(mp4, boxes.get("moov")));
	}

	@Test
	public void mdatSizeIsPatchedToTheBytesActuallyWritten() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("size.mp4");
		StreamingMp4Writer writer = open(file);
		writer.write(idr(0, 1000));
		writer.write(inter(33, 2000));
		writer.finish();

		byte[] mp4 = Files.readAllBytes(file);
		int[] mdat = topLevelBoxes(mp4).get("mdat");
		// Each sample is a 4-byte length prefix plus the slice NAL.
		int expectedPayload = (4 + 1 + 1000) + (4 + 1 + 2000);
		assertEquals(8 + expectedPayload, mdat[1]);
	}

	@Test
	public void parameterSetsAreLiftedOutOfSampleData() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("params.mp4");
		StreamingMp4Writer writer = open(file);
		writer.write(idr(0, 10));
		writer.finish();

		byte[] mp4 = Files.readAllBytes(file);
		int[] mdat = topLevelBoxes(mp4).get("mdat");
		// SPS and PPS belong in avcC, not mdat: only the IDR slice is a sample.
		assertEquals(8 + 4 + 1 + 10, mdat[1]);
		assertTrue(writer.hasKeyframe());
	}

	@Test
	public void anAccessUnitWithOnlyParameterSetsAddsNoSample() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("empty-au.mp4");
		StreamingMp4Writer writer = open(file);

		ByteArrayOutputStream au = new ByteArrayOutputStream();
		writeStartCode(au);
		au.write(SPS, 0, SPS.length);
		writer.write(new EncodedFrame(0, 0, false, 0, false, au.toByteArray()));
		assertEquals(0, writer.frameCount());

		writer.write(idr(33, 10));
		assertEquals(1, writer.frameCount());
		writer.finish();
	}

	@Test
	public void anUnfinishedFileIsRecognisableAsIncomplete() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("crashed.mp4");
		StreamingMp4Writer writer = open(file);
		writer.write(idr(0, 100));
		writer.close();

		assertTrue(StreamingMp4Writer.isIncomplete(file));

		Path good = tmp.getRoot().toPath().resolve("good.mp4");
		StreamingMp4Writer ok = open(good);
		ok.write(idr(0, 100));
		ok.finish();
		assertFalse(StreamingMp4Writer.isIncomplete(good));
	}

	@Test
	public void abortLeavesNoFile() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("aborted.mp4");
		StreamingMp4Writer writer = open(file);
		writer.write(idr(0, 100));
		writer.abort();
		assertFalse(Files.exists(file));
	}

	@Test
	public void finishingWithNoFramesFails() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("nothing.mp4");
		StreamingMp4Writer writer = open(file);
		try
		{
			writer.finish();
			fail("expected IOException");
		}
		catch (IOException expected)
		{
			assertNotNull(expected.getMessage());
		}
	}

	@Test
	public void spsPpsFallBackToTheDriverBlobWhenNotInBand() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("driver-params.mp4");
		StreamingMp4Writer writer = open(file);
		// An IDR whose access unit carries no parameter sets.
		ByteArrayOutputStream au = new ByteArrayOutputStream();
		writeStartCode(au);
		au.write(0x65);
		au.write(0x11);
		writer.write(new EncodedFrame(0, 0, true, 0, false, au.toByteArray()));
		writer.finish();

		assertTrue(topLevelBoxes(Files.readAllBytes(file)).containsKey("moov"));
	}

	@Test
	public void durationTracksWallClockNotFrameCount() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("duration.mp4");
		StreamingMp4Writer writer = open(file);
		writer.write(idr(10_000, 100));
		writer.write(inter(12_500, 100));
		assertEquals(2500L, writer.durationMs());
		writer.finish();
	}

	@Test
	public void theFileOpensBeforeTheEncodeParametersAreKnown() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("late-params.mp4");
		StreamingMp4Writer writer = new StreamingMp4Writer(file);
		// Open alone must not need a segment: that is what keeps file creation
		// off the encoder thread.
		assertTrue(Files.exists(file));

		writer.segment(info());
		writer.write(idr(0, 100));
		writer.finish();
		assertTrue(topLevelBoxes(Files.readAllBytes(file)).containsKey("moov"));
	}

	@Test
	public void finishingWithoutParametersFails() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("no-params.mp4");
		StreamingMp4Writer writer = new StreamingMp4Writer(file);
		writer.write(idr(0, 100));
		try
		{
			writer.finish();
			fail("expected IOException");
		}
		catch (IOException expected)
		{
			assertNotNull(expected.getMessage());
		}
	}

	@Test
	public void theFirstSegmentWins() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("first-segment.mp4");
		StreamingMp4Writer writer = new StreamingMp4Writer(file);
		writer.segment(info());
		writer.segment(new EncodedSegmentInfo(1, 800, 600, 800, 608, 60, null));
		writer.write(idr(0, 100));
		writer.finish();

		// A track cannot change coded extent mid-stream; the resize opens a
		// continuation file instead.
		byte[] mp4 = Files.readAllBytes(file);
		assertTrue(containsBigEndianShortPair(mp4, 1920, 1080));
	}

	/** Looks for the tkhd/stsd width|height pair as 16.16 or 16-bit values. */
	private static boolean containsBigEndianShortPair(byte[] data, int first, int second)
	{
		for (int i = 0; i + 4 <= data.length; i++)
		{
			int a = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
			int b = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
			if (a == first && b == second)
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void driverSuppliedParametersCountAsFinishable() throws IOException
	{
		// The gate that decides whether a session is worth keeping must agree
		// with what finish() can actually resolve, or every session whose
		// driver keeps SPS/PPS out of band is discarded.
		Path file = tmp.getRoot().toPath().resolve("driver-only.mp4");
		StreamingMp4Writer writer = new StreamingMp4Writer(file);
		writer.segment(info());
		assertFalse(writer.canFinish());

		ByteArrayOutputStream au = new ByteArrayOutputStream();
		writeStartCode(au);
		au.write(0x65);
		au.write(0x11);
		writer.write(new EncodedFrame(0, 0, true, 0, false, au.toByteArray()));

		assertFalse("no in-band parameter sets", writer.hasKeyframe());
		assertTrue("driver blob is enough", writer.canFinish());
		writer.finish();
		assertTrue(topLevelBoxes(Files.readAllBytes(file)).containsKey("moov"));
	}

	@Test
	public void withNeitherInBandNorDriverParametersItIsNotFinishable() throws IOException
	{
		Path file = tmp.getRoot().toPath().resolve("no-params-at-all.mp4");
		StreamingMp4Writer writer = new StreamingMp4Writer(file);
		writer.segment(new EncodedSegmentInfo(0, 1920, 1080, 1920, 1088, 30, null));

		ByteArrayOutputStream au = new ByteArrayOutputStream();
		writeStartCode(au);
		au.write(0x65);
		au.write(0x11);
		writer.write(new EncodedFrame(0, 0, true, 0, false, au.toByteArray()));

		assertFalse(writer.canFinish());
		writer.abort();
	}

	/** name -> {offset, size} for each top-level box, in file order. */
	private static Map<String, int[]> topLevelBoxes(byte[] mp4)
	{
		Map<String, int[]> out = new LinkedHashMap<>();
		int pos = 0;
		while (pos + 8 <= mp4.length)
		{
			int size = ByteBuffer.wrap(mp4, pos, 4).getInt();
			String type = new String(mp4, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
			if (size < 8 || pos + size > mp4.length)
			{
				break;
			}
			out.put(type, new int[]{pos, size});
			pos += size;
		}
		return out;
	}

	/** Reads sample_count out of the first stsz inside moov. */
	private static int sampleCount(byte[] mp4, int[] moov)
	{
		for (int i = moov[0]; i + 20 < moov[0] + moov[1]; i++)
		{
			if (mp4[i] == 's' && mp4[i + 1] == 't' && mp4[i + 2] == 's' && mp4[i + 3] == 'z')
			{
				// stsz: version/flags(4) sample_size(4) sample_count(4)
				return ByteBuffer.wrap(mp4, i + 12, 4).getInt();
			}
		}
		return -1;
	}
}
