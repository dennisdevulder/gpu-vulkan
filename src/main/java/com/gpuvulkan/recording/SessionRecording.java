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
package com.gpuvulkan.recording;

import com.gpuvulkan.encoding.EncodedFrame;
import com.gpuvulkan.encoding.EncodedSegmentInfo;
import com.gpuvulkan.encoding.EncoderRestart;
import com.gpuvulkan.encoding.NalSink;
import com.gpuvulkan.encoding.StreamingMp4Writer;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * One long recording: an encoder sink on one side, an mp4 on the other.
 *
 * {@link #frame} and {@link #segmentStarted} run under the encoder lock and
 * only append or set flags. Closing the file happens on the owner's executor
 * once the sink is detached, so nothing races the finalize.
 */
@Slf4j
final class SessionRecording implements NalSink, RecordingHandle
{
	interface Owner
	{
		void finalizeSession(SessionRecording session, StopReason reason);
	}

	enum StopReason
	{
		REQUESTED,
		MAX_LENGTH,
		SIZE_LIMIT,
		RESIZED,
		ENCODER_RESTART,
		SHUTDOWN,
		WRITE_FAILED
	}

	private final RecordingTarget target;
	private final String description;
	private final RecordingRequest request;
	private final Owner owner;
	private final long maxDurationMs;
	private final String continuationOf;
	private final AudioCapture audio;
	private long audioCursorMs = -1L;
	private final CompletableFuture<RecordingEntry> result = new CompletableFuture<>();
	private final AtomicBoolean stopping = new AtomicBoolean();

	private volatile StreamingMp4Writer writer;
	private volatile EncodedSegmentInfo segment;
	private volatile long firstFrameMs = -1L;
	private volatile long lastFrameMs = -1L;
	private volatile long frames;
	private volatile IOException writeFailure;

	SessionRecording(RecordingTarget target, RecordingRequest request, Owner owner,
		int maxSeconds, String continuationOf, AudioCapture audio)
	{
		this.continuationOf = continuationOf;
		this.audio = audio;
		this.target = target;
		this.request = request;
		this.description = request.description();
		this.owner = owner;
		this.maxDurationMs = TimeUnit.SECONDS.toMillis(Math.max(1, maxSeconds));
	}

	@Override
	public String id()
	{
		return target.id();
	}

	@Override
	public RecordingKind kind()
	{
		return target.kind();
	}

	@Override
	public String description()
	{
		return description;
	}

	@Override
	public long startedAt()
	{
		return target.triggeredAt();
	}

	@Override
	public boolean active()
	{
		return !stopping.get();
	}

	@Override
	public long bytesWritten()
	{
		StreamingMp4Writer w = writer;
		return w == null ? 0L : w.bytesWritten();
	}

	long frameCount()
	{
		return frames;
	}

	long elapsedMs()
	{
		return firstFrameMs < 0 ? 0L : lastFrameMs - firstFrameMs;
	}

	RecordingTarget target()
	{
		return target;
	}

	RecordingRequest request()
	{
		return request;
	}

	@Override
	public CompletableFuture<RecordingEntry> stop()
	{
		requestStop(StopReason.REQUESTED);
		return result;
	}

	@Override
	public void abort()
	{
		if (stopping.compareAndSet(false, true))
		{
			owner.finalizeSession(this, StopReason.WRITE_FAILED);
		}
	}

	@Override
	public CompletableFuture<RecordingEntry> result()
	{
		return result;
	}

	private void requestStop(StopReason reason)
	{
		if (stopping.compareAndSet(false, true))
		{
			owner.finalizeSession(this, reason);
		}
	}

	/** Called by the owner before attach, keeping the open off the encoder thread. */
	void open() throws IOException
	{
		if (writer != null)
		{
			return;
		}
		StreamingMp4Writer opened = new StreamingMp4Writer(target.file());
		if (audio != null && audio.running())
		{
			opened.audioTrack(audio.sampleRate(), audio.channels(), 16);
		}
		writer = opened;
	}

	@Override
	public void segmentStarted(EncodedSegmentInfo info)
	{
		StreamingMp4Writer w = writer;
		if (w == null)
		{
			return;
		}
		if (segment == null)
		{
			segment = info;
			w.segment(info);
			return;
		}
		if (info.sourceWidth != segment.sourceWidth || info.sourceHeight != segment.sourceHeight)
		{
			// SPS/PPS bind to a coded extent, so a resize needs its own track.
			requestStop(StopReason.RESIZED);
		}
	}

	@Override
	public void frame(EncodedFrame frame)
	{
		StreamingMp4Writer w = writer;
		if (w == null || stopping.get())
		{
			return;
		}
		try
		{
			if (!w.write(frame))
			{
				requestStop(StopReason.SIZE_LIMIT);
				return;
			}
		}
		catch (IOException e)
		{
			writeFailure = e;
			requestStop(StopReason.WRITE_FAILED);
			return;
		}

		frames++;
		lastFrameMs = frame.timestampMs;
		if (firstFrameMs < 0)
		{
			firstFrameMs = frame.timestampMs;
		}
		pumpAudio(w, frame.timestampMs);
		if (lastFrameMs - firstFrameMs >= maxDurationMs)
		{
			requestStop(StopReason.MAX_LENGTH);
		}
	}

	/**
	 * Writes the audio that belongs alongside the frame just written, taken
	 * from the same rolling buffer the video came from. Replayed pre-roll
	 * frames therefore pull their own recorded audio, not silence.
	 *
	 * Runs on the encoder thread with the video writes, which is what keeps
	 * the two interleaved and serialised.
	 */
	private void pumpAudio(StreamingMp4Writer w, long timestampMs)
	{
		if (audio == null || !w.hasAudioTrack())
		{
			return;
		}
		if (audioCursorMs < 0)
		{
			// Start one block behind the first frame so nothing is skipped.
			audioCursorMs = timestampMs - 1;
		}
		try
		{
			for (AudioRing.Block block : audio.drain(audioCursorMs, timestampMs))
			{
				w.writeAudio(block.pcm, 0, block.pcm.length);
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to write audio, continuing without it", e);
		}
		audioCursorMs = timestampMs;
	}

	@Override
	public void detached(Throwable cause)
	{
		if (stopping.compareAndSet(false, true))
		{
			// A restart (swapchain rebuild) continues into a new file; a real
			// shutdown ends the recording.
			owner.finalizeSession(this, cause instanceof EncoderRestart
				? StopReason.ENCODER_RESTART : StopReason.SHUTDOWN);
		}
	}

	/** Closes the file. Must run after detach. Null when nothing usable was
	 *  recorded, the file having been removed. */
	RecordingEntry finish(int fps) throws IOException
	{
		StreamingMp4Writer w = writer;
		if (w == null)
		{
			return null;
		}
		if (writeFailure != null)
		{
			w.abort();
			throw writeFailure;
		}
		if (!w.canFinish())
		{
			w.abort();
			return null;
		}
		long duration = w.durationMs();
		w.finish();

		EncodedSegmentInfo info = segment;
		return target.entry()
			.description(description)
			.durationMs(duration)
			.dimensions(info == null ? 0 : info.sourceWidth, info == null ? 0 : info.sourceHeight)
			.fps(info == null ? fps : info.fps)
			.session(true)
			.continuationOf(continuationOf)
			.metadata(request.metadata())
			.build();
	}

	void failed(Throwable cause)
	{
		result.completeExceptionally(cause);
	}

	void completed(RecordingEntry entry)
	{
		result.complete(entry);
	}
}
