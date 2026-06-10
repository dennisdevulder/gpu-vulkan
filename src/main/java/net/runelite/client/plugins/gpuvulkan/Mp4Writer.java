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

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal single-pass MP4 (ISO BMFF) muxer for the replay buffer's H.264
 * stream. Wraps already-encoded access units — no re-encoding, no temp
 * files: one {@code ftyp} + {@code mdat} + {@code moov} write.
 *
 * <p>Assumes what {@link H264EncodeSession} produces: Annex-B NAL units,
 * every frame an IDR picture (so no {@code stss} box — its absence marks
 * all samples sync), one video track, no audio.
 */
final class Mp4Writer
{
	/** Media timescale: microseconds. */
	private static final int TIMESCALE = 1_000_000;

	private Mp4Writer()
	{
	}

	static final class Sample
	{
		final byte[] annexB;
		final long captureNanos;

		Sample(byte[] annexB, long captureNanos)
		{
			this.annexB = annexB;
			this.captureNanos = captureNanos;
		}
	}

	/**
	 * @param annexBHeader the encoded SPS+PPS parameter sets (Annex-B)
	 * @param width visible (cropped) width in pixels
	 * @param height visible height in pixels
	 */
	static void write(File file, byte[] annexBHeader, List<Sample> samples, int width, int height)
		throws IOException
	{
		byte[] sps = null;
		byte[] pps = null;
		for (byte[] nal : splitNals(annexBHeader))
		{
			int type = nal[0] & 0x1F;
			if (type == 7)
			{
				sps = nal;
			}
			else if (type == 8)
			{
				pps = nal;
			}
		}
		if (sps == null || pps == null)
		{
			throw new IOException("Parameter header is missing SPS or PPS");
		}

		// Annex-B start codes -> 4-byte length prefixes (AVCC), as stored in mdat.
		List<byte[]> avccSamples = new ArrayList<>(samples.size());
		long mdatPayload = 0;
		for (Sample sample : samples)
		{
			byte[] avcc = toAvcc(sample.annexB);
			avccSamples.add(avcc);
			mdatPayload += avcc.length;
		}

		int[] durations = sampleDurationsMicros(samples);
		long totalMicros = 0;
		for (int d : durations)
		{
			totalMicros += d;
		}

		byte[] ftyp = ftyp();
		long sampleDataOffset = ftyp.length + 8; // mdat header
		byte[] moov = moov(sps, pps, avccSamples, durations, totalMicros, width, height, sampleDataOffset);

		try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 20))
		{
			out.write(ftyp);
			DataOutputStream data = new DataOutputStream(out);
			data.writeInt((int) (mdatPayload + 8));
			data.writeBytes("mdat");
			for (byte[] sample : avccSamples)
			{
				out.write(sample);
			}
			out.write(moov);
		}
	}

	private static List<byte[]> splitNals(byte[] annexB)
	{
		List<byte[]> nals = new ArrayList<>();
		int i = 0;
		int start = -1;
		while (i + 2 < annexB.length)
		{
			if (annexB[i] == 0 && annexB[i + 1] == 0
				&& (annexB[i + 2] == 1 || (annexB[i + 2] == 0 && i + 3 < annexB.length && annexB[i + 3] == 1)))
			{
				int codeLen = annexB[i + 2] == 1 ? 3 : 4;
				if (start >= 0)
				{
					nals.add(java.util.Arrays.copyOfRange(annexB, start, i));
				}
				start = i + codeLen;
				i = start;
			}
			else
			{
				i++;
			}
		}
		if (start >= 0 && start < annexB.length)
		{
			nals.add(java.util.Arrays.copyOfRange(annexB, start, annexB.length));
		}
		return nals;
	}

	private static byte[] toAvcc(byte[] annexB)
	{
		List<byte[]> nals = splitNals(annexB);
		int size = 0;
		for (byte[] nal : nals)
		{
			size += 4 + nal.length;
		}
		byte[] out = new byte[size];
		int pos = 0;
		for (byte[] nal : nals)
		{
			out[pos++] = (byte) (nal.length >>> 24);
			out[pos++] = (byte) (nal.length >>> 16);
			out[pos++] = (byte) (nal.length >>> 8);
			out[pos++] = (byte) nal.length;
			System.arraycopy(nal, 0, out, pos, nal.length);
			pos += nal.length;
		}
		return out;
	}

	/** Per-sample durations from capture timestamps; the last sample reuses
	 *  the previous delta (there is no "next" timestamp to subtract). */
	private static int[] sampleDurationsMicros(List<Sample> samples)
	{
		int n = samples.size();
		int[] durations = new int[n];
		int fallback = 33_333;
		for (int i = 0; i < n - 1; i++)
		{
			long delta = (samples.get(i + 1).captureNanos - samples.get(i).captureNanos) / 1_000L;
			durations[i] = (int) Math.max(1, Math.min(delta, Integer.MAX_VALUE));
			fallback = durations[i];
		}
		durations[n - 1] = fallback;
		return durations;
	}

	// ---- boxes -------------------------------------------------------------

	private static byte[] ftyp() throws IOException
	{
		Box b = new Box("ftyp");
		b.out.writeBytes("isom");
		b.out.writeInt(0x200);
		b.out.writeBytes("isomiso2avc1mp41");
		return b.bytes();
	}

	private static byte[] moov(byte[] sps, byte[] pps, List<byte[]> avccSamples, int[] durations,
		long totalMicros, int width, int height, long sampleDataOffset) throws IOException
	{
		Box moov = new Box("moov");
		moov.out.write(mvhd(totalMicros));
		moov.out.write(trak(sps, pps, avccSamples, durations, totalMicros, width, height, sampleDataOffset));
		return moov.bytes();
	}

	private static byte[] mvhd(long totalMicros) throws IOException
	{
		Box b = new Box("mvhd");
		b.out.writeInt(0);                       // version + flags
		b.out.writeInt(0);                       // creation time
		b.out.writeInt(0);                       // modification time
		b.out.writeInt(1000);                    // timescale (ms)
		b.out.writeInt((int) (totalMicros / 1000));
		b.out.writeInt(0x00010000);              // rate 1.0
		b.out.writeShort(0x0100);                // volume 1.0
		b.out.writeShort(0);
		b.out.writeInt(0);
		b.out.writeInt(0);
		writeIdentityMatrix(b.out);
		for (int i = 0; i < 6; i++)
		{
			b.out.writeInt(0);                   // pre_defined
		}
		b.out.writeInt(2);                       // next track id
		return b.bytes();
	}

	private static byte[] trak(byte[] sps, byte[] pps, List<byte[]> avccSamples, int[] durations,
		long totalMicros, int width, int height, long sampleDataOffset) throws IOException
	{
		Box trak = new Box("trak");
		trak.out.write(tkhd(totalMicros, width, height));
		trak.out.write(mdia(sps, pps, avccSamples, durations, totalMicros, width, height, sampleDataOffset));
		return trak.bytes();
	}

	private static byte[] tkhd(long totalMicros, int width, int height) throws IOException
	{
		Box b = new Box("tkhd");
		b.out.writeInt(0x000003);                // version 0, flags: enabled | in movie
		b.out.writeInt(0);
		b.out.writeInt(0);
		b.out.writeInt(1);                       // track id
		b.out.writeInt(0);
		b.out.writeInt((int) (totalMicros / 1000));
		b.out.writeInt(0);
		b.out.writeInt(0);
		b.out.writeShort(0);                     // layer
		b.out.writeShort(0);                     // alternate group
		b.out.writeShort(0);                     // volume (video)
		b.out.writeShort(0);
		writeIdentityMatrix(b.out);
		b.out.writeInt(width << 16);             // 16.16 fixed
		b.out.writeInt(height << 16);
		return b.bytes();
	}

	private static byte[] mdia(byte[] sps, byte[] pps, List<byte[]> avccSamples, int[] durations,
		long totalMicros, int width, int height, long sampleDataOffset) throws IOException
	{
		Box mdia = new Box("mdia");

		Box mdhd = new Box("mdhd");
		mdhd.out.writeInt(0);
		mdhd.out.writeInt(0);
		mdhd.out.writeInt(0);
		mdhd.out.writeInt(TIMESCALE);
		mdhd.out.writeInt((int) totalMicros);
		mdhd.out.writeShort(0x55C4);             // language: und
		mdhd.out.writeShort(0);
		mdia.out.write(mdhd.bytes());

		Box hdlr = new Box("hdlr");
		hdlr.out.writeInt(0);
		hdlr.out.writeInt(0);
		hdlr.out.writeBytes("vide");
		hdlr.out.writeInt(0);
		hdlr.out.writeInt(0);
		hdlr.out.writeInt(0);
		hdlr.out.writeBytes("VideoHandler");
		hdlr.out.writeByte(0);
		mdia.out.write(hdlr.bytes());

		Box minf = new Box("minf");

		Box vmhd = new Box("vmhd");
		vmhd.out.writeInt(1);                    // version 0, flags 1
		vmhd.out.writeShort(0);                  // graphics mode
		vmhd.out.writeShort(0);
		vmhd.out.writeShort(0);
		vmhd.out.writeShort(0);
		minf.out.write(vmhd.bytes());

		Box dinf = new Box("dinf");
		Box dref = new Box("dref");
		dref.out.writeInt(0);
		dref.out.writeInt(1);                    // one entry
		Box url = new Box("url ");
		url.out.writeInt(1);                     // flags: data in this file
		dref.out.write(url.bytes());
		dinf.out.write(dref.bytes());
		minf.out.write(dinf.bytes());

		minf.out.write(stbl(sps, pps, avccSamples, durations, width, height, sampleDataOffset));
		mdia.out.write(minf.bytes());
		return mdia.bytes();
	}

	private static byte[] stbl(byte[] sps, byte[] pps, List<byte[]> avccSamples, int[] durations,
		int width, int height, long sampleDataOffset) throws IOException
	{
		Box stbl = new Box("stbl");

		Box stsd = new Box("stsd");
		stsd.out.writeInt(0);
		stsd.out.writeInt(1);
		stsd.out.write(avc1(sps, pps, width, height));
		stbl.out.write(stsd.bytes());

		// stts run-length encodes (count, duration) pairs.
		Box stts = new Box("stts");
		stts.out.writeInt(0);
		List<int[]> runs = new ArrayList<>();
		for (int d : durations)
		{
			if (!runs.isEmpty() && runs.get(runs.size() - 1)[1] == d)
			{
				runs.get(runs.size() - 1)[0]++;
			}
			else
			{
				runs.add(new int[]{1, d});
			}
		}
		stts.out.writeInt(runs.size());
		for (int[] run : runs)
		{
			stts.out.writeInt(run[0]);
			stts.out.writeInt(run[1]);
		}
		stbl.out.write(stts.bytes());

		Box stsc = new Box("stsc");
		stsc.out.writeInt(0);
		stsc.out.writeInt(1);
		stsc.out.writeInt(1);                    // first chunk
		stsc.out.writeInt(avccSamples.size());   // samples per chunk
		stsc.out.writeInt(1);                    // sample description index
		stbl.out.write(stsc.bytes());

		Box stsz = new Box("stsz");
		stsz.out.writeInt(0);
		stsz.out.writeInt(0);                    // non-uniform sizes
		stsz.out.writeInt(avccSamples.size());
		for (byte[] sample : avccSamples)
		{
			stsz.out.writeInt(sample.length);
		}
		stbl.out.write(stsz.bytes());

		Box stco = new Box("stco");
		stco.out.writeInt(0);
		stco.out.writeInt(1);
		stco.out.writeInt((int) sampleDataOffset);
		stbl.out.write(stco.bytes());

		return stbl.bytes();
	}

	private static byte[] avc1(byte[] sps, byte[] pps, int width, int height) throws IOException
	{
		Box b = new Box("avc1");
		b.out.writeInt(0);                       // reserved
		b.out.writeShort(0);
		b.out.writeShort(1);                     // data reference index
		b.out.writeShort(0);                     // pre_defined
		b.out.writeShort(0);
		b.out.writeInt(0);
		b.out.writeInt(0);
		b.out.writeInt(0);
		b.out.writeShort(width);
		b.out.writeShort(height);
		b.out.writeInt(0x00480000);              // 72 dpi horizontal
		b.out.writeInt(0x00480000);              // 72 dpi vertical
		b.out.writeInt(0);
		b.out.writeShort(1);                     // frame count per sample
		for (int i = 0; i < 32; i++)
		{
			b.out.writeByte(0);                  // compressor name
		}
		b.out.writeShort(24);                    // depth
		b.out.writeShort(-1);                    // pre_defined

		Box avcC = new Box("avcC");
		avcC.out.writeByte(1);                   // configuration version
		avcC.out.writeByte(sps[1]);              // profile
		avcC.out.writeByte(sps[2]);              // profile compatibility
		avcC.out.writeByte(sps[3]);              // level
		avcC.out.writeByte(0xFF);                // 4-byte NAL lengths
		avcC.out.writeByte(0xE1);                // 1 SPS
		avcC.out.writeShort(sps.length);
		avcC.out.write(sps);
		avcC.out.writeByte(1);                   // 1 PPS
		avcC.out.writeShort(pps.length);
		avcC.out.write(pps);
		b.out.write(avcC.bytes());
		return b.bytes();
	}

	private static void writeIdentityMatrix(DataOutputStream out) throws IOException
	{
		out.writeInt(0x00010000);
		out.writeInt(0);
		out.writeInt(0);
		out.writeInt(0);
		out.writeInt(0x00010000);
		out.writeInt(0);
		out.writeInt(0);
		out.writeInt(0);
		out.writeInt(0x40000000);
	}

	private static final class Box
	{
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final DataOutputStream out = new DataOutputStream(body);
		private final String type;

		Box(String type)
		{
			this.type = type;
		}

		byte[] bytes() throws IOException
		{
			out.flush();
			byte[] payload = body.toByteArray();
			ByteArrayOutputStream boxed = new ByteArrayOutputStream(payload.length + 8);
			DataOutputStream header = new DataOutputStream(boxed);
			header.writeInt(payload.length + 8);
			header.writeBytes(type);
			header.write(payload);
			return boxed.toByteArray();
		}
	}
}
