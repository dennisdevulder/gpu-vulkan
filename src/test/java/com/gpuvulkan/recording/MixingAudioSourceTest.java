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
			new Tone((short) 1000, 10), new Tone((short) 500, 10), 100);
		mix.start();

		byte[] buffer = new byte[64];
		assertEquals(64, mix.read(buffer));
		assertEquals(1500, sampleAt(buffer, 0));
	}

	@Test
	public void gainScalesOnlyTheMixedInSource() throws Exception
	{
		MixingAudioSource mix = new MixingAudioSource(
			new Tone((short) 1000, 10), new Tone((short) 500, 10), 50);
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
			new Tone((short) 30000, 10), new Tone((short) 30000, 10), 100);
		mix.start();

		byte[] buffer = new byte[64];
		mix.read(buffer);
		assertEquals(Short.MAX_VALUE, sampleAt(buffer, 0));
	}

	@Test
	public void aMicThatWillNotOpenLeavesSystemAudioRecording() throws Exception
	{
		Tone system = new Tone((short) 1000, 10);
		MixingAudioSource mix = new MixingAudioSource(system, new Tone((short) 500, 10).failing(), 100);
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
			new Tone((short) 1000, 10), new Tone((short) 500, 0), 100);
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
			new Tone((short) 1000, 0), new Tone((short) 500, 10), 100);
		mix.start();
		assertEquals(0, mix.read(new byte[64]));
	}

	@Test
	public void closingReleasesBothDevices() throws Exception
	{
		Tone system = new Tone((short) 1000, 10);
		Tone mic = new Tone((short) 500, 10);
		MixingAudioSource mix = new MixingAudioSource(system, mic, 100);
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
		MixingAudioSource mix = new MixingAudioSource(system, mic, 100);
		mix.start();
		mic.closed = false;
		mix.close();
		assertTrue(system.closed);
		assertFalse(mic.closed);
	}
}
