/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved. See VulkanEncoder.java for full license text.
 */
package com.gpuvulkan.encoding;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal faststart MP4 muxer for a single H.264/AVC video track.
 *
 * Input: complete H.264 bitstream emitted by the GPU encoder (Annex B or AVCC),
 *        plus SPS and PPS NAL bodies, plus per-frame metadata (keyframe flags,
 *        presentation timestamps). Everything sits in the ~5-7 MB H.264 output
 *        range, so we build the entire file in memory and write once.
 *
 * Output layout: {@code ftyp | moov | mdat}. {@code moov} before {@code mdat}
 * is mandatory for browser/Discord/Twitter in-place playback ("faststart").
 *
 * Design constraints:
 * - Zero CPU pressure: integer math + a single streamed write. No transcode.
 * - Bounded heap: frame metadata is O(samples); bitstream buffers are already
 *   heap-resident post burst encode.
 * - One sample per chunk (stsc single entry), 32-bit chunk offsets (stco). Good
 *   enough for clips up to ~4 GiB; clip target is tens of MB.
 */
public final class Mp4Writer
{
    /** Per-sample metadata. Units of {@code durationTicks} are in the media timescale. */
    public static final class Sample
    {
        public final int offsetInMdat;   // relative to first AVCC sample
        public final int size;           // AVCC size (4-byte length prefix + NAL body)
        public final boolean keyframe;
        public final int durationTicks;

        public Sample(int offsetInMdat, int size, boolean keyframe, int durationTicks)
        {
            this.offsetInMdat = offsetInMdat;
            this.size = size;
            this.keyframe = keyframe;
            this.durationTicks = durationTicks;
        }
    }

    /**
     * PCM audio track state. One MP4 sample is one audio frame, so the tables
     * are kept in constant-size / run-length form rather than per-frame
     * records: 48 kHz for ten minutes would otherwise be 28M objects.
     */
    public static final class AudioTrack
    {
        public final int sampleRate;
        public final int channels;
        public final int bitsPerSample;
        /** {offsetInMdat, frameCount} per written block. */
        private final List<long[]> chunks = new ArrayList<>();
        private long totalFrames;

        public AudioTrack(int sampleRate, int channels, int bitsPerSample)
        {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
        }

        public void addChunk(long offsetInMdat, long frameCount)
        {
            if (frameCount <= 0)
            {
                return;
            }
            chunks.add(new long[]{offsetInMdat, frameCount});
            totalFrames += frameCount;
        }

        public int bytesPerFrame()
        {
            return channels * (bitsPerSample / 8);
        }

        public long totalFrames()
        {
            return totalFrames;
        }

        public boolean isEmpty()
        {
            return chunks.isEmpty();
        }
    }

    private final int width;
    private final int height;
    private final int timescale;
    private final byte[] sps;
    private final byte[] pps;

    public Mp4Writer(int width, int height, int timescale, byte[] sps, byte[] pps)
    {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("bad dimensions");
        if (timescale <= 0) throw new IllegalArgumentException("bad timescale");
        this.width = width;
        this.height = height;
        this.timescale = timescale;
        this.sps = Objects.requireNonNull(sps, "sps").clone();
        this.pps = Objects.requireNonNull(pps, "pps").clone();
        if (sps.length < 4) throw new IllegalArgumentException("SPS too short (need profile/level bytes)");
    }

