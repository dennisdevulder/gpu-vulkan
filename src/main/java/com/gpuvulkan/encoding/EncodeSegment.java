/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
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
