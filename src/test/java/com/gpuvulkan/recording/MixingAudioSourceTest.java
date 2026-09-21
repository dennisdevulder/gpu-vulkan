/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MixingAudioSourceTest
{
	/** Emits a fixed 16-bit LE sample repeatedly, then reports exhaustion. */
	private static final class Tone implements AudioSource
	{
		private final short value;
		private int readsLeft;
		private boolean failOnStart;
		boolean closed;

		Tone(short value, int readsLeft)
		{
			this.value = value;
			this.readsLeft = readsLeft;
		}

		Tone failing()
		{
			this.failOnStart = true;
			return this;
		}

		@Override
		public int sampleRate()
		{
			return 48000;
		}

		@Override
		public int channels()
		{
			return 2;
		}

		@Override
		public void start() throws Exception
		{
			if (failOnStart)
			{
				throw new IllegalStateException("device in use");
			}
		}

		@Override
		public int read(byte[] buffer)
		{
			if (readsLeft-- <= 0)
			{
				return 0;
			}
			for (int i = 0; i + 1 < buffer.length; i += 2)
			{
				buffer[i] = (byte) (value & 0xFF);
				buffer[i + 1] = (byte) (value >> 8);
			}
			return buffer.length;
		}

		@Override
		public void close()
		{
			closed = true;
		}
	}

	private static short sampleAt(byte[] pcm, int index)
	{
		return (short) ((pcm[index * 2 + 1] << 8) | (pcm[index * 2] & 0xFF));
	}

	@Test
	public void bothSourcesAreSummed() throws Exception
	{
		MixingAudioSource mix = new MixingAudioSource(
			new Tone((short) 1000, 10), new Tone((short) 500, 10));
		mix.start();

		byte[] buffer = new byte[64];
		assertEquals(64, mix.read(buffer));
		assertEquals(1500, sampleAt(buffer, 0));
	}

	@Test
	public void gainScalesOnlyTheMixedInSource() throws Exception
	{
		MixingAudioSource mix = new MixingAudioSource(
			new Tone((short) 1000, 10), new GainAudioSource(new Tone((short) 500, 10), 50));
		mix.start();

		byte[] buffer = new byte[64];
		mix.read(buffer);
		assertEquals(1250, sampleAt(buffer, 0));
	}

	@Test
	public void summingSaturatesRatherThanWrapping() throws Exception
	{
		// Two loud sources would wrap to a large negative value without the clamp,
		// which is an audible click rather than distortion.
		MixingAudioSource mix = new MixingAudioSource(
			new Tone((short) 30000, 10), new Tone((short) 30000, 10));
		mix.start();

		byte[] buffer = new byte[64];
		mix.read(buffer);
		assertEquals(Short.MAX_VALUE, sampleAt(buffer, 0));
	}

	@Test
	public void aMicThatWillNotOpenLeavesSystemAudioRecording() throws Exception
	{
		Tone system = new Tone((short) 1000, 10);
		MixingAudioSource mix = new MixingAudioSource(system, new Tone((short) 500, 10).failing());
		mix.start();

		byte[] buffer = new byte[64];
		assertEquals(64, mix.read(buffer));
		// Untouched by the mic that never started.
		assertEquals(1000, sampleAt(buffer, 0));
	}

	@Test
	public void theMicRunningDryLeavesSystemAudioIntact() throws Exception
	{
		MixingAudioSource mix = new MixingAudioSource(
			new Tone((short) 1000, 10), new Tone((short) 500, 0));
		mix.start();

		byte[] buffer = new byte[64];
		mix.read(buffer);
		assertEquals(1000, sampleAt(buffer, 0));
	}

	@Test
	public void primaryDrivesTheClock() throws Exception
	{
		// No primary data means no output, regardless of the mic.
		MixingAudioSource mix = new MixingAudioSource(
			new Tone((short) 1000, 0), new Tone((short) 500, 10));
		mix.start();
		assertEquals(0, mix.read(new byte[64]));
	}

	/** Emits a fixed number of bytes per read, to model bursty delivery. */
	private static final class Burst implements AudioSource
	{
		private final short value;
		private final int bytesPerRead;
		private int budget;

		Burst(short value, int bytesPerRead, int totalBytes)
		{
			this.value = value;
			this.bytesPerRead = bytesPerRead;
			this.budget = totalBytes;
		}

		@Override
		public int sampleRate()
		{
			return 48000;
		}

		@Override
		public int channels()
		{
			return 2;
		}

		@Override
		public void start()
		{
		}

		@Override
		public int read(byte[] buffer)
		{
			int n = Math.min(Math.min(bytesPerRead, buffer.length), budget);
			for (int i = 0; i + 1 < n; i += 2)
			{
				buffer[i] = (byte) (value & 0xFF);
				buffer[i + 1] = (byte) (value >> 8);
			}
			budget -= n;
			return n;
		}

		@Override
		public void close()
		{
		}
	}

	@Test
	public void micAudioArrivingInSmallerBurstsIsNotLost() throws Exception
	{
		// The mic delivers 16 bytes at a time while the primary asks for 64.
		// Reading it opportunistically would leave three quarters of each
		// block without voice, which is what chopped speech sounded like.
		MixingAudioSource mix = new MixingAudioSource(
			new Tone((short) 100, 100), new Burst((short) 1000, 16, 4096));
		mix.start();

		byte[] buffer = new byte[64];
		mix.read(buffer);
		for (int i = 0; i < 32; i++)
		{
			assertEquals("sample " + i + " lost its mic audio", 1100, sampleAt(buffer, i));
		}
	}

	@Test
	public void queuedMicAudioCarriesAcrossReads() throws Exception
	{
		MixingAudioSource mix = new MixingAudioSource(
			new Tone((short) 100, 100), new Burst((short) 1000, 16, 4096));
		mix.start();

		byte[] buffer = new byte[64];
		mix.read(buffer);
		java.util.Arrays.fill(buffer, (byte) 0);
		mix.read(buffer);
		// The second block is still voiced: nothing was dropped between them.
		assertEquals(1100, sampleAt(buffer, 0));
	}

	@Test
	public void aMicThatStopsDeliveringLeavesTheRestUnvoiced()
		throws Exception
	{
		MixingAudioSource mix = new MixingAudioSource(
			new Tone((short) 100, 100), new Burst((short) 1000, 16, 16));
		mix.start();

		byte[] buffer = new byte[64];
		mix.read(buffer);
		assertEquals(1100, sampleAt(buffer, 0));
		// Past the 16 bytes the mic had, the primary is untouched.
		assertEquals(100, sampleAt(buffer, 20));
	}

	/** Alternates L and R values so a channel swap is detectable. */
	private static final class StereoBurst implements AudioSource
	{
		private final int bytesPerRead;
		private int budget;
		/** Absolute sample index, so the L/R pattern continues across bursts. */
		private int sampleIndex;

		StereoBurst(int bytesPerRead, int totalBytes)
		{
			this.bytesPerRead = bytesPerRead;
			this.budget = totalBytes;
		}

		@Override
		public int sampleRate()
		{
			return 48000;
		}

		@Override
		public int channels()
		{
			return 2;
		}

		@Override
		public void start()
		{
		}

		@Override
		public int read(byte[] buffer)
		{
			int n = Math.min(Math.min(bytesPerRead, buffer.length), budget);
			for (int i = 0; i + 1 < n; i += 2)
			{
				// Left = 1000, right = 2000, by absolute sample position.
				short v = (short) ((sampleIndex++ % 2 == 0) ? 1000 : 2000);
				buffer[i] = (byte) (v & 0xFF);
				buffer[i + 1] = (byte) (v >> 8);
			}
			budget -= n;
			return n;
		}

		@Override
		public void close()
		{
		}
	}

	@Test
	public void micChannelsStayAlignedWhenBurstsSplitAFrame() throws Exception
	{
		// 6-byte bursts are one and a half stereo frames. Taking an odd number
		// of samples would start the next block on the mic's right channel.
		MixingAudioSource mix = new MixingAudioSource(
			new Tone((short) 0, 100), new StereoBurst(6, 4096));
		mix.start();

		byte[] buffer = new byte[64];
		for (int block = 0; block < 4; block++)
		{
			mix.read(buffer);
			for (int frame = 0; frame < 16; frame++)
			{
				assertEquals("left, block " + block + " frame " + frame, 1000, sampleAt(buffer, frame * 2));
				assertEquals("right, block " + block + " frame " + frame, 2000, sampleAt(buffer, frame * 2 + 1));
			}
		}
	}

	@Test
	public void aLateMicDropsItsOldestAudioNotItsNewest() throws Exception
	{
		// Far more mic audio than the primary consumes: the queue must cap,
		// and what survives must be the most recent.
		MixingAudioSource mix = new MixingAudioSource(
			new Tone((short) 0, 100), new Burst((short) 7, 8192, 1 << 20));
		mix.start();

		byte[] buffer = new byte[64];
		for (int i = 0; i < 50; i++)
		{
			mix.read(buffer);
		}
		// Still voiced after heavy over-delivery, so the cap did not empty it.
		assertEquals(7, sampleAt(buffer, 0));
	}

	@Test
	public void closingReleasesBothDevices() throws Exception
	{
		Tone system = new Tone((short) 1000, 10);
		Tone mic = new Tone((short) 500, 10);
		MixingAudioSource mix = new MixingAudioSource(system, mic);
		mix.start();
		mix.close();
		assertTrue(system.closed);
		assertTrue(mic.closed);
	}

	@Test
	public void aFailedMicIsNotClosedTwice() throws Exception
	{
		Tone system = new Tone((short) 1000, 10);
		Tone mic = new Tone((short) 500, 10).failing();
		MixingAudioSource mix = new MixingAudioSource(system, mic);
		mix.start();
		mic.closed = false;
		mix.close();
		assertTrue(system.closed);
		assertFalse(mic.closed);
	}
}
