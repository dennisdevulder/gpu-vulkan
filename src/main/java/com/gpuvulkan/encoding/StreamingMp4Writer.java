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

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Muxes access units into the destination mp4 as they arrive, holding one
 * metadata record per frame. Use it over {@link LocalMp4Writer} whenever the
 * whole bitstream will not fit in heap.
 *
 * Layout is {@code ftyp | mdat | moov}: moov cannot be sized until the last
 * frame is in, so these files are not faststart.
 *
 * Not thread-safe.
 */
@Slf4j
public final class StreamingMp4Writer implements Closeable
{
    private static final int TIMESCALE = 90_000;
    private static final int MDAT_TYPE = 0x6D646174;
    private static final int OUTPUT_BUFFER_BYTES = 256 * 1024;

    /** stco offsets and Sample.offsetInMdat are 32-bit, so mdat cannot pass 2 GiB. */
    static final long MAX_MDAT_BYTES = Integer.MAX_VALUE - (64L * 1024 * 1024);

    private static final class PendingSample
    {
        final int offsetInMdat;
        final int size;
        final boolean keyframe;
        final long timestampMs;

        PendingSample(int offsetInMdat, int size, boolean keyframe, long timestampMs)
        {
            this.offsetInMdat = offsetInMdat;
            this.size = size;
            this.keyframe = keyframe;
            this.timestampMs = timestampMs;
        }
    }

    private final Path path;
    private final List<PendingSample> samples = new ArrayList<>();
    private final ByteBuffer lengthPrefix = ByteBuffer.allocate(4);

    private int width;
    private int height;
    private int fps;
    private byte[] driverSpsPps;
    private Mp4Writer.AudioTrack audio;
    private FileChannel channel;
    private OutputStream out;
    private int ftypLength;
    private long mdatPayloadStart;
    private long mdatBytes;
    private byte[] sps;
    private byte[] pps;
    private boolean finished;

    /** Opens the file. Parameters arrive later, keeping this off the encoder thread. */
    public StreamingMp4Writer(Path path) throws IOException
    {
        this.path = path;
        open();
    }

    /**
     * Adds a PCM audio track. Call before the first {@link #writeAudio}; the
     * samples are interleaved into the same mdat as the video.
     */
    public void audioTrack(int sampleRate, int channels, int bitsPerSample)
    {
        if (audio == null)
        {
            audio = new Mp4Writer.AudioTrack(sampleRate, channels, bitsPerSample);
        }
    }

    public boolean hasAudio()
    {
        return audio != null && !audio.isEmpty();
    }

    /** True once {@link #audioTrack} has been declared, with or without samples. */
    public boolean hasAudioTrack()
    {
        return audio != null;
    }

    /** Writes {@code millis} of silence, used to align the track against
     *  video pre-roll the capture device was never asked for. */
    public void writeSilence(long millis) throws IOException
    {
        if (audio == null || millis <= 0)
        {
            return;
        }
        long frames = millis * audio.sampleRate / 1000L;
        byte[] zeros = new byte[audio.bytesPerFrame() * 1024];
        while (frames > 0)
        {
            int batch = (int) Math.min(frames, 1024);
            writeAudio(zeros, 0, batch * audio.bytesPerFrame());
            frames -= batch;
        }
    }

    /**
     * Appends a block of interleaved PCM. One block becomes one chunk in the
     * audio sample table, so callers should write reasonably sized blocks
     * rather than a frame at a time.
     */
    public void writeAudio(byte[] pcm, int offset, int length) throws IOException
    {
        if (finished || audio == null || length <= 0)
        {
            return;
        }
        int frames = length / audio.bytesPerFrame();
        if (frames <= 0)
        {
            return;
        }
        int bytes = frames * audio.bytesPerFrame();
        if (mdatBytes + bytes > MAX_MDAT_BYTES)
        {
            return;
        }
        audio.addChunk(mdatBytes, frames);
        out.write(pcm, offset, bytes);
        mdatBytes += bytes;
    }

