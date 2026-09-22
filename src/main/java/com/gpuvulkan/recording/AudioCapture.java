/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the capture device and keeps a rolling PCM buffer, so a clip can reach
 * backwards for audio exactly as it reaches backwards for encoded frames.
 *
 * One owner of the device: sessions and clips both read the ring rather than
 * the line, so they never contend for it.
 */
@Slf4j
final class AudioCapture
{
	private static final int READ_BUFFER_BYTES = 16 * 1024;
	private static final int SILENCE_THRESHOLD = 16;
	private static final long SILENCE_REPORT_MS = 10_000;

	private final AudioSource source;
	private final AudioRing ring;
	private volatile boolean running;
	private volatile long lastSignalMs;
	private Thread pump;

	AudioCapture(AudioSource source, long byteBudget)
	{
		this.source = source;
		this.ring = new AudioRing(byteBudget);
	}

	int sampleRate()
	{
		return source.sampleRate();
	}

	int channels()
	{
		return source.channels();
	}

	int bytesPerFrame()
	{
		return source.channels() * 2;
	}

	boolean running()
	{
		return running;
	}

	float level()
	{
		return running ? source.level() : 0f;
	}

	/** @return false when the device could not be opened; recording continues without audio */
	boolean start()
	{
		if (running)
		{
			return true;
		}
		try
		{
			source.start();
		}
		catch (Exception e)
		{
			log.warn("Audio capture unavailable, recordings will have no sound", e);
			source.close();
			return false;
		}
		running = true;
		lastSignalMs = System.currentTimeMillis();
		pump = new Thread(this::pumpLoop, "vkgpu-audio");
		pump.setDaemon(true);
		pump.start();
		return true;
	}

	private void pumpLoop()
	{
		byte[] buffer = new byte[READ_BUFFER_BYTES];
		while (running)
		{
			try
			{
				int read = source.read(buffer);
				if (read < 0)
				{
					break;
				}
				if (read == 0)
				{
					// The line had nothing ready; sleeping a frame keeps this
					// thread off a spin without risking overrun on a 1s buffer.
					Thread.sleep(5);
					continue;
				}
				long now = System.currentTimeMillis();
				if (hasSignal(buffer, read))
				{
					lastSignalMs = now;
				}
				ring.put(now, buffer, read);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				break;
			}
			catch (Exception e)
			{
				log.warn("Audio capture stopped", e);
				break;
			}
		}
		running = false;
	}

	/**
	 * True when the line has carried nothing but zeroes for a while. A device
	 * that captures silence is indistinguishable from a working one until the
	 * recording is played back, so this is what the panel reports.
	 */
	boolean silent()
	{
		return running && System.currentTimeMillis() - lastSignalMs > SILENCE_REPORT_MS;
	}

	private static boolean hasSignal(byte[] buffer, int length)
	{
		for (int i = 0; i + 1 < length; i += 2)
		{
			// 16-bit LE; a couple of LSBs of dither should not count as signal.
			int sample = Math.abs((short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF)));
			if (sample > SILENCE_THRESHOLD)
			{
				return true;
			}
		}
		return false;
	}

	List<AudioRing.Block> drain(long afterMs, long untilMs)
	{
		return ring.drain(afterMs, untilMs);
	}

	byte[] window(long fromMs, long toMs)
	{
		return ring.window(fromMs, toMs);
	}

	void setByteBudget(long budget)
	{
		ring.setByteBudget(budget);
	}

	void stop()
	{
		running = false;
		Thread active = pump;
		pump = null;
		if (active != null)
		{
			active.interrupt();
			try
			{
				// Let a read in progress return before the line goes away
				// underneath it.
				active.join(500);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}
		source.close();
		ring.reset();
	}
}
