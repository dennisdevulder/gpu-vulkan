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

	private final AudioSource source;
	private final AudioRing ring;
	private volatile boolean running;
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
				ring.put(System.currentTimeMillis(), buffer, read);
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
		}
		source.close();
		ring.reset();
	}
}
