/*
 * Copyright (c) 2025, Dennis De Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.encoding;

/**
 * Metadata for one Vulkan video encode session. A new segment is created
 * whenever the source extent grows past the current coded extent, since
 * the SPS/PPS and DPB are bound to a fixed coded extent at session-create
 * time.
 *
 * Source vs coded dimensions: coded is padded to a multiple of 16 for H.264
 * macroblocks. SPS frame_cropping hides the padding at decode, so the MP4
 * track header reports source dimensions and players display them correctly.
 *
 * Package-private: {@code spsPps} aliases the driver-emitted byte[]. Safe
 * inside this package; not for external exposure.
 */
final class EncodeSegment
{
    final int id;
    final int sourceWidth;
    final int sourceHeight;
    final int codedWidth;
    final int codedHeight;
    final int fps;
    final byte[] spsPps;
    final long firstTimestampMs;

    EncodeSegment(int id, int sourceWidth, int sourceHeight,
                  int codedWidth, int codedHeight, int fps,
                  byte[] spsPps, long firstTimestampMs)
    {
        this.id = id;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.codedWidth = codedWidth;
        this.codedHeight = codedHeight;
        this.fps = fps;
        this.spsPps = spsPps;
        this.firstTimestampMs = firstTimestampMs;
    }
}
