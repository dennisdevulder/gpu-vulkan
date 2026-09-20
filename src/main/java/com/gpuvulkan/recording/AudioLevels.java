/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording;

/** Peak level of a PCM block, for the panel's meters. */
final class AudioLevels
{
	private AudioLevels()
	{
	}

	/** Loudest sample in {@code [0, length)} as a fraction of full scale. */
	static float peak(byte[] pcm, int length)
	{
		int peak = 0;
		for (int i = 0; i + 1 < length; i += 2)
		{
			int sample = Math.abs((short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF)));
			if (sample > peak)
			{
				peak = sample;
			}
		}
		return peak / (float) Short.MAX_VALUE;
	}
}
