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

import java.util.ArrayList;
import java.util.List;

/**
 * Circular buffer of encoded frames, bounded by frame count and by bytes.
 *
 * The byte budget is the binding one: 60s at 60fps and 12 Mbps is ~90 MB in a
 * ring sized purely by slots, against a ~712 MB client heap.
 */
final class NalRing implements NalSink
{
    /** 600 slots covers 10s of pre-roll at up to 60fps. */
    static final int DEFAULT_CAPACITY = 600;

    /** Overridden from config. */
    static final long DEFAULT_BYTE_BUDGET = 64L * 1024 * 1024;

    private final EncodedFrame[] slots;
    private final int capacity;
    private final Object lock = new Object();
    private int writeIndex;
    private int count;
    private long bytes;
    private long byteBudget;
    private long droppedToBudget;

    NalRing()
    {
        this(DEFAULT_CAPACITY, DEFAULT_BYTE_BUDGET);
    }

    NalRing(int capacity, long byteBudget)
    {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.slots = new EncodedFrame[capacity];
        this.byteBudget = byteBudget > 0 ? byteBudget : DEFAULT_BYTE_BUDGET;
    }

    @Override
    public void segmentStarted(EncodedSegmentInfo info)
    {
        // Frames carry their segment id and finalize filters on it.
    }

    @Override
    public void frame(EncodedFrame frame)
    {
        put(frame);
    }

    void put(EncodedFrame frame)
    {
        if (frame == null) throw new IllegalArgumentException("frame must not be null");
        synchronized (lock)
        {
            EncodedFrame replaced = slots[writeIndex];
            if (replaced != null)
            {
                bytes -= replaced.size();
            }
            slots[writeIndex] = frame;
            bytes += frame.size();
            writeIndex = (writeIndex + 1) % capacity;
            if (count < capacity) count++;
            evictToBudget();
        }
    }

    /** Caller holds {@code lock}. Keeps one frame: an oversized IDR still
     *  has to be saveable. */
    private void evictToBudget()
    {
        while (bytes > byteBudget && count > 1)
        {
            int oldest = (writeIndex - count + capacity) % capacity;
            EncodedFrame dropped = slots[oldest];
            slots[oldest] = null;
            if (dropped != null)
            {
                bytes -= dropped.size();
            }
            count--;
            droppedToBudget++;
        }
    }

    void setByteBudget(long budget)
    {
        synchronized (lock)
        {
            this.byteBudget = budget > 0 ? budget : DEFAULT_BYTE_BUDGET;
            evictToBudget();
        }
    }

    long byteBudget()
    {
        synchronized (lock)
        {
            return byteBudget;
        }
    }

    /** Frames in chronological order (oldest first). */
    List<EncodedFrame> snapshot()
    {
        synchronized (lock)
        {
            List<EncodedFrame> out = new ArrayList<>(count);
            int oldest = (writeIndex - count + capacity) % capacity;
            for (int i = 0; i < count; i++)
            {
                EncodedFrame frame = slots[(oldest + i) % capacity];
                if (frame != null)
                {
                    out.add(frame);
                }
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

    long bytes()
    {
        synchronized (lock)
        {
            return bytes;
        }
    }

    long droppedToBudget()
    {
        synchronized (lock)
        {
            return droppedToBudget;
        }
    }

    int capacity()
    {
        return capacity;
    }

    /** Resized copy, newest frames kept. Must not drop footage: clips grow the
     *  ring at trigger time, and the pre-roll is what would be lost. */
    NalRing resized(int newCapacity)
    {
        synchronized (lock)
        {
            NalRing out = new NalRing(newCapacity, byteBudget);
            List<EncodedFrame> retained = snapshot();
            int from = Math.max(0, retained.size() - newCapacity);
            for (int i = from; i < retained.size(); i++)
            {
                out.put(retained.get(i));
            }
            return out;
        }
    }

    void reset()
    {
        synchronized (lock)
        {
            for (int i = 0; i < capacity; i++) slots[i] = null;
            writeIndex = 0;
            count = 0;
            bytes = 0;
        }
    }
}
