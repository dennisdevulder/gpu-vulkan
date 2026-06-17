/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved. See VulkanEncoder.java for full license text.
 */
package com.gpuvulkan.encoding;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * H.264 Annex B wire-format writer. Pure Java, no Vulkan imports, no GPU required.
 *
 * Vulkan Video Encode writes raw NAL unit bodies into the bitstream buffer WITHOUT
 * Annex B start codes. For an elementary stream (`.h264`) or Annex B-in-MP4, the host
 * must prepend `0x00 0x00 0x00 0x01` before each NAL and emit SPS+PPS before every IDR
 * access unit (so decoders joining mid-stream can initialize).
 */
public final class AnnexBWriter
{
    static final byte[] START_CODE = { 0x00, 0x00, 0x00, 0x01 };

    public static final int NAL_TYPE_NON_IDR_SLICE = 1;
    public static final int NAL_TYPE_IDR_SLICE     = 5;
    public static final int NAL_TYPE_SEI           = 6;
    public static final int NAL_TYPE_SPS           = 7;
    public static final int NAL_TYPE_PPS           = 8;
    public static final int NAL_TYPE_AUD           = 9;

    private AnnexBWriter() {}

    /**
     * Write one NAL unit: start code followed by the raw NAL body from the encoder.
     * The body must already contain the NAL header byte and any emulation-prevention
     * bytes the driver inserted; do not strip or re-encode.
     *
     * Position of {@code nalBody} is preserved (a duplicate is used internally).
     */
    public static void writeNalu(OutputStream out, ByteBuffer nalBody) throws IOException
    {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(nalBody, "nalBody");
        if (!nalBody.hasRemaining())
        {
            return;
        }
        out.write(START_CODE);
        ByteBuffer view = nalBody.duplicate();
        if (view.hasArray())
        {
            out.write(view.array(), view.arrayOffset() + view.position(), view.remaining());
        }
        else
        {
            byte[] tmp = new byte[view.remaining()];
            view.get(tmp);
            out.write(tmp);
        }
    }

    /**
     * Write an IDR access unit: SPS, then PPS, then the IDR slice NAL body.
     * Each is wrapped with its own start code. Call this once per IDR frame.
     *
     * All three buffers must contain NAL bodies as returned by the driver / by
     * {@code vkGetEncodedVideoSessionParametersKHR} (no start codes, emulation-prevention
     * bytes already inserted).
     */
    public static void writeIdrAccessUnit(OutputStream out, ByteBuffer sps, ByteBuffer pps, ByteBuffer idrSlice)
        throws IOException
    {
        writeNalu(out, sps);
        writeNalu(out, pps);
        writeNalu(out, idrSlice);
    }

    /**
     * Return the {@code nal_unit_type} (bits 0-4 of the first byte) of a NAL body.
     * Throws IllegalArgumentException if the body is empty or has the forbidden zero
     * bit set.
     */
    public static int readNalUnitType(ByteBuffer nalBody)
    {
        Objects.requireNonNull(nalBody, "nalBody");
        if (!nalBody.hasRemaining())
        {
            throw new IllegalArgumentException("empty NAL body");
        }
        int header = nalBody.get(nalBody.position()) & 0xFF;
        if ((header & 0x80) != 0)
        {
            throw new IllegalArgumentException("forbidden_zero_bit set in NAL header: 0x" + Integer.toHexString(header));
        }
        return header & 0x1F;
    }

    /**
     * Iterate NAL units in an Annex B byte stream, returning each NAL body as a
     * slice of the original array (no copy). Start codes ({@code 0x000001} or
     * {@code 0x00000001}) separate units; the body excludes the start code.
     *
     * Returns a record for each NAL with {@code offset} and {@code length} into
     * {@code data}, in stream order.
     */
    public static java.util.List<Nalu> splitNalus(byte[] data)
    {
        Objects.requireNonNull(data, "data");
        java.util.ArrayList<Nalu> out = new java.util.ArrayList<>();
        int n = data.length;
        int i = 0;
        int bodyStart = -1;

        while (i < n)
        {
            int scLen = matchStartCode(data, i, n);
            if (scLen > 0)
            {
                if (bodyStart >= 0)
                {
                    out.add(new Nalu(bodyStart, i - bodyStart));
                }
                i += scLen;
                bodyStart = i;
            }
            else
            {
                i++;
            }
        }
        if (bodyStart >= 0 && bodyStart < n)
        {
            out.add(new Nalu(bodyStart, n - bodyStart));
        }
        return out;
    }

    private static int matchStartCode(byte[] data, int i, int n)
    {
        if (i + 4 <= n && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1)
        {
            return 4;
        }
        if (i + 3 <= n && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1)
        {
            return 3;
        }
        return 0;
    }

    /** A NAL unit body as a slice of a backing Annex B byte array. */
    public static final class Nalu
    {
        public final int offset;
        public final int length;

        public Nalu(int offset, int length)
        {
            this.offset = offset;
            this.length = length;
        }

        public int nalUnitType(byte[] data)
        {
            int header = data[offset] & 0xFF;
            if ((header & 0x80) != 0)
            {
                throw new IllegalArgumentException("forbidden_zero_bit set");
            }
            return header & 0x1F;
        }
    }
}
