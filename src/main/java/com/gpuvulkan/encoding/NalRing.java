/*
 * Copyright (c) 2025, Dennis De Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.encoding;

import java.util.ArrayList;
import java.util.List;

/**
 * Circular buffer of encoded H.264 frames. Each slot holds an Annex-B NAL
 * payload tagged with the segment that produced it, so finalize can pick
 * frames from a single session and trim to an IDR.
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
