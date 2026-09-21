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
	/** Substitute the default device when the named one is missing. Right for
	 *  the main capture; wrong for a microphone, where the default is usually
	 *  the output monitor and substituting it doubles the system audio. */
	private final boolean fallbackToDefault;
	private TargetDataLine line;
	/** Channels the device actually gave us; mono is upmixed on read. */
	private int capturedChannels = CHANNELS;
	private volatile float level;
	private byte[] monoScratch = new byte[0];

	public SystemAudioSource(String deviceName)
	{
		this(deviceName, true);
	}

	public SystemAudioSource(String deviceName, boolean fallbackToDefault)
	{
		this.deviceName = deviceName;
		this.fallbackToDefault = fallbackToDefault;
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
		// Headset microphones are usually mono-only, so stereo cannot be
		// assumed. Whatever opens is upmixed to stereo on read.
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
		// A second of slack: the recorder polls on its own cadence and must not
		// lose samples when a frame takes longer than expected.
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
				// Stable name first; the raw mixer name carries an ALSA card
				// number that changes across reboots and replugs.
				if (!deviceName.equals(stableName(mi)) && !deviceName.equals(mi.getName()))
				{
					continue;
				}
				Mixer mixer = AudioSystem.getMixer(mi);
				return mixer.isLineSupported(info) ? (TargetDataLine) mixer.getLine(info) : null;
			}
			if (!fallbackToDefault)
			{
				throw new IllegalStateException("audio device '" + deviceName
					+ "' is not present. Available: " + captureDevices());
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

	/**
	 * Identifies a device by what it is rather than where it is plugged in:
	 * the description names the product, the mixer name only its card slot.
	 */
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

	/**
	 * Mixers that can capture at all, by stable name. Mono counts: most
	 * headset microphones offer nothing else. The JVM's own "[default]" entry
	 * is skipped, since "default" already stands for it.
	 */
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
