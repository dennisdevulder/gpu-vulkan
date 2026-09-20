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

import com.gpuvulkan.GpuVulkanPluginConfig;
import com.gpuvulkan.encoding.StreamingMp4Writer;
import com.gpuvulkan.encoding.StreamingVulkanEncoder;
import com.gpuvulkan.encoding.VideoEncoder;
import com.gpuvulkan.recording.events.RecordingDeleted;
import com.gpuvulkan.recording.events.RecordingFailed;
import com.gpuvulkan.recording.events.RecordingProgress;
import com.gpuvulkan.recording.events.RecordingSaved;
import com.gpuvulkan.recording.events.RecordingStarted;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;

/**
 * Turns requests into files: clips from the rolling buffer, sessions streamed
 * to disk. All file work is on one executor thread, so a trigger can fire
 * straight from a game event handler.
 */
@Slf4j
public final class VulkanRecordingService implements RecordingService, SessionRecording.Owner
{
	public static final int MAX_CLIP_SECONDS = 60;

	private final RecordingBackend backend;
	private final RecordingStore store;
	private final RecordingKindRegistry kinds;
	private final EventBus eventBus;
	private final GpuVulkanPluginConfig config;
	private final Consumer<String> announce;

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r ->
	{
		Thread t = new Thread(r, "vkgpu-recordings");
		t.setDaemon(true);
		return t;
	});
	private final List<SessionRecording> sessions = new CopyOnWriteArrayList<>();

	public VulkanRecordingService(RecordingBackend backend, RecordingStore store,
		RecordingKindRegistry kinds, EventBus eventBus, GpuVulkanPluginConfig config,
		Consumer<String> announce)
	{
		this.backend = backend;
		this.store = store;
		this.kinds = kinds;
		this.eventBus = eventBus;
		this.config = config;
		this.announce = announce == null ? message -> { } : announce;
	}

	public void start()
	{
		executor.execute(() ->
		{
			sweepIncompleteFiles();
			store.scan();
		});
		executor.scheduleAtFixedRate(this::publishProgress, 1, 1, TimeUnit.SECONDS);
	}

	public void shutdown()
	{
		stopAllSessions();
		executor.shutdown();
		try
		{
			if (!executor.awaitTermination(5, TimeUnit.SECONDS))
			{
				executor.shutdownNow();
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			executor.shutdownNow();
		}
	}

	public void stopAllSessions()
	{
		for (SessionRecording session : sessions)
		{
			session.stop();
		}
	}

	@Override
	public boolean available()
	{
		return config.recordingsEnabled() && backend.available();
	}

	@Override
	public String unavailableReason()
	{
		if (!config.recordingsEnabled())
		{
			return "turn on Recordings > Save recordings";
		}
		return backend.unavailableReason();
	}

	@Override
	public RecordingKindRegistry kinds()
	{
		return kinds;
	}

	@Override
	public List<RecordingEntry> index()
	{
		return store.list();
	}

	@Override
	public Optional<RecordingEntry> find(String id)
	{
		return store.find(id);
	}

	@Override
	public boolean delete(String id)
	{
		Optional<RecordingEntry> entry = store.find(id);
		if (!entry.isPresent() || !store.delete(id))
		{
			return false;
		}
		eventBus.post(new RecordingDeleted(entry.get(), RecordingDeleted.Reason.USER));
		return true;
	}

	@Override
	public boolean setPinned(String id, boolean pinned)
	{
		return store.setPinned(id, pinned).map(e -> e.pinned() == pinned).orElse(false);
	}

	@Override
	public List<RecordingHandle> activeSessions()
	{
		return new ArrayList<>(sessions);
	}

	// ------------------------------------------------------------------- clips

	@Override
	public CompletableFuture<RecordingEntry> clip(RecordingRequest request)
	{
		String reason = unavailableReason();
		if (reason != null)
		{
			return failed(request, new IllegalStateException(reason));
		}
		StreamingVulkanEncoder encoder = backend.encoder();
		if (encoder == null)
		{
			return failed(request, new IllegalStateException("encoder is not running"));
		}

		long triggeredAt = System.currentTimeMillis();
		int pre = clamp(resolve(request.preRollSeconds(), config.inFlightEncodingBufferSeconds()),
			1, MAX_CLIP_SECONDS);
		int post = clamp(resolve(request.postRollSeconds(), config.inFlightEncodingPostWaitSeconds()),
			0, MAX_CLIP_SECONDS - pre);
		// Queued, not inline: growing the ring takes the encoder lock, which is
		// held across a frame encode, and triggers call this on the client thread.
		executor.execute(() -> backend.ensureBuffered(pre, post));

		CompletableFuture<RecordingEntry> future = new CompletableFuture<>();
		executor.schedule(() ->
		{
			try
			{
				future.complete(writeClip(encoder, request, triggeredAt, pre, post));
			}
			catch (Throwable t)
			{
				log.warn("Failed to save {} recording", request.kind().id(), t);
				eventBus.post(new RecordingFailed(null, request.kind(), request.description(), t));
				future.completeExceptionally(t);
			}
		}, post, TimeUnit.SECONDS);
		return future;
	}

	private RecordingEntry writeClip(StreamingVulkanEncoder encoder, RecordingRequest request,
		long triggeredAt, int pre, int post) throws IOException
	{
		long start = triggeredAt - TimeUnit.SECONDS.toMillis(pre);
		long end = triggeredAt + TimeUnit.SECONDS.toMillis(post);
		VideoEncoder.ClipData clip = encoder.finalizeClip(start, end);
		if (clip == null || clip.getFrames().isEmpty())
		{
			throw new IOException("no encoded frames were available for the requested window");
		}
		if (!"video/mp4".equals(clip.getContentType()))
		{
			throw new IOException("encoder produced unsupported content type " + clip.getContentType());
		}

		RecordingTarget target = store.allocate(request.kind(), request.description(), triggeredAt);
		byte[] bytes = clip.getFrames().get(0);
		Files.write(target.file(), bytes);

		RecordingEntry entry = target.entry()
			.description(request.description())
			.durationMs(TimeUnit.SECONDS.toMillis((long) pre + post))
			.dimensions(backend.frameWidth(), backend.frameHeight())
			.fps(backend.captureFps())
			.sizeBytes(bytes.length)
			.metadata(request.metadata())
			.build();
		return publishSaved(store.commit(entry), target.file());
	}

	// ---------------------------------------------------------------- sessions

	@Override
	public RecordingHandle session(RecordingRequest request)
	{
		return openSession(request, null);
	}

	private RecordingHandle openSession(RecordingRequest request, String continuationOf)
	{
		String reason = unavailableReason();
		if (reason == null && !config.recordingSessionsEnabled())
		{
			reason = "long recordings are disabled";
		}
		if (reason != null)
		{
			return new FailedHandle(request, new IllegalStateException(reason));
		}
		StreamingVulkanEncoder encoder = backend.encoder();
		if (encoder == null)
		{
			return new FailedHandle(request, new IllegalStateException("encoder is not running"));
		}

		long triggeredAt = System.currentTimeMillis();
		int pre = clamp(resolve(request.preRollSeconds(), config.inFlightEncodingBufferSeconds()),
			0, MAX_CLIP_SECONDS);
		int maxSeconds = clamp(resolve(request.maxSeconds(), config.recordingSessionMaxSeconds()),
			1, 3600);

		RecordingTarget target;
		try
		{
			target = store.allocate(request.kind(), request.description(), triggeredAt);
		}
		catch (IOException e)
		{
			return new FailedHandle(request, e);
		}

		SessionRecording session = new SessionRecording(target, request, this, maxSeconds,
			continuationOf, audioSource());
		sessions.add(session);
		// attachSink replays the pre-roll and goes live under the encoder lock:
		// no gap, no duplicated frame. Queued for the same reason as above; the
		// pre-roll covers the handover.
		long preRollFrom = triggeredAt - TimeUnit.SECONDS.toMillis(pre);
		executor.execute(() ->
		{
			try
			{
				session.open();
			}
			catch (IOException e)
			{
				log.warn("Failed to open recording {}", session.id(), e);
				session.abort();
				return;
			}
			encoder.attachSink(session, preRollFrom);
		});

		// Also enforced on wall clock: a session starved of frames must still close.
		executor.schedule(() -> session.stop(), maxSeconds + 5L, TimeUnit.SECONDS);

		eventBus.post(new RecordingStarted(session));
		return session;
	}

	@Override
	public void finalizeSession(SessionRecording session, SessionRecording.StopReason reason)
	{
		executor.execute(() ->
		{
			StreamingVulkanEncoder encoder = backend.encoder();
			if (encoder != null)
			{
				encoder.detachSink(session);
			}
			sessions.remove(session);
			try
			{
				RecordingEntry entry = session.finish(backend.captureFps());
				if (entry == null)
				{
					IllegalStateException empty = new IllegalStateException(
						"recording produced no decodable frames");
					session.failed(empty);
					eventBus.post(new RecordingFailed(session.id(), session.kind(),
						session.description(), empty));
					return;
				}
				RecordingEntry stored = publishSaved(store.commit(entry), session.target().file());
				session.completed(stored);

				if (reason == SessionRecording.StopReason.RESIZED
					|| reason == SessionRecording.StopReason.SIZE_LIMIT
					|| reason == SessionRecording.StopReason.ENCODER_RESTART)
				{
					openSession(session.request(), session.id());
				}
			}
			catch (Throwable t)
			{
				log.warn("Failed to finalise recording {}", session.id(), t);
				session.failed(t);
				eventBus.post(new RecordingFailed(session.id(), session.kind(),
					session.description(), t));
			}
		});
	}

	// ------------------------------------------------------------------ shared

	/** Null when audio is off, so the session records video only. */
	private AudioSource audioSource()
	{
		return config.recordingAudioEnabled()
			? new SystemAudioSource(config.recordingAudioDevice()) : null;
	}

	private RecordingEntry publishSaved(RecordingEntry entry, Path file)
	{
		eventBus.post(new RecordingSaved(entry, file));
		if (config.recordingChatFeedback())
		{
			announce.accept("Recording saved: " + file.getFileName());
		}
		enforceRetention(entry.id());
		return entry;
	}

	private void enforceRetention(String justSavedId)
	{
		long budget = Math.max(0, (long) config.recordingDiskBudgetMb()) * 1024L * 1024L;
		List<RecordingEntry> evicted = store.enforceRetention(budget,
			config.recordingRetentionDays(), java.util.Collections.singleton(justSavedId));
		for (RecordingEntry entry : evicted)
		{
			eventBus.post(new RecordingDeleted(entry, RecordingDeleted.Reason.RETENTION));
		}
	}

	private void publishProgress()
	{
		for (SessionRecording session : sessions)
		{
			if (session.active())
			{
				eventBus.post(new RecordingProgress(session, session.elapsedMs(),
					session.frameCount(), session.bytesWritten()));
			}
		}
	}

	/** Deletes mp4s left unfinished by a killed client. They have no moov and
	 *  cannot be repaired without a decoder. */
	private void sweepIncompleteFiles()
	{
		Path root = store.root();
		if (!Files.isDirectory(root))
		{
			return;
		}
		try (DirectoryStream<Path> folders = Files.newDirectoryStream(root, Files::isDirectory))
		{
			for (Path folder : folders)
			{
				try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.mp4"))
				{
					for (Path file : files)
					{
						if (StreamingMp4Writer.isIncomplete(file))
						{
							log.info("Removing incomplete recording {}", file);
							Files.deleteIfExists(file);
						}
					}
				}
			}
		}
		catch (IOException e)
		{
			log.debug("Failed to sweep incomplete recordings", e);
		}
	}

	private CompletableFuture<RecordingEntry> failed(RecordingRequest request, Throwable cause)
	{
		eventBus.post(new RecordingFailed(null, request.kind(), request.description(), cause));
		return CompletableFuture.failedFuture(cause);
	}

	private static int resolve(int requested, int configured)
	{
		return requested < 0 ? configured : requested;
	}

	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}

	/** Already-failed handle for a session that could not be opened. */
	private static final class FailedHandle implements RecordingHandle
	{
		private final RecordingRequest request;
		private final CompletableFuture<RecordingEntry> result = new CompletableFuture<>();

		FailedHandle(RecordingRequest request, Throwable cause)
		{
			this.request = request;
			this.result.completeExceptionally(cause);
		}

		@Override
		public String id()
		{
			return null;
		}

		@Override
		public RecordingKind kind()
		{
			return request.kind();
		}

		@Override
		public String description()
		{
			return request.description();
		}

		@Override
		public long startedAt()
		{
			return 0L;
		}

		@Override
		public boolean active()
		{
			return false;
		}

		@Override
		public long bytesWritten()
		{
			return 0L;
		}

		@Override
		public CompletableFuture<RecordingEntry> stop()
		{
			return result;
		}

		@Override
		public void abort()
		{
		}

		@Override
		public CompletableFuture<RecordingEntry> result()
		{
			return result;
		}
	}
}
