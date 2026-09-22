/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording.discord;

import com.gpuvulkan.GpuVulkanPluginConfig;
import com.gpuvulkan.recording.RecordingEntry;
import com.gpuvulkan.recording.RecordingService;
import com.gpuvulkan.recording.events.RecordingSaved;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.Subscribe;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Posts finished recordings to a Discord webhook.
 *
 * Written against the public recording events rather than the recorder's
 * internals, so it is the same thing a third-party plugin could do.
 */
@Slf4j
public final class DiscordUploader
{
	/** Metadata key carrying the outcome, so the panel can show it. */
	public static final String STATUS_KEY = "discord";

	public static final String SENT = "sent";
	public static final String TOO_LARGE = "too large";
	public static final String FAILED = "failed";
	public static final String UPLOADING = "uploading";
	public static final String QUEUED = "queued";
	public static final String SKIPPED = "skipped";
	public static final String NO_WEBHOOK = "no webhook";

	private static final MediaType MP4 = MediaType.parse("video/mp4");
	/** One post per this long; a boss that drops three times should not
	 *  produce three simultaneous uploads. */
	private static final long MIN_SPACING_MS = 5_000;
	/** Past this the backlog is stale rather than useful. */
	private static final int MAX_QUEUED = 16;
	/** discordapp.com is the legacy domain and still issued in older setups. */
	private static final String[] ALLOWED_HOSTS = {"discord.com", "discordapp.com"};

