/*
 * Copyright (c) 2026, Dennis de Vulder
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
 * One encoded H.264 access unit, Annex-B, tagged with its session.
 *
 * {@code nalUnits} is shared with the ring and every sink. Never mutate it.
 */
public final class EncodedFrame
{
    public final int segmentId;
    public final long timestampMs;
    public final boolean idr;
    public final int frameNum;
    public final boolean needsBlur;
    public final byte[] nalUnits;

    public EncodedFrame(int segmentId, long timestampMs, boolean idr, int frameNum,
                        boolean needsBlur, byte[] nalUnits)
    {
        this.segmentId = segmentId;
        this.timestampMs = timestampMs;
        this.idr = idr;
        this.frameNum = frameNum;
        this.needsBlur = needsBlur;
        this.nalUnits = nalUnits;
    }

    public int size()
    {
        return nalUnits == null ? 0 : nalUnits.length;
    }
}