    /**
     * Build AVCC samples for the given Annex B bitstream. Returns {@code (avccBytes, samples)}
     * where {@code avccBytes} is the concatenation of all non-parameter NALs with 4-byte
     * length prefixes, ready to place in {@code mdat}. SPS/PPS NALs are filtered
     * out; they live in {@code avcC}, not in sample data.
     */
    public static Built buildSamples(byte[] annexBBitstream, int[] frameStartIndices,
                                     boolean[] keyframeFlags, int[] durationsTicks)
    {
        Objects.requireNonNull(annexBBitstream, "annexBBitstream");
        Objects.requireNonNull(frameStartIndices, "frameStartIndices");
        if (frameStartIndices.length != keyframeFlags.length || frameStartIndices.length != durationsTicks.length)
        {
            throw new IllegalArgumentException("metadata arrays must match frame count");
        }
        List<AnnexBWriter.Nalu> nalus = AnnexBWriter.splitNalus(annexBBitstream);

        // Group NALs into frames: nalus[i] belongs to frame k if frameStartIndices[k] <= i < frameStartIndices[k+1].
        // We emit one AVCC sample per frame (concatenation of its non-parameter NALs with length prefixes).
        ByteBuffer out = ByteBuffer.allocate(annexBBitstream.length + 8 * nalus.size());
        List<Sample> samples = new ArrayList<>(frameStartIndices.length);

        for (int f = 0; f < frameStartIndices.length; f++)
        {
            int from = frameStartIndices[f];
            int to = (f + 1 < frameStartIndices.length) ? frameStartIndices[f + 1] : nalus.size();
            int sampleStart = out.position();
            for (int i = from; i < to; i++)
            {
                AnnexBWriter.Nalu nalu = nalus.get(i);
                if (nalu.length == 0 || (annexBBitstream[nalu.offset] & 0x80) != 0)
                {
                    // False-positive start code inside a NAL body; ignore.
                    continue;
                }
                int type = nalu.nalUnitType(annexBBitstream);
                if (type == AnnexBWriter.NAL_TYPE_SPS || type == AnnexBWriter.NAL_TYPE_PPS)
                {
                    continue;
                }
                out.putInt(nalu.length);
                out.put(annexBBitstream, nalu.offset, nalu.length);
            }
            int sampleSize = out.position() - sampleStart;
            if (sampleSize == 0)
            {
                throw new IllegalStateException("frame " + f + " produced no sample data");
            }
            samples.add(new Sample(sampleStart, sampleSize, keyframeFlags[f], durationsTicks[f]));
        }

        byte[] avcc = new byte[out.position()];
        System.arraycopy(out.array(), 0, avcc, 0, avcc.length);
        return new Built(avcc, samples);
    }

    public static final class Built
    {
        public final byte[] avccBitstream;
        public final List<Sample> samples;

        Built(byte[] avccBitstream, List<Sample> samples)
        {
            this.avccBitstream = avccBitstream;
            this.samples = samples;
        }
    }

