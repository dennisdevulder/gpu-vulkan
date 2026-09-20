/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording;

/**
 * Scales a source's samples.
 *
 * Capture devices are read raw. A microphone arrives without the gain a
 * desktop normally applies to it, and an output monitor arrives scaled by the
 * playback volume, so both usually need lifting before they are usable.
 */
final class GainAudioSource implements AudioSource
{
	private final AudioSource delegate;
	private final float gain;
	private volatile float level;

	GainAudioSource(AudioSource delegate, int gainPercent)
	{
		this.delegate = delegate;
		this.gain = Math.max(0, gainPercent) / 100f;
	}

	@Override
	public int sampleRate()
	{
		return delegate.sampleRate();
	}

	@Override
	public int channels()
	{
		return delegate.channels();
	}

	@Override
	public void start() throws Exception
	{
		delegate.start();
	}

	@Override
	public int read(byte[] buffer) throws Exception
	{
		int read = delegate.read(buffer);
		if (read <= 0)
		{
			return read;
		}
		if (gain != 1f)
		{
			applyGain(buffer, read);
		}
		level = AudioLevels.peak(buffer, read);
		return read;
	}

	/** Saturating: lifting a loud source must distort rather than wrap. */
	private void applyGain(byte[] buffer, int length)
	{
		for (int i = 0; i + 1 < length; i += 2)
		{
			int sample = Math.round((short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF)) * gain);
			if (sample > Short.MAX_VALUE)
			{
				sample = Short.MAX_VALUE;
			}
			else if (sample < Short.MIN_VALUE)
			{
				sample = Short.MIN_VALUE;
			}
			buffer[i] = (byte) (sample & 0xFF);
			buffer[i + 1] = (byte) ((sample >> 8) & 0xFF);
		}
	}

	@Override
	public float level()
	{
		return level;
	}

	@Override
	public void close()
	{
		delegate.close();
	}
}
