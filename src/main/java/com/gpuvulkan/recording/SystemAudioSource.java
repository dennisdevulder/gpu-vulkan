/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording;

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import lombok.extern.slf4j.Slf4j;

/**
 * Captures from a {@code javax.sound.sampled} input line.
 *
 * This records the system, not the game: RuneLite exposes sound effect ids,
 * never PCM, so there is nothing game-specific to capture. On Linux the line
 * appears in the PipeWire graph and a monitor source can be routed into it;
 * elsewhere it needs a loopback device to exist.
 */
@Slf4j
public final class SystemAudioSource implements AudioSource
{
	private static final int SAMPLE_RATE = 48_000;
	private static final int CHANNELS = 2;
	private static final int BITS = 16;

	private final String deviceName;
	private TargetDataLine line;

	public SystemAudioSource(String deviceName)
	{
		this.deviceName = deviceName;
	}

	@Override
	public int sampleRate()
	{
		return SAMPLE_RATE;
	}

	@Override
	public int channels()
	{
		return CHANNELS;
	}

	@Override
	public void start() throws Exception
	{
		AudioFormat format = new AudioFormat(SAMPLE_RATE, BITS, CHANNELS, true, false);
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
		TargetDataLine opened = open(info, format);
		// A second of slack: the recorder polls on its own cadence and must not
		// lose samples when a frame takes longer than expected.
		opened.open(format, SAMPLE_RATE * CHANNELS * (BITS / 8));
		opened.start();
		line = opened;
	}

	private TargetDataLine open(DataLine.Info info, AudioFormat format) throws Exception
	{
		if (deviceName != null && !deviceName.isEmpty() && !"default".equals(deviceName))
		{
			for (Mixer.Info mi : AudioSystem.getMixerInfo())
			{
				if (!deviceName.equals(mi.getName()))
				{
					continue;
				}
				Mixer mixer = AudioSystem.getMixer(mi);
				if (mixer.isLineSupported(info))
				{
					return (TargetDataLine) mixer.getLine(info);
				}
			}
			log.warn("Audio device '{}' is unavailable, using the default instead. Available: {}",
				deviceName, captureDevices());
		}
		return AudioSystem.getTargetDataLine(format);
	}

	@Override
	public int read(byte[] buffer) throws Exception
	{
		TargetDataLine active = line;
		if (active == null)
		{
			return -1;
		}
		int available = Math.min(active.available(), buffer.length);
		if (available <= 0)
		{
			return 0;
		}
		// Whole frames only: a split frame would swap the channels from here on.
		int frameSize = CHANNELS * (BITS / 8);
		available -= available % frameSize;
		return available <= 0 ? 0 : active.read(buffer, 0, available);
	}

	@Override
	public void close()
	{
		TargetDataLine active = line;
		line = null;
		if (active != null)
		{
			active.stop();
			active.close();
		}
	}

	/** Names of every mixer that can capture the recorder's format. */
	public static List<String> captureDevices()
	{
		AudioFormat format = new AudioFormat(SAMPLE_RATE, BITS, CHANNELS, true, false);
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
		List<String> names = new ArrayList<>();
		for (Mixer.Info mi : AudioSystem.getMixerInfo())
		{
			if (AudioSystem.getMixer(mi).isLineSupported(info))
			{
				names.add(mi.getName());
			}
		}
		return names;
	}
}