    /** Required before {@link #finish}. First call wins: a track cannot change
     *  coded extent mid-stream. */
    public void segment(EncodedSegmentInfo info)
    {
        if (info == null || width > 0)
        {
            return;
        }
        this.width = info.sourceWidth;
        this.height = info.sourceHeight;
        this.fps = info.fps;
        this.driverSpsPps = info.spsPps;
    }

    private void open() throws IOException
    {
        Path parent = path.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        channel = FileChannel.open(path, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        out = new BufferedOutputStream(Channels.newOutputStream(channel), OUTPUT_BUFFER_BYTES);

        byte[] ftyp = Mp4Writer.buildFtyp();
        ftypLength = ftyp.length;
        out.write(ftyp);

        // Zero until finish patches it, which is how isIncomplete spots a crash.
        ByteBuffer header = ByteBuffer.allocate(8);
        header.putInt(0);
        header.putInt(MDAT_TYPE);
        out.write(header.array());
        mdatPayloadStart = ftypLength + 8L;
    }

    public Path path()
    {
        return path;
    }

    public int frameCount()
    {
        return samples.size();
    }

    public long bytesWritten()
    {
        return mdatPayloadStart + mdatBytes;
    }

    public long durationMs()
    {
        if (samples.size() < 2)
        {
            return samples.isEmpty() ? 0L : 1000L / Math.max(1, fps > 0 ? fps : 30);
        }
        return samples.get(samples.size() - 1).timestampMs - samples.get(0).timestampMs;
    }

    /** True once the file is decodable. */
    public boolean hasKeyframe()
    {
        return sps != null && pps != null;
    }

    /** True when {@link #finish} can resolve parameter sets, in-band or from
     *  the driver blob. */
    public boolean canFinish()
    {
        return !samples.isEmpty() && (hasKeyframe() || driverSpsPps != null);
    }

    /**
     * Appends one access unit.
     *
     * @return false when mdat is full and the caller should roll over; the
     *         frame was not written.
     */
    public boolean write(EncodedFrame frame) throws IOException
    {
        if (finished)
        {
            throw new IllegalStateException("writer already finished");
        }
        if (frame == null || frame.nalUnits == null || frame.nalUnits.length == 0)
        {
            return true;
        }
        if (mdatBytes + frame.nalUnits.length + 64 > MAX_MDAT_BYTES)
        {
            return false;
        }

        byte[] data = frame.nalUnits;
        int sampleStart = (int) mdatBytes;
        int sampleSize = 0;

        // Scanned, not split into a list: this runs on every frame of a session.
        int n = data.length;
        int i = 0;
        int bodyStart = -1;
        while (i < n)
        {
            int startCode = AnnexBWriter.matchStartCode(data, i, n);
            if (startCode > 0)
            {
                if (bodyStart >= 0)
                {
                    sampleSize += appendNal(data, bodyStart, i - bodyStart);
                }
                i += startCode;
                bodyStart = i;
            }
            else
            {
                i++;
            }
        }
        if (bodyStart >= 0 && bodyStart < n)
        {
            sampleSize += appendNal(data, bodyStart, n - bodyStart);
        }

        if (sampleSize == 0)
        {
            // Parameter-set-only access unit; nothing to index.
            return true;
        }
        mdatBytes += sampleSize;
        samples.add(new PendingSample(sampleStart, sampleSize, frame.idr, frame.timestampMs));
        return true;
    }

    /** Appends one NAL with its AVCC length prefix, diverting SPS/PPS to avcC. */
    private int appendNal(byte[] data, int offset, int length) throws IOException
    {
        if (length == 0 || (data[offset] & 0x80) != 0)
        {
            // False-positive start code inside a NAL body.
            return 0;
        }
        int type = data[offset] & 0x1F;
        if (type == AnnexBWriter.NAL_TYPE_SPS)
        {
            if (sps == null)
            {
                sps = slice(data, offset, length);
            }
            return 0;
        }
        if (type == AnnexBWriter.NAL_TYPE_PPS)
        {
            if (pps == null)
            {
                pps = slice(data, offset, length);
            }
            return 0;
        }
        lengthPrefix.clear();
        lengthPrefix.putInt(length);
        out.write(lengthPrefix.array(), 0, 4);
        out.write(data, offset, length);
        return 4 + length;
    }

    /** @throws IOException when nothing decodable was written */
    public void finish() throws IOException
    {
        if (finished)
        {
            return;
        }
        finished = true;
        out.flush();

        if (samples.isEmpty())
        {
            throw new IOException("no frames were written");
        }
        if (width <= 0 || height <= 0)
        {
            throw new IOException("encode parameters were never supplied");
        }
        resolveParameterSets();

        long mdatBoxSize = 8L + mdatBytes;
        ByteBuffer patch = ByteBuffer.allocate(4);
        patch.putInt((int) mdatBoxSize);
        patch.flip();
        channel.write(patch, ftypLength);

        channel.position(mdatPayloadStart + mdatBytes);
        byte[] moov = buildMoov();
        channel.write(ByteBuffer.wrap(moov));
        channel.force(false);
        close();
    }

    private void resolveParameterSets() throws IOException
    {
        if (sps != null && pps != null)
        {
            return;
        }
        if (driverSpsPps == null)
        {
            throw new IOException("bitstream carried no SPS/PPS and the driver supplied none");
        }
        byte[][] fromDriver = LocalMp4Writer.extractSpsPpsFromDriverBlob(driverSpsPps);
        if (sps == null)
        {
            sps = fromDriver[0];
        }
        if (pps == null)
        {
            pps = fromDriver[1];
        }
        if (sps == null || pps == null)
        {
            throw new IOException("could not resolve SPS/PPS for the recording");
        }
    }

    private byte[] buildMoov()
    {
        int count = samples.size();
        long[] timestamps = new long[count];
        for (int i = 0; i < count; i++)
        {
            timestamps[i] = samples.get(i).timestampMs;
        }
        int[] durations = LocalMp4Writer.computeDurations(count, fps, timestamps);

        List<Mp4Writer.Sample> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            PendingSample s = samples.get(i);
            out.add(new Mp4Writer.Sample(s.offsetInMdat, s.size, s.keyframe, durations[i]));
        }
        return new Mp4Writer(width, height, TIMESCALE, sps, pps)
            .buildMoov(out, audio, (int) mdatPayloadStart);
    }

