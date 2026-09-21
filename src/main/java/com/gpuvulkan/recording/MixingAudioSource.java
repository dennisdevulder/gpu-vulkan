/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording;

import lombok.extern.slf4j.Slf4j;

/**
 * Sums a microphone into system capture. The primary drives the clock; the
 * secondary is queued and consumed to match, since the two lines deliver in
 * bursts of different sizes.
 */
@Slf4j
final class MixingAudioSource implements AudioSource
{
	private static final int FRAME_BYTES = 2 * 2;
	/** Roughly 200ms at 48 kHz stereo. Past this the mic is unusably late. */
	private static final int MAX_QUEUE_BYTES = 48_000 * FRAME_BYTES / 5;

	private final AudioSource primary;
	private final AudioSource secondary;
	private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
	private byte[] queued = new byte[0];
	private int queuedOffset;
	private byte[] scratch = new byte[0];
	private volatile boolean secondaryFailed;
	private volatile float mixedLevel;

	MixingAudioSource(AudioSource primary, AudioSource secondary)
	{
		this.primary = primary;
		this.secondary = secondary;
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

		drainSecondary();
		int mixable = take(read);
		if (mixable > 0)
		{
			mix(buffer, queued, queuedOffset - mixable, mixable);
		}
		mixedLevel = AudioLevels.peak(buffer, read);
		return read;
	}

	/** Bounded by the queue cap, not a read count: small bursts need many reads. */
	private void drainSecondary() throws Exception
	{
		if (scratch.length < 8192)
		{
			scratch = new byte[8192];
		}
		while (pending.size() < MAX_QUEUE_BYTES)
		{
			int read = secondary.read(scratch);
			if (read <= 0)
			{
				break;
			}
			pending.write(scratch, 0, read);
		}
	}

	/** Dequeues up to {@code wanted} bytes; the only place drift is corrected. */
	private int take(int wanted)
	{
		if (pending.size() > 0)
		{
			byte[] drained = pending.toByteArray();
			pending.reset();
			int remaining = queued.length - queuedOffset;
			byte[] merged = new byte[remaining + drained.length];
			System.arraycopy(queued, queuedOffset, merged, 0, remaining);
			System.arraycopy(drained, 0, merged, remaining, drained.length);
			// Drop oldest so a late mic catches up rather than drifting further.
			int drop = Math.max(0, merged.length - MAX_QUEUE_BYTES);
			queued = merged;
			queuedOffset = drop - drop % FRAME_BYTES;
		}
		int available = queued.length - queuedOffset;
		int take = Math.min(wanted, available);
		// Whole frames: a split one swaps the mic's channels from then on.
		take -= take % FRAME_BYTES;
		queuedOffset += take;
		return take;
	}

	/** Saturating sum: two full-scale sources would wrap without the clamp. */
	private void mix(byte[] into, byte[] from, int fromOffset, int length)
	{
		for (int i = 0; i + 1 < length; i += 2)
		{
			int a = (short) ((into[i + 1] << 8) | (into[i] & 0xFF));
			int b = (short) ((from[fromOffset + i + 1] << 8) | (from[fromOffset + i] & 0xFF));
			int sum = a + b;
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
		return mixedLevel;
	}

	boolean micFailed()
	{
		return secondaryFailed;
	}

	float micLevel()
	{
		return secondaryFailed ? 0f : secondary.level();
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
