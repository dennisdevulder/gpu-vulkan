/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Rolling buffer of timestamped PCM, the audio counterpart to the encoder's
 * NAL ring. Bounded by bytes so a long pre-roll cannot grow without limit.
 */
final class AudioRing
{
	static final class Block
	{
		final long timestampMs;
		final byte[] pcm;

		Block(long timestampMs, byte[] pcm)
		{
			this.timestampMs = timestampMs;
			this.pcm = pcm;
		}
	}

	private final Deque<Block> blocks = new ArrayDeque<>();
	private final Object lock = new Object();
	private long byteBudget;
	private long bytes;

	AudioRing(long byteBudget)
	{
		this.byteBudget = byteBudget > 0 ? byteBudget : 4L * 1024 * 1024;
	}

	void put(long timestampMs, byte[] pcm, int length)
	{
		if (length <= 0)
		{
			return;
		}
		byte[] copy = new byte[length];
		System.arraycopy(pcm, 0, copy, 0, length);
		synchronized (lock)
		{
			blocks.addLast(new Block(timestampMs, copy));
			bytes += length;
			while (bytes > byteBudget && blocks.size() > 1)
			{
				bytes -= blocks.removeFirst().pcm.length;
			}
		}
	}

	void setByteBudget(long budget)
	{
		synchronized (lock)
		{
			byteBudget = budget > 0 ? budget : byteBudget;
			while (bytes > byteBudget && blocks.size() > 1)
			{
				bytes -= blocks.removeFirst().pcm.length;
			}
		}
	}

	/** Blocks timestamped in {@code (afterMs, untilMs]}, oldest first. */
	List<Block> drain(long afterMs, long untilMs)
	{
		List<Block> out = new ArrayList<>();
		synchronized (lock)
		{
			for (Block b : blocks)
			{
				if (b.timestampMs > afterMs && b.timestampMs <= untilMs)
				{
					out.add(b);
				}
			}
		}
		return out;
	}

	/** Every buffered sample in {@code [fromMs, toMs]}, concatenated. */
	byte[] window(long fromMs, long toMs)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		synchronized (lock)
		{
			for (Block b : blocks)
			{
				if (b.timestampMs >= fromMs && b.timestampMs <= toMs)
				{
					out.write(b.pcm, 0, b.pcm.length);
				}
			}
		}
		return out.toByteArray();
	}

	long bytes()
	{
		synchronized (lock)
		{
			return bytes;
		}
	}

	void reset()
	{
		synchronized (lock)
		{
			blocks.clear();
			bytes = 0;
		}
	}
}