    /** Write the MP4 file to {@code path}. Overwrites any existing file. */
    public void writeTo(Path path, byte[] avccBitstream, List<Sample> samples) throws IOException
    {
        Objects.requireNonNull(path, "path");
        byte[] ftyp = buildFtyp();
        byte[] moov = buildMoov(samples, /* mdatPayloadStart */ 0); // fixup offsets below once mdat position is known
        int headerSize = ftyp.length + moov.length;
        int mdatPayloadStart = headerSize + 8; // past mdat's own size+type
        moov = buildMoov(samples, mdatPayloadStart);
        int mdatBoxSize = 8 + avccBitstream.length;

        try (FileChannel ch = FileChannel.open(path,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
        {
            ch.write(ByteBuffer.wrap(ftyp));
            ch.write(ByteBuffer.wrap(moov));
            ByteBuffer mdat = ByteBuffer.allocate(8);
            mdat.putInt(mdatBoxSize);
            mdat.putInt(0x6D646174); // 'mdat'
            mdat.flip();
            ch.write(mdat);
            ch.write(ByteBuffer.wrap(avccBitstream));
        }
    }

    /** Convenience: also exposes the final MP4 as a byte[] (tests and small clips). */
    public byte[] writeToBytes(byte[] avccBitstream, List<Sample> samples)
    {
        return writeToBytes(avccBitstream, samples, null, 0, 0);
    }

    /**
     * Faststart MP4 with an optional PCM track. Audio follows the video in a
     * single mdat, so the layout stays ftyp | moov | mdat.
     */
    public byte[] writeToBytes(byte[] avccBitstream, List<Sample> samples,
                               byte[] pcm, int sampleRate, int channels)
    {
        AudioTrack audio = null;
        if (pcm != null && pcm.length > 0 && sampleRate > 0 && channels > 0)
        {
            audio = new AudioTrack(sampleRate, channels, 16);
            audio.addChunk(avccBitstream.length, pcm.length / audio.bytesPerFrame());
        }
        int pcmBytes = audio == null ? 0 : (int) audio.totalFrames() * audio.bytesPerFrame();

        byte[] ftyp = buildFtyp();
        byte[] moovProbe = buildMoov(samples, audio, 0);
        int mdatPayloadStart = ftyp.length + moovProbe.length + 8;
        byte[] moov = buildMoov(samples, audio, mdatPayloadStart);
        int mdatBoxSize = 8 + avccBitstream.length + pcmBytes;
        int total = ftyp.length + moov.length + mdatBoxSize;

        ByteBuffer buf = ByteBuffer.allocate(total);
        buf.put(ftyp);
        buf.put(moov);
        buf.putInt(mdatBoxSize);
        buf.putInt(0x6D646174);
        buf.put(avccBitstream);
        if (pcmBytes > 0)
        {
            buf.put(pcm, 0, pcmBytes);
        }
        return buf.array();
    }

    /** Package-private so StreamingMp4Writer can emit the same header. */
    static byte[] buildFtyp()
    {
        // ftyp: major_brand='isom', minor_version=512, compatible_brands=['isom','iso2','avc1','mp41']
        BoxBuilder b = new BoxBuilder("ftyp");
        b.writeFourCC("isom");
        b.writeInt(512);
        b.writeFourCC("isom");
        b.writeFourCC("iso2");
        b.writeFourCC("avc1");
        b.writeFourCC("mp41");
        // ISO/IEC 23003-5; without it players may skip a raw PCM audio track.
        b.writeFourCC("iso6");
        return b.build();
    }

    /** Package-private: StreamingMp4Writer appends moov after a streamed mdat. */
    byte[] buildMoov(List<Sample> samples, int mdatPayloadStart)
    {
        return buildMoov(samples, null, mdatPayloadStart);
    }

    byte[] buildMoov(List<Sample> samples, AudioTrack audio, int mdatPayloadStart)
    {
        long videoDuration = 0;
        for (Sample s : samples) videoDuration += s.durationTicks;

        // mvhd duration is in movie timescale and covers the longest track.
        long movieDuration = videoDuration;
        if (audio != null && !audio.isEmpty())
        {
            movieDuration = Math.max(movieDuration,
                audio.totalFrames() * timescale / Math.max(1, audio.sampleRate));
        }

        BoxBuilder moov = new BoxBuilder("moov");
        moov.writeBytes(buildMvhd(movieDuration, audio != null && !audio.isEmpty() ? 3 : 2));
        moov.writeBytes(buildTrak(samples, videoDuration, mdatPayloadStart));
        if (audio != null && !audio.isEmpty())
        {
            moov.writeBytes(buildAudioTrak(audio, mdatPayloadStart));
        }
        return moov.build();
    }

    private byte[] buildMvhd(long duration, int nextTrackId)
    {
        BoxBuilder b = new BoxBuilder("mvhd");
        b.writeInt(0);                          // version + flags
        b.writeInt(0);                          // creation_time
        b.writeInt(0);                          // modification_time
        b.writeInt(timescale);                  // timescale
        b.writeInt((int) duration);             // duration
        b.writeInt(0x00010000);                 // rate 1.0
        b.writeShort((short) 0x0100);           // volume 1.0
        b.writeShort((short) 0);                // reserved
        b.writeInt(0); b.writeInt(0);           // reserved[2]
        writeUnityMatrix(b);                    // 9x int32 matrix
        for (int i = 0; i < 6; i++) b.writeInt(0); // pre_defined[6]
        b.writeInt(nextTrackId);                // next_track_ID
        return b.build();
    }

    private byte[] buildTrak(List<Sample> samples, long totalDuration, int mdatPayloadStart)
    {
        BoxBuilder trak = new BoxBuilder("trak");
        trak.writeBytes(buildTkhd(totalDuration, 1, false));
        trak.writeBytes(buildMdia(samples, totalDuration, mdatPayloadStart));
        return trak.build();
    }

    private byte[] buildTkhd(long duration, int trackId, boolean audio)
    {
        BoxBuilder b = new BoxBuilder("tkhd");
        b.writeInt(0x0000000F);                 // version=0, flags: enabled|in_movie|in_preview|in_poster
        b.writeInt(0);                          // creation_time
        b.writeInt(0);                          // modification_time
        b.writeInt(trackId);                    // track_ID
        b.writeInt(0);                          // reserved
        b.writeInt((int) duration);             // duration
        b.writeInt(0); b.writeInt(0);           // reserved[2]
        b.writeShort((short) 0);                // layer
        b.writeShort((short) 0);                // alternate_group
        b.writeShort((short) (audio ? 0x0100 : 0)); // volume 1.0 for audio
        b.writeShort((short) 0);                // reserved
        writeUnityMatrix(b);
        b.writeInt(audio ? 0 : width << 16);    // width 16.16 fixed
        b.writeInt(audio ? 0 : height << 16);   // height
        return b.build();
    }

    private byte[] buildMdia(List<Sample> samples, long duration, int mdatPayloadStart)
    {
        BoxBuilder mdia = new BoxBuilder("mdia");
        mdia.writeBytes(buildMdhd(duration));
        mdia.writeBytes(buildHdlr());
        mdia.writeBytes(buildMinf(samples, mdatPayloadStart));
        return mdia.build();
    }

    private byte[] buildMdhd(long duration)
    {
        return buildMdhd(duration, timescale);
    }

    private byte[] buildMdhd(long duration, int mediaTimescale)
    {
        BoxBuilder b = new BoxBuilder("mdhd");
        b.writeInt(0);                          // version + flags
        b.writeInt(0);                          // creation
        b.writeInt(0);                          // modification
        b.writeInt(mediaTimescale);
        b.writeInt((int) duration);
        b.writeShort((short) 0x55C4);           // 'und' packed language
        b.writeShort((short) 0);                // pre_defined
        return b.build();
    }

    private byte[] buildHdlr()
    {
        return buildHdlr("vide", "VideoHandler");
    }

    private byte[] buildHdlr(String handler, String name)
    {
        BoxBuilder b = new BoxBuilder("hdlr");
        b.writeInt(0);                          // version + flags
        b.writeInt(0);                          // pre_defined
        b.writeFourCC(handler);
        b.writeInt(0); b.writeInt(0); b.writeInt(0); // reserved[3]
        byte[] ascii = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        b.writeBytes(ascii);
        b.writeByte((byte) 0);
        return b.build();
    }

    private byte[] buildMinf(List<Sample> samples, int mdatPayloadStart)
    {
        BoxBuilder minf = new BoxBuilder("minf");
        minf.writeBytes(buildVmhd());
        minf.writeBytes(buildDinf());
        minf.writeBytes(buildStbl(samples, mdatPayloadStart));
        return minf.build();
    }

    private byte[] buildVmhd()
    {
        BoxBuilder b = new BoxBuilder("vmhd");
        b.writeInt(1);                          // version=0, flags=1
        b.writeShort((short) 0);                // graphicsmode
        b.writeShort((short) 0); b.writeShort((short) 0); b.writeShort((short) 0); // opcolor
        return b.build();
    }

    private byte[] buildDinf()
    {
        BoxBuilder dinf = new BoxBuilder("dinf");
        BoxBuilder dref = new BoxBuilder("dref");
        dref.writeInt(0);                       // version + flags
        dref.writeInt(1);                       // entry_count
        BoxBuilder url = new BoxBuilder("url ");
        url.writeInt(1);                        // flags: self-contained
        dref.writeBytes(url.build());
        dinf.writeBytes(dref.build());
        return dinf.build();
    }

    private byte[] buildStbl(List<Sample> samples, int mdatPayloadStart)
    {
        BoxBuilder stbl = new BoxBuilder("stbl");
        stbl.writeBytes(buildStsd());
        stbl.writeBytes(buildStts(samples));
        stbl.writeBytes(buildStss(samples));
        stbl.writeBytes(buildStsc(samples.size()));
        stbl.writeBytes(buildStsz(samples));
        stbl.writeBytes(buildStco(samples, mdatPayloadStart));
        return stbl.build();
    }

    private byte[] buildStsd()
    {
        BoxBuilder stsd = new BoxBuilder("stsd");
        stsd.writeInt(0);                       // version + flags
        stsd.writeInt(1);                       // entry_count

        BoxBuilder avc1 = new BoxBuilder("avc1");
        for (int i = 0; i < 6; i++) avc1.writeByte((byte) 0); // reserved
        avc1.writeShort((short) 1);             // data_reference_index
        avc1.writeShort((short) 0); avc1.writeShort((short) 0); // pre_defined, reserved
        avc1.writeInt(0); avc1.writeInt(0); avc1.writeInt(0); // pre_defined[3]
        avc1.writeShort((short) width);
        avc1.writeShort((short) height);
        avc1.writeInt(0x00480000);              // horizresolution 72
        avc1.writeInt(0x00480000);              // vertresolution 72
        avc1.writeInt(0);                       // reserved
        avc1.writeShort((short) 1);             // frame_count
        byte[] compressor = new byte[32];       // compressorname[32], pascal-style
        avc1.writeBytes(compressor);
        avc1.writeShort((short) 0x0018);        // depth
        avc1.writeShort((short) -1);            // pre_defined
        avc1.writeBytes(buildAvcC());
        avc1.writeBytes(buildColr());
        stsd.writeBytes(avc1.build());
        return stsd.build();
    }

    // nclx colr: redundant with the SPS VUI but written as belt-and-suspenders.
    // matrix=6 (BT.601), full_range=0 to match the NV12 shader output.
    private static byte[] buildColr()
    {
        BoxBuilder b = new BoxBuilder("colr");
        b.writeFourCC("nclx");
        b.writeShort((short) 2);      // colour_primaries unspecified
        b.writeShort((short) 2);      // transfer_characteristics unspecified
        b.writeShort((short) 6);      // matrix_coefficients = BT.601
        b.writeByte((byte) 0x00);     // full_range_flag=0, reserved=0
        return b.build();
    }

    private byte[] buildAvcC()
    {
        // ISO/IEC 14496-15 section 5.2.4.1 AVCDecoderConfigurationRecord
        BoxBuilder b = new BoxBuilder("avcC");
        b.writeByte((byte) 1);                          // configurationVersion
        b.writeByte(sps[1]);                            // AVCProfileIndication
        b.writeByte(sps[2]);                            // profile_compatibility
        b.writeByte(sps[3]);                            // AVCLevelIndication
        b.writeByte((byte) 0xFF);                       // reserved(6)=111111b | lengthSizeMinusOne(2)=11
        b.writeByte((byte) 0xE1);                       // reserved(3)=111b | numOfSequenceParameterSets(5)=1
        b.writeShort((short) sps.length);
        b.writeBytes(sps);
        b.writeByte((byte) 1);                          // numOfPictureParameterSets
        b.writeShort((short) pps.length);
        b.writeBytes(pps);
        return b.build();
    }

    private byte[] buildStts(List<Sample> samples)
    {
        // Run-length encode equal-duration runs
        List<int[]> runs = new ArrayList<>();
        int count = 0;
        int curDur = -1;
        for (Sample s : samples)
        {
            if (s.durationTicks == curDur)
            {
                count++;
            }
            else
            {
                if (count > 0) runs.add(new int[] { count, curDur });
                count = 1;
                curDur = s.durationTicks;
            }
        }
        if (count > 0) runs.add(new int[] { count, curDur });

        BoxBuilder b = new BoxBuilder("stts");
        b.writeInt(0);                          // version + flags
        b.writeInt(runs.size());
        for (int[] r : runs)
        {
            b.writeInt(r[0]);
            b.writeInt(r[1]);
        }
        return b.build();
    }

    private byte[] buildStss(List<Sample> samples)
    {
        List<Integer> keyframes = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++)
        {
            if (samples.get(i).keyframe) keyframes.add(i + 1);
        }
        BoxBuilder b = new BoxBuilder("stss");
        b.writeInt(0);
        b.writeInt(keyframes.size());
        for (int k : keyframes) b.writeInt(k);
        return b.build();
    }

    private byte[] buildStsc(int sampleCount)
    {
        BoxBuilder b = new BoxBuilder("stsc");
        b.writeInt(0);                          // version + flags
        b.writeInt(1);                          // entry_count: single run, 1 sample per chunk
        b.writeInt(1);                          // first_chunk
        b.writeInt(1);                          // samples_per_chunk
        b.writeInt(1);                          // sample_description_index
        return b.build();
    }

    private byte[] buildStsz(List<Sample> samples)
    {
        BoxBuilder b = new BoxBuilder("stsz");
        b.writeInt(0);                          // version + flags
        b.writeInt(0);                          // sample_size=0 (per-sample sizes follow)
        b.writeInt(samples.size());
        for (Sample s : samples) b.writeInt(s.size);
        return b.build();
    }

    private byte[] buildStco(List<Sample> samples, int mdatPayloadStart)
    {
        BoxBuilder b = new BoxBuilder("stco");
        b.writeInt(0);
        b.writeInt(samples.size());
        for (Sample s : samples) b.writeInt(mdatPayloadStart + s.offsetInMdat);
        return b.build();
    }

    private byte[] buildAudioTrak(AudioTrack audio, int mdatPayloadStart)
    {
        long duration = audio.totalFrames();
        BoxBuilder trak = new BoxBuilder("trak");
        trak.writeBytes(buildTkhd(duration * timescale / Math.max(1, audio.sampleRate), 2, true));

        BoxBuilder mdia = new BoxBuilder("mdia");
        mdia.writeBytes(buildMdhd(duration, audio.sampleRate));
        mdia.writeBytes(buildHdlr("soun", "SoundHandler"));

        BoxBuilder minf = new BoxBuilder("minf");
        minf.writeBytes(buildSmhd());
        minf.writeBytes(buildDinf());
        minf.writeBytes(buildAudioStbl(audio, mdatPayloadStart));
        mdia.writeBytes(minf.build());

        trak.writeBytes(mdia.build());
        return trak.build();
    }

    private byte[] buildSmhd()
    {
        BoxBuilder b = new BoxBuilder("smhd");
        b.writeInt(0);                          // version + flags
        b.writeShort((short) 0);                // balance
        b.writeShort((short) 0);                // reserved
        return b.build();
    }

    private byte[] buildAudioStbl(AudioTrack audio, int mdatPayloadStart)
    {
        BoxBuilder stbl = new BoxBuilder("stbl");
        stbl.writeBytes(buildAudioStsd(audio));
        stbl.writeBytes(buildAudioStts(audio));
        stbl.writeBytes(buildAudioStsc(audio));
        stbl.writeBytes(buildAudioStsz(audio));
        stbl.writeBytes(buildAudioStco(audio, mdatPayloadStart));
        return stbl.build();
    }

    /** ISO/IEC 23003-5 ipcm sample entry: an AudioSampleEntry plus pcmC. */
    private byte[] buildAudioStsd(AudioTrack audio)
    {
        BoxBuilder stsd = new BoxBuilder("stsd");
        stsd.writeInt(0);                       // version + flags
        stsd.writeInt(1);                       // entry_count

        BoxBuilder ipcm = new BoxBuilder("ipcm");
        for (int i = 0; i < 6; i++) ipcm.writeByte((byte) 0); // reserved
        ipcm.writeShort((short) 1);             // data_reference_index
        ipcm.writeInt(0); ipcm.writeInt(0);     // reserved[2]
        ipcm.writeShort((short) audio.channels);
        ipcm.writeShort((short) audio.bitsPerSample);
        ipcm.writeShort((short) 0);             // pre_defined
        ipcm.writeShort((short) 0);             // reserved
        ipcm.writeInt(audio.sampleRate << 16);  // samplerate 16.16

        BoxBuilder pcmC = new BoxBuilder("pcmC");
        pcmC.writeInt(0);                       // version + flags
        pcmC.writeByte((byte) 1);               // format_flags bit0: little-endian
        pcmC.writeByte((byte) audio.bitsPerSample);
        ipcm.writeBytes(pcmC.build());

        stsd.writeBytes(ipcm.build());
        return stsd.build();
    }

    /** Every audio frame is one tick at the media timescale, so one run covers all. */
    private byte[] buildAudioStts(AudioTrack audio)
    {
        BoxBuilder b = new BoxBuilder("stts");
        b.writeInt(0);
        b.writeInt(1);
        b.writeInt((int) audio.totalFrames());
        b.writeInt(1);
        return b.build();
    }

    private byte[] buildAudioStsc(AudioTrack audio)
    {
        List<int[]> runs = new ArrayList<>();
        for (int i = 0; i < audio.chunks.size(); i++)
        {
            int perChunk = (int) audio.chunks.get(i)[1];
            if (runs.isEmpty() || runs.get(runs.size() - 1)[1] != perChunk)
            {
                runs.add(new int[]{i + 1, perChunk});
            }
        }
        BoxBuilder b = new BoxBuilder("stsc");
        b.writeInt(0);
        b.writeInt(runs.size());
        for (int[] r : runs)
        {
            b.writeInt(r[0]);                   // first_chunk
            b.writeInt(r[1]);                   // samples_per_chunk
            b.writeInt(1);                      // sample_description_index
        }
        return b.build();
    }

    private byte[] buildAudioStsz(AudioTrack audio)
    {
        BoxBuilder b = new BoxBuilder("stsz");
        b.writeInt(0);
        b.writeInt(audio.bytesPerFrame());      // constant sample size
        b.writeInt((int) audio.totalFrames());
        return b.build();
    }

    private byte[] buildAudioStco(AudioTrack audio, int mdatPayloadStart)
    {
        BoxBuilder b = new BoxBuilder("stco");
        b.writeInt(0);
        b.writeInt(audio.chunks.size());
        for (long[] c : audio.chunks)
        {
            b.writeInt((int) (mdatPayloadStart + c[0]));
        }
        return b.build();
    }

    private static void writeUnityMatrix(BoxBuilder b)
    {
        int[] m = { 0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000 };
        for (int v : m) b.writeInt(v);
    }

    private static final class BoxBuilder
    {
        private final String fourcc;
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

        BoxBuilder(String fourcc)
        {
            if (fourcc.length() != 4) throw new IllegalArgumentException("fourcc must be 4 chars");
            this.fourcc = fourcc;
        }

        void writeByte(byte v) { buf.write(v & 0xFF); }
        void writeShort(short v)
        {
            buf.write((v >>> 8) & 0xFF);
            buf.write(v & 0xFF);
        }
        void writeInt(int v)
        {
            buf.write((v >>> 24) & 0xFF);
            buf.write((v >>> 16) & 0xFF);
            buf.write((v >>>  8) & 0xFF);
            buf.write(v & 0xFF);
        }
        void writeFourCC(String s)
        {
            if (s.length() != 4) throw new IllegalArgumentException();
            for (int i = 0; i < 4; i++) buf.write(s.charAt(i) & 0x7F);
        }
        void writeBytes(byte[] b)
        {
            try { buf.write(b); } catch (IOException e) { throw new AssertionError(e); }
        }

        byte[] build()
        {
            byte[] body = buf.toByteArray();
            int size = body.length + 8;
            ByteBuffer out = ByteBuffer.allocate(size);
            out.putInt(size);
            for (int i = 0; i < 4; i++) out.put((byte) fourcc.charAt(i));
            out.put(body);
            return out.array();
        }
    }
}