    /** Closes and deletes the file. */
    public void abort()
    {
        finished = true;
        closeQuietly();
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException e)
        {
            log.debug("Failed to delete aborted recording {}", path, e);
        }
    }

    @Override
    public void close() throws IOException
    {
        if (out != null)
        {
            out.flush();
            out = null;
        }
        if (channel != null)
        {
            channel.close();
            channel = null;
        }
    }

    private void closeQuietly()
    {
        try
        {
            close();
        }
        catch (IOException e)
        {
            log.debug("Failed to close {}", path, e);
        }
    }

    /** True for a file started but never finished: mdat size is still zero. */
    public static boolean isIncomplete(Path file)
    {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ))
        {
            if (ch.size() < 16)
            {
                return true;
            }
            ByteBuffer head = ByteBuffer.allocate(16);
            ch.read(head, 0);
            head.flip();
            int ftypSize = head.getInt(0);
            if (ftypSize <= 0 || ftypSize + 8 > ch.size())
            {
                return false;
            }
            ByteBuffer mdat = ByteBuffer.allocate(8);
            ch.read(mdat, ftypSize);
            mdat.flip();
            return mdat.getInt(0) == 0 && mdat.getInt(4) == MDAT_TYPE;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    private static byte[] slice(byte[] src, int offset, int length)
    {
        byte[] out = new byte[length];
        System.arraycopy(src, offset, out, 0, length);
        return out;
    }

}
