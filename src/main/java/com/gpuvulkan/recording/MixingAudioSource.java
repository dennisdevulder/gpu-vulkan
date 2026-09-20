/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording;

import lombok.extern.slf4j.Slf4j;

/**
 * Sums a second source into the first, so a recording can carry the mic
 * alongside system output. Your own voice never reaches the output monitor --
 * it goes to the chat application, not to your speakers -- so without this you
 * hear everyone in a call except yourself.
 *
 * The primary drives the clock. Both lines run at the same rate, so the mic is
 * read to match and its own buffer absorbs jitter.
 */
@Slf4j
final class MixingAudioSource implements AudioSource
{
	private final AudioSource primary;
	private final AudioSource secondary;
	private final float gain;
	private byte[] scratch = new byte[0];
	private boolean secondaryFailed;
	private volatile float micLevel;

	MixingAudioSource(AudioSource primary, AudioSource secondary, int gainPercent)
	{
		this.primary = primary;
		this.secondary = secondary;
		this.gain = Math.max(0, gainPercent) / 100f;
	}

	@Override
	public int sampleRate()
	{
		return primary.sampleRate();
	}

	@Override
	public int channels()
	{
		return primary.channels();
	}

	@Override
	public void start() throws Exception
	{
		primary.start();
		try
		{
			secondary.start();
		}
		catch (Exception e)
		{
			// The mic is the optional half: losing it must not lose the recording.
			log.warn("Microphone unavailable, recording system audio only", e);
			secondaryFailed = true;
			secondary.close();
		}
	}

	@Override
	public int read(byte[] buffer) throws Exception
	{
		int read = primary.read(buffer);
		if (read <= 0 || secondaryFailed)
		{
			return read;
		}
		if (scratch.length < read)
		{
			scratch = new byte[read];
		}

		int mixable = secondary.read(scratch);
		if (mixable > 0)
		{
			micLevel = AudioLevels.peak(scratch, mixable);
			mix(buffer, scratch, Math.min(read, mixable));
		}
		return read;
	}

	/** Saturating sum: two full-scale sources would wrap without the clamp. */
	private void mix(byte[] into, byte[] from, int length)
	{
		for (int i = 0; i + 1 < length; i += 2)
		{
			int a = (short) ((into[i + 1] << 8) | (into[i] & 0xFF));
			int b = (short) ((from[i + 1] << 8) | (from[i] & 0xFF));
			int sum = a + Math.round(b * gain);
			if (sum > Short.MAX_VALUE)
			{
				sum = Short.MAX_VALUE;
			}
			else if (sum < Short.MIN_VALUE)
			{
				sum = Short.MIN_VALUE;
			}
			into[i] = (byte) (sum & 0xFF);
			into[i + 1] = (byte) ((sum >> 8) & 0xFF);
		}
	}

	@Override
	public float level()
	{
		return primary.level();
	}

	/** Pre-gain level of the mixed-in microphone. */
	float micLevel()
	{
		return secondaryFailed ? 0f : micLevel;
	}

	@Override
	public void close()
	{
		primary.close();
		if (!secondaryFailed)
		{
			secondary.close();
		}
	}
}
