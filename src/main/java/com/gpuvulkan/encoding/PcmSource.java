/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.encoding;

/**
 * Supplies the PCM that belongs to a span of video. Queried once the clip's
 * frame range is known, so the audio matches the IDR the clip actually starts
 * at rather than the requested window.
 */
public interface PcmSource
{
    int sampleRate();

    int channels();

    /** Interleaved 16-bit little-endian PCM for {@code [fromMs, toMs]}. */
    byte[] window(long fromMs, long toMs);
}