	private final RecordingService service;
	private final GpuVulkanPluginConfig config;
	private final OkHttpClient client;
	private final ScheduledExecutorService scheduler =
		Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "vkgpu-discord");
			t.setDaemon(true);
			return t;
		});
	private final Deque<Runnable> queued = new ArrayDeque<>();
	private boolean draining;
	private long lastDispatchMs;
	private final java.util.concurrent.atomic.AtomicBoolean warnedInvalid =
		new java.util.concurrent.atomic.AtomicBoolean();

	public DiscordUploader(RecordingService service, GpuVulkanPluginConfig config, OkHttpClient client)
	{
		this.service = service;
		this.config = config;
		// Defaults are tuned for API calls, not multi-megabyte uploads.
		this.client = client.newBuilder()
			.writeTimeout(10, TimeUnit.MINUTES)
			.readTimeout(2, TimeUnit.MINUTES)
			.build();
	}

	@Subscribe
	public void onRecordingSaved(RecordingSaved event)
	{
		if (config.discordAutoUpload())
		{
			push(event.entry(), event.path());
		}
	}

	/** True when a usable webhook is set, so the panel can offer the action. */
	public boolean configured()
	{
		return webhook() != null;
	}

	/** Posts one recording regardless of the automatic setting. */
	public void push(RecordingEntry entry, Path file)
	{
		HttpUrl url = webhook();
		if (url == null)
		{
			service.annotate(entry.id(), STATUS_KEY, NO_WEBHOOK);
			return;
		}

		long limit = (long) Math.max(1, config.discordMaxUploadMb()) * 1024L * 1024L;
		if (entry.sizeBytes() > limit)
		{
			// Long recordings routinely exceed any tier; saying so beats a
			// rejected upload after minutes of transfer.
			service.annotate(entry.id(), STATUS_KEY, TOO_LARGE);
			return;
		}
		if (!Files.isRegularFile(file))
		{
			return;
		}

		enqueue(entry, () -> dispatch(url, entry, file));
	}

	/**
	 * Spaces posts out rather than dropping them, so a burst still arrives in
	 * full. A backlog past {@link #MAX_QUEUED} is discarded: by the time it
	 * would send, nobody is waiting for it.
	 */
	private synchronized void enqueue(RecordingEntry entry, Runnable upload)
	{
		if (queued.size() >= MAX_QUEUED)
		{
			service.annotate(entry.id(), STATUS_KEY, SKIPPED);
			return;
		}
		queued.addLast(upload);
		service.annotate(entry.id(), STATUS_KEY, queued.size() > 1 ? QUEUED : UPLOADING);
		scheduleNext();
	}

	/** Caller holds the monitor. */
	private void scheduleNext()
	{
		if (draining || queued.isEmpty())
		{
			return;
		}
		draining = true;
		long since = System.currentTimeMillis() - lastDispatchMs;
		long delay = Math.max(0, MIN_SPACING_MS - since);
		scheduler.schedule(this::drainOne, delay, TimeUnit.MILLISECONDS);
	}

	private void drainOne()
	{
		Runnable next;
		synchronized (this)
		{
			draining = false;
			next = queued.pollFirst();
			lastDispatchMs = System.currentTimeMillis();
		}
		if (next != null)
		{
			next.run();
		}
		synchronized (this)
		{
			scheduleNext();
		}
	}

	public void close()
	{
		scheduler.shutdownNow();
		synchronized (this)
		{
			queued.clear();
		}
	}

	private void dispatch(HttpUrl url, RecordingEntry entry, Path file)
	{
		service.annotate(entry.id(), STATUS_KEY, UPLOADING);
		client.newCall(request(url, entry, file)).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Discord upload failed for {}", entry.id(), e);
				service.annotate(entry.id(), STATUS_KEY, FAILED);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response closed = response)
				{
					if (closed.isSuccessful())
					{
						service.annotate(entry.id(), STATUS_KEY, SENT);
					}
					else if (closed.code() == 413)
					{
						// The real ceiling is the server's boost tier, which
						// we cannot know until it refuses.
						service.annotate(entry.id(), STATUS_KEY, TOO_LARGE);
					}
					else
					{
						log.debug("Discord rejected {} with {}", entry.id(), closed.code());
						service.annotate(entry.id(), STATUS_KEY, FAILED);
					}
				}
			}
		});
	}

	private Request request(HttpUrl url, RecordingEntry entry, Path file)
	{
		MultipartBody body = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("content", describe(entry))
			.addFormDataPart("files[0]", entry.fileName(),
				RequestBody.create(MP4, file.toFile()))
			.build();
		return new Request.Builder().url(url).post(body).build();
	}

	private String describe(RecordingEntry entry)
	{
		String name = entry.description().isEmpty() ? entry.kindId() : entry.description();
		long seconds = entry.durationMs() / 1000L;
		return seconds > 0 ? name + " (" + seconds + "s)" : name;
	}

	/** Null when unset or not a usable webhook, which is how this stays opt-in. */
	private HttpUrl webhook()
	{
		String configured = config.discordWebhookUrl();
		if (configured == null || configured.trim().isEmpty())
		{
			return null;
		}
		HttpUrl url = parseWebhook(configured);
		if (url == null && warnedInvalid.compareAndSet(false, true))
		{
			// Once: configured() is called for every card on every refresh.
			log.warn("Discord webhook is not a Discord webhook URL; nothing will be posted");
		}
		else if (url != null)
		{
			warnedInvalid.set(false);
		}
		return url;
	}

	/**
	 * Accepts only an https Discord webhook endpoint. A recording is gameplay
	 * footage with a player name on it, so a mistyped or pasted-in host must
	 * not quietly become somewhere to send it.
	 *
	 * @return the parsed URL, or null when it is not one
	 */
	static HttpUrl parseWebhook(String configured)
	{
		HttpUrl url = configured == null ? null : HttpUrl.parse(configured.trim());
		if (url == null || !"https".equals(url.scheme()) || !isDiscordHost(url.host()))
		{
			return null;
		}
		List<String> segments = url.pathSegments();
		if (segments.size() < 4 || !"api".equals(segments.get(0)))
		{
			return null;
		}
		// /api/webhooks/{id}/{token} or /api/v10/webhooks/{id}/{token}
		int webhooks = "webhooks".equals(segments.get(1)) ? 1
			: segments.size() > 2 && "webhooks".equals(segments.get(2)) ? 2 : -1;
		if (webhooks < 0 || segments.size() < webhooks + 3)
		{
			return null;
		}
		return segments.get(webhooks + 1).isEmpty() || segments.get(webhooks + 2).isEmpty()
			? null : url;
	}

	private static boolean isDiscordHost(String host)
	{
		for (String allowed : ALLOWED_HOSTS)
		{
			if (allowed.equals(host) || host.endsWith("." + allowed))
			{
				return true;
			}
		}
		return false;
	}
}
