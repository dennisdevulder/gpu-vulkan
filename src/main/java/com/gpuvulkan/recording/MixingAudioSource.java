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
 * The primary drives the clock. The two lines deliver in bursts of different
 * sizes, so everything the secondary offers is queued and then consumed to
 * match the primary's block; reading it opportunistically instead leaves gaps
 * wherever the two did not happen to line up.
 */
@Slf4j
final class MixingAudioSource implements AudioSource
{
	/** Roughly 200ms at 48 kHz stereo. Past this the mic is unusably late. */
	private static final int MAX_QUEUE_BYTES = 48_000 * 2 * 2 / 5;

	private final AudioSource primary;
	private final AudioSource secondary;
	private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
	private byte[] queued = new byte[0];
	private int queuedOffset;
	private byte[] scratch = new byte[0];
	private boolean secondaryFailed;
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

		drainSecondary();
		int mixable = take(read);
		if (mixable > 0)
		{
			mix(buffer, queued, queuedOffset - mixable, mixable);
		}
		mixedLevel = AudioLevels.peak(buffer, read);
		return read;
	}

	/** Pulls everything the secondary currently offers into the queue. */
	private void drainSecondary() throws Exception
	{
		for (int guard = 0; guard < 8; guard++)
		{
			if (scratch.length == 0)
			{
				scratch = new byte[8192];
			}
			int read = secondary.read(scratch);
			if (read <= 0)
			{
				break;
			}
			pending.write(scratch, 0, read);
			if (pending.size() >= MAX_QUEUE_BYTES)
			{
				break;
			}
		}
	}

	/**
	 * Takes up to {@code wanted} bytes off the queue. Oldest audio is dropped
	 * when the queue has run long, which is the only point drift is corrected.
	 */
	private int take(int wanted)
	{
		if (pending.size() > 0)
		{
			byte[] drained = pending.toByteArray();
			pending.reset();
			int keepFrom = Math.max(0, drained.length - MAX_QUEUE_BYTES);
			int remaining = queued.length - queuedOffset;
			byte[] merged = new byte[remaining + drained.length - keepFrom];
			System.arraycopy(queued, queuedOffset, merged, 0, remaining);
			System.arraycopy(drained, keepFrom, merged, remaining, drained.length - keepFrom);
			queued = merged;
			queuedOffset = 0;
		}
		int available = queued.length - queuedOffset;
		int take = Math.min(wanted, available);
		take -= take % 2;
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

	/** The summed signal, which is what reaches the file. */
	@Override
	public float level()
	{
		return mixedLevel;
	}

	boolean micFailed()
	{
		return secondaryFailed;
	}

	/** The microphone's contribution to that signal, after gain. */
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
