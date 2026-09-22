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
 * Captures from a {@code javax.sound.sampled} input line. RuneLite exposes no
 * PCM, so this records the system; on Linux a monitor source is routed in.
 */
@Slf4j
public final class SystemAudioSource implements AudioSource
{
	private static final int SAMPLE_RATE = 48_000;
	private static final int CHANNELS = 2;
	private static final int BITS = 16;

	private final String deviceName;
	private TargetDataLine line;
	private int capturedChannels = CHANNELS;
	private volatile float level;
	private byte[] monoScratch = new byte[0];

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
		// Headset mics are usually mono-only; whatever opens is upmixed on read.
		AudioFormat stereo = new AudioFormat(SAMPLE_RATE, BITS, CHANNELS, true, false);
		AudioFormat mono = new AudioFormat(SAMPLE_RATE, BITS, 1, true, false);

		AudioFormat format = stereo;
		TargetDataLine opened = open(new DataLine.Info(TargetDataLine.class, stereo), stereo);
		if (opened == null)
		{
			format = mono;
			opened = open(new DataLine.Info(TargetDataLine.class, mono), mono);
		}
		if (opened == null)
		{
			throw new IllegalStateException("no capture line for " + describe());
		}

		capturedChannels = format.getChannels();
		// A second of slack against a slow frame.
		opened.open(format, SAMPLE_RATE * capturedChannels * (BITS / 8));
		opened.start();
		line = opened;
	}

	private String describe()
	{
		return deviceName == null || deviceName.isEmpty() ? "default" : deviceName;
	}

	/** Null when this device cannot provide the format; the caller retries mono. */
	private TargetDataLine open(DataLine.Info info, AudioFormat format) throws Exception
	{
		if (deviceName != null && !deviceName.isEmpty() && !"default".equals(deviceName))
		{
			for (Mixer.Info mi : AudioSystem.getMixerInfo())
			{
				// The mixer name carries a card number that moves across replugs.
				if (!deviceName.equals(stableName(mi)) && !deviceName.equals(mi.getName()))
				{
					continue;
				}
				Mixer mixer = AudioSystem.getMixer(mi);
				return mixer.isLineSupported(info) ? (TargetDataLine) mixer.getLine(info) : null;
			}
			log.warn("Audio device '{}' is unavailable, using the default instead. Available: {}",
				deviceName, captureDevices());
		}
		try
		{
			return AudioSystem.getTargetDataLine(format);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/** The product name from the description; the mixer name is only a card slot. */
	static String stableName(Mixer.Info mi)
	{
		String desc = mi.getDescription();
		if (desc == null || desc.isEmpty())
		{
			return mi.getName();
		}
		String prefix = "Direct Audio Device: ";
		if (desc.startsWith(prefix))
		{
			desc = desc.substring(prefix.length());
		}
		// ALSA repeats the subdevice name; keep the first two distinct parts.
		String[] parts = desc.split(",\\s*");
		if (parts.length >= 2 && !parts[0].equals(parts[1]))
		{
			return parts[0] + ", " + parts[1];
		}
		return parts[0];
	}

	@Override
	public int read(byte[] buffer) throws Exception
	{
		TargetDataLine active = line;
		if (active == null)
		{
			return -1;
		}
		if (capturedChannels == CHANNELS)
		{
			int available = Math.min(active.available(), buffer.length);
			// Whole frames only: a split frame would swap the channels from here on.
			available -= available % (CHANNELS * (BITS / 8));
			if (available <= 0)
			{
				return 0;
			}
			int read = active.read(buffer, 0, available);
			level = AudioLevels.peak(buffer, read);
			return read;
		}

		// Mono device: read half as many bytes and duplicate each sample.
		int wanted = buffer.length / 2;
		int available = Math.min(active.available(), wanted);
		available -= available % (BITS / 8);
		if (available <= 0)
		{
			return 0;
		}
		if (monoScratch.length < available)
		{
			monoScratch = new byte[available];
		}
		int read = active.read(monoScratch, 0, available);
		for (int i = 0, o = 0; i + 1 < read; i += 2, o += 4)
		{
			buffer[o] = monoScratch[i];
			buffer[o + 1] = monoScratch[i + 1];
			buffer[o + 2] = monoScratch[i];
			buffer[o + 3] = monoScratch[i + 1];
		}
		level = AudioLevels.peak(monoScratch, read);
		return read * 2;
	}

	@Override
	public float level()
	{
		return level;
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

	/** Capture-capable mixers by stable name, mono included. */
	public static List<String> captureDevices()
	{
		DataLine.Info stereo = new DataLine.Info(TargetDataLine.class,
			new AudioFormat(SAMPLE_RATE, BITS, CHANNELS, true, false));
		DataLine.Info mono = new DataLine.Info(TargetDataLine.class,
			new AudioFormat(SAMPLE_RATE, BITS, 1, true, false));
		List<String> names = new ArrayList<>();
		for (Mixer.Info mi : AudioSystem.getMixerInfo())
		{
			if (mi.getName().endsWith("[default]"))
			{
				continue;
			}
			Mixer mixer = AudioSystem.getMixer(mi);
			if (mixer.isLineSupported(stereo) || mixer.isLineSupported(mono))
			{
				String name = stableName(mi);
				if (!names.contains(name))
				{
					names.add(name);
				}
			}
		}
		return names;
	}
}
