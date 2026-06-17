/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved. See VulkanEncoder.java for full license text.
 */
package com.gpuvulkan.encoding;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Mux an H.264 Annex B bitstream to a faststart MP4 on disk. */
public final class LocalMp4Writer
{
    private static final int TIMESCALE = 90_000;

    private LocalMp4Writer() {}

    public static void write(byte[] h264, byte[] driverSpsPps,
                             int width, int height, int fps, Path out) throws IOException
    {
        java.nio.file.Files.write(out, toBytes(h264, driverSpsPps, width, height, fps));
    }

    public static byte[] toBytes(byte[] h264, byte[] driverSpsPps,
                                 int width, int height, int fps)
    {
        return toBytes(h264, driverSpsPps, width, height, fps, null);
    }

    public static byte[] toBytes(byte[] h264, byte[] driverSpsPps,
                                 int width, int height, int fps, long[] frameTimestampsMs)
    {
        Parsed p = analyze(h264);
        if (p.sps == null || p.pps == null)
        {
            if (driverSpsPps == null)
            {
                throw new IllegalStateException(
                    "bitstream missing SPS/PPS and driver returned no parameter blob");
            }
            byte[][] sp = extractSpsPpsFromDriverBlob(driverSpsPps);
            p.sps = sp[0];
            p.pps = sp[1];
        }

        int[] durations = computeDurations(p.frameStartIndices.length, fps, frameTimestampsMs);

        Mp4Writer.Built built = Mp4Writer.buildSamples(h264, p.frameStartIndices, p.keyframes, durations);
        return new Mp4Writer(width, height, TIMESCALE, p.sps, p.pps)
            .writeToBytes(built.avccBitstream, built.samples);
    }

    private static int[] computeDurations(int sampleCount, int fps, long[] timestampsMs)
    {
        int[] durations = new int[sampleCount];
        int fallbackTicks = Math.max(1, TIMESCALE / Math.max(1, fps));
        if (timestampsMs == null || timestampsMs.length != sampleCount || sampleCount < 2)
        {
            Arrays.fill(durations, fallbackTicks);
            return durations;
        }
        for (int i = 0; i < sampleCount - 1; i++)
        {
            long deltaMs = timestampsMs[i + 1] - timestampsMs[i];
            if (deltaMs <= 0)
            {
                durations[i] = fallbackTicks;
            }
            else
            {
                durations[i] = (int) Math.max(1, deltaMs * TIMESCALE / 1000L);
            }
        }
        durations[sampleCount - 1] = durations[sampleCount - 2];
        return durations;
    }

    static final class Parsed
    {
        byte[] sps;
        byte[] pps;
        int[] frameStartIndices;
        boolean[] keyframes;
        int nalCount;
    }

    /** Analyze an Annex B byte stream: extract SPS/PPS bodies and map slice NALs to frame boundaries. */
    static Parsed analyze(byte[] annexB)
    {
        List<AnnexBWriter.Nalu> nalus = AnnexBWriter.splitNalus(annexB);
        Parsed p = new Parsed();
        p.nalCount = nalus.size();

        List<Integer> frameStarts = new ArrayList<>();
        List<Boolean> kf = new ArrayList<>();

        for (int i = 0; i < nalus.size(); i++)
        {
            AnnexBWriter.Nalu nal = nalus.get(i);
            // Start-code scanning can produce false positives when a byte triple
            // inside a NAL body happens to look like 00 00 01. Skip NALs whose
            // header fails the forbidden_zero_bit check instead of dying on them.
            if (nal.length == 0 || (annexB[nal.offset] & 0x80) != 0)
            {
                continue;
            }
            int type = nal.nalUnitType(annexB);
            switch (type)
            {
                case AnnexBWriter.NAL_TYPE_SPS:
                    if (p.sps == null)
                    {
                        p.sps = slice(annexB, nal.offset, nal.length);
                    }
                    break;
                case AnnexBWriter.NAL_TYPE_PPS:
                    if (p.pps == null)
                    {
                        p.pps = slice(annexB, nal.offset, nal.length);
                    }
                    break;
                case AnnexBWriter.NAL_TYPE_IDR_SLICE:
                case AnnexBWriter.NAL_TYPE_NON_IDR_SLICE:
                    // A slice NAL marks the start of a new frame. Preceding parameter NALs
                    // (SPS/PPS/SEI) in this run belong to this same frame, so walk back.
                    int frameStart = i;
                    while (frameStart > 0)
                    {
                        AnnexBWriter.Nalu prev = nalus.get(frameStart - 1);
                        if (prev.length == 0 || (annexB[prev.offset] & 0x80) != 0) break;
                        int prevType = prev.nalUnitType(annexB);
                        if (prevType == AnnexBWriter.NAL_TYPE_SPS
                            || prevType == AnnexBWriter.NAL_TYPE_PPS
                            || prevType == AnnexBWriter.NAL_TYPE_SEI
                            || prevType == AnnexBWriter.NAL_TYPE_AUD)
                        {
                            if (!frameStarts.isEmpty() && frameStarts.get(frameStarts.size() - 1) >= frameStart - 1)
                            {
                                break;
                            }
                            frameStart--;
                        }
                        else
                        {
                            break;
                        }
                    }
                    frameStarts.add(frameStart);
                    kf.add(type == AnnexBWriter.NAL_TYPE_IDR_SLICE);
                    break;
                default:
                    break;
            }
        }

        p.frameStartIndices = new int[frameStarts.size()];
        p.keyframes = new boolean[frameStarts.size()];
        for (int i = 0; i < frameStarts.size(); i++)
        {
            p.frameStartIndices[i] = frameStarts.get(i);
            p.keyframes[i] = kf.get(i);
        }
        return p;
    }

    /**
     * Splits a driver-emitted parameter blob into its SPS and PPS NAL
     * bodies. The blob is in Annex B form (start codes between NALs);
     * scanning for nal_type bytes would false-positive on payload bytes
     * inside the SPS (e.g. {@code level_idc = 40} matches the PPS nal_type).
     */
    static byte[][] extractSpsPpsFromDriverBlob(byte[] blob)
    {
        List<AnnexBWriter.Nalu> nalus = AnnexBWriter.splitNalus(blob);
        if (nalus.isEmpty())
        {
            throw new IllegalStateException("driver SPS/PPS blob contains no NAL units");
        }
        byte[] sps = null, pps = null;
        for (AnnexBWriter.Nalu nal : nalus)
        {
            int type = nal.nalUnitType(blob);
            if (type == AnnexBWriter.NAL_TYPE_SPS && sps == null)
            {
                sps = Arrays.copyOfRange(blob, nal.offset, nal.offset + nal.length);
            }
            else if (type == AnnexBWriter.NAL_TYPE_PPS && pps == null)
            {
                pps = Arrays.copyOfRange(blob, nal.offset, nal.offset + nal.length);
            }
        }
        if (sps == null || pps == null)
        {
            throw new IllegalStateException("driver SPS/PPS blob missing SPS (" + (sps == null)
                + ") or PPS (" + (pps == null) + ") after parsing " + nalus.size() + " NALUs");
        }
        return new byte[][] { sps, pps };
    }

    private static byte[] slice(byte[] src, int offset, int length)
    {
        byte[] out = new byte[length];
        System.arraycopy(src, offset, out, 0, length);
        return out;
    }
}
