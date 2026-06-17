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

import java.util.ArrayList;
import java.util.List;

/**
 * Circular buffer of encoded H.264 frames. Replaces the JPEG ring on the
 * streaming-encode path: each slot holds an Annex-B NAL payload tagged with
 * the segment that produced it, so finalize can pick frames from a single
 * session and trim to an IDR.
 *
 * Package-private: {@link Slot} aliases the underlying {@code byte[]} as
 * a final field, which is safe inside this package but should not be
 * exposed externally.
 */
final class NalRing
{
    /** 600 slots covers 10s of pre-roll at up to 60fps; ~12-24MB at typical
     *  H.264 NAL sizes, well under the heap-pressure ceiling. */
    static final int DEFAULT_CAPACITY = 600;

    static final class Slot
    {
        final int segmentId;
        final long timestampMs;
        final boolean isIdr;
        final int frameNum;
        final boolean needsBlur;
        final byte[] nalUnits;

        Slot(int segmentId, long timestampMs, boolean isIdr, int frameNum,
             boolean needsBlur, byte[] nalUnits)
        {
            this.segmentId = segmentId;
            this.timestampMs = timestampMs;
            this.isIdr = isIdr;
            this.frameNum = frameNum;
            this.needsBlur = needsBlur;
            this.nalUnits = nalUnits;
        }
    }

    private final Slot[] slots;
    private final int capacity;
    private final Object lock = new Object();
    private int writeIndex;
    private int count;

    NalRing()
    {
        this(DEFAULT_CAPACITY);
    }

    NalRing(int capacity)
    {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.slots = new Slot[capacity];
    }

    void put(Slot slot)
    {
        if (slot == null) throw new IllegalArgumentException("slot must not be null");
        synchronized (lock)
        {
            slots[writeIndex] = slot;
            writeIndex = (writeIndex + 1) % capacity;
            if (count < capacity) count++;
        }
    }

    /** Slots in chronological order (oldest first). */
    List<Slot> snapshot()
    {
        synchronized (lock)
        {
            List<Slot> out = new ArrayList<>(count);
            int oldest = (writeIndex - count + capacity) % capacity;
            for (int i = 0; i < count; i++)
            {
                out.add(slots[(oldest + i) % capacity]);
            }
            return out;
        }
    }

    int size()
    {
        synchronized (lock)
        {
            return count;
        }
    }

    int capacity()
    {
        return capacity;
    }

    void reset()
    {
        synchronized (lock)
        {
            for (int i = 0; i < capacity; i++) slots[i] = null;
            writeIndex = 0;
            count = 0;
        }
    }
}
