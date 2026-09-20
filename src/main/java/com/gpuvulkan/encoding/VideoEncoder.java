/*
 * Copyright (c) 2025, Dennis De Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.encoding;

import java.util.List;

/**
 * Strategy interface for encoded clip storage.
 */
public interface VideoEncoder
{
    /**
     * Initializes the encoder for recording at the given settings.
     *
     * @param fps target frames per second
     */
    void start(int fps);

    /**
     * Configures the rate-control bitrate target for the next recording. No-op
     * for encoders that do not use bitrate-based rate control.
     */
    default void configureBitrate(int averageBps, int peakBps) {}

    /**
     * Stops recording and releases resources. Buffer contents are cleared.
     */
    void stop();

    /**
     * Extracts frames from the buffer for the given time window and prepares
     * them for upload. Returns null if no frames are available.
     *
     * @param startTime start of clip window (milliseconds)
     * @param endTime end of clip window (milliseconds)
     * @return clip data ready for upload, or null if no frames available
     */
    ClipData finalizeClip(long startTime, long endTime);

    /**
     * Clears the buffer without stopping recording.
     */
    void reset();

    /**
     * Returns a human-readable name for this encoder (e.g., "mjpeg", "vulkan-h264").
     */
    String encoderName();

    /**
     * Encapsulates clip data ready for upload.
     */
    class ClipData
    {
        private final List<byte[]> frames;
        private final String contentType;
        private final long totalSize;

        public ClipData(List<byte[]> frames, String contentType, long totalSize)
        {
            this.frames = frames;
            this.contentType = contentType;
            this.totalSize = totalSize;
        }

        /** Encoded payloads; H.264 returns a single MP4 byte array. */
        public List<byte[]> getFrames() { return frames; }

        /** MIME type for the encoded payload. */
        public String getContentType() { return contentType; }

        /** Total byte size of all frames combined */
        public long getTotalSize() { return totalSize; }
    }
}
