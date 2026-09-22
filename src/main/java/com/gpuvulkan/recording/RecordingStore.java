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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * On-disk library: {@code <root>/<kind>/<name>.mp4} with a JSON sidecar beside
 * each, indexed by scanning those sidecars. Posts no events; the service does.
 */
@Slf4j
public final class RecordingStore
{
	private static final String VIDEO_EXT = ".mp4";
	private static final String THUMB_EXT = ".png";
	/** Thumbnails live under the kind folder, not beside the videos. */
	private static final String THUMB_DIR = "thumbs";
	private static final String SIDECAR_EXT = ".json";

	private final Path root;
	private final RecordingKindRegistry kinds;
	private final Object lock = new Object();
	private final Map<String, RecordingEntry> entries = new LinkedHashMap<>();
	/** Names handed out by allocate but not yet on disk. */
	private final java.util.Set<String> claimed = new java.util.HashSet<>();

	public RecordingStore(Path root, RecordingKindRegistry kinds)
	{
		this.root = root;
		this.kinds = kinds;
	}

	public Path root()
	{
		return root;
	}

	/** Rebuilds the index. Sidecars whose video is gone are deleted. */
	public void scan()
	{
		Map<String, RecordingEntry> found = new LinkedHashMap<>();
		if (Files.isDirectory(root))
		{
			try (DirectoryStream<Path> folders = Files.newDirectoryStream(root, Files::isDirectory))
			{
				for (Path folder : folders)
				{
					scanFolder(folder, found);
				}
			}
			catch (IOException e)
			{
				log.warn("Failed to scan recordings root {}", root, e);
			}
		}

		List<RecordingEntry> sorted = new ArrayList<>(found.values());
		sorted.sort(Comparator.comparingLong(RecordingEntry::triggeredAt).reversed());
		synchronized (lock)
		{
			entries.clear();
			for (RecordingEntry entry : sorted)
			{
				entries.put(entry.id(), entry);
			}
		}
	}

	private void scanFolder(Path folder, Map<String, RecordingEntry> found)
	{
		sweepPendingThumbnails(folder.resolve(THUMB_DIR));
		relocateLooseThumbnails(folder);
		try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + SIDECAR_EXT))
		{
			for (Path sidecar : files)
			{
				RecordingEntry entry = RecordingCodec.read(sidecar);
				if (entry == null)
				{
					log.debug("Ignoring unreadable recording sidecar {}", sidecar);
					continue;
				}
				Path video = folder.resolve(entry.fileName());
				if (!Files.isRegularFile(video))
				{
					deleteQuietly(sidecar);
					continue;
				}
				// Trust the file: a session killed mid-write never updated its sidecar.
				long size = sizeOf(video);
				RecordingEntry indexed = size == entry.sizeBytes() ? entry : entry.withSize(size);
				if (indexed.folder() == null || indexed.folder().isEmpty())
				{
					indexed = indexed.toBuilder()
						.folder(folder.getFileName().toString()).build();
				}
				found.put(entry.id(), indexed);
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to scan recordings folder {}", folder, e);
		}
	}

	/** Reserves a destination, creating the folder but no files. */
	public synchronized RecordingTarget allocate(RecordingKind kind, String description,
		long triggeredAt) throws IOException
	{
		RecordingKind resolved = kind == null ? RecordingKindRegistry.GENERIC : kind;
		Path folder = root.resolve(resolved.folder());
		Files.createDirectories(folder);

		// Names are also claimed in memory: allocate creates no files, so two
		// allocations in the same second with the same description would
		// otherwise agree on a path and one would truncate the other.
		String base = baseName(triggeredAt, description);
		String candidate = base;
		for (int i = 2; Files.exists(folder.resolve(candidate + VIDEO_EXT))
			|| !claimed.add(resolved.folder() + "/" + candidate); i++)
		{
			candidate = base + "_" + i;
		}

		return new RecordingTarget(
			UUID.randomUUID().toString(),
			resolved,
			triggeredAt,
			candidate + VIDEO_EXT,
			candidate + THUMB_EXT,
			folder.resolve(candidate + VIDEO_EXT),
			folder.resolve(THUMB_DIR).resolve(candidate + THUMB_EXT),
			folder.resolve(candidate + SIDECAR_EXT));
	}

	/** Call once the mp4 is complete. */
	public RecordingEntry commit(RecordingEntry entry) throws IOException
	{
		synchronized (lock)
		{
			return commitLocked(entry);
		}
	}

	/** Caller holds {@code lock}. */
	private RecordingEntry commitLocked(RecordingEntry entry) throws IOException
	{
		RecordingEntry stored = entry;
		Path video = videoPath(entry);
		if (Files.isRegularFile(video))
		{
			long size = sizeOf(video);
			if (size != entry.sizeBytes())
			{
				stored = entry.withSize(size);
			}
		}
		RecordingCodec.write(sidecarPath(stored), stored);
		entries.put(stored.id(), stored);
		return stored;
	}

	/** Thumbnails written before they had their own directory. */
	private void relocateLooseThumbnails(Path folder)
	{
		try (DirectoryStream<Path> loose = Files.newDirectoryStream(folder, "*" + THUMB_EXT))
		{
			Path thumbs = folder.resolve(THUMB_DIR);
			for (Path path : loose)
			{
				Files.createDirectories(thumbs);
				Files.move(path, thumbs.resolve(path.getFileName()),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			log.debug("Failed to relocate thumbnails in {}", folder, e);
		}
	}

	/** Scratch thumbnails outlive a clip that failed before it was written. */
	private void sweepPendingThumbnails(Path folder)
	{
		try (DirectoryStream<Path> pending = Files.newDirectoryStream(folder, ".pending_*"))
		{
			for (Path path : pending)
			{
				deleteQuietly(path);
			}
		}
		catch (IOException e)
		{
			log.debug("Failed to sweep pending thumbnails in {}", folder, e);
		}
	}

	/**
	 * A temporary path for a thumbnail captured before its recording exists.
	 * A clip's frame is grabbed at the trigger, but the file is only allocated
	 * post-roll seconds later.
	 */
	public Path thumbnailScratch(RecordingKind kind, long triggeredAt) throws IOException
	{
		RecordingKind resolved = kind == null ? RecordingKindRegistry.GENERIC : kind;
		Path folder = root.resolve(resolved.folder()).resolve(THUMB_DIR);
		Files.createDirectories(folder);
		return folder.resolve(".pending_" + triggeredAt + THUMB_EXT);
	}

	/**
	 * Moves a scratch thumbnail alongside its recording.
	 *
	 * @return the entry's thumbnail name, or null when there was none
	 */
	public String adoptThumbnail(Path scratch, RecordingTarget target)
	{
		if (scratch == null || !Files.isRegularFile(scratch))
		{
			return null;
		}
		try
		{
			Path parent = target.thumbnail().getParent();
			if (parent != null)
			{
				Files.createDirectories(parent);
			}
			Files.move(scratch, target.thumbnail(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return target.thumbnailName();
		}
		catch (IOException e)
		{
			log.debug("Could not store thumbnail for {}", target.id(), e);
			deleteQuietly(scratch);
			return null;
		}
	}

	/** Newest first. */
	public List<RecordingEntry> list()
	{
		synchronized (lock)
		{
			List<RecordingEntry> out = new ArrayList<>(entries.values());
			out.sort(Comparator.comparingLong(RecordingEntry::triggeredAt).reversed());
			return Collections.unmodifiableList(out);
		}
	}

	public Optional<RecordingEntry> find(String id)
	{
		synchronized (lock)
		{
			return Optional.ofNullable(entries.get(id));
		}
	}

	public boolean delete(String id)
	{
		RecordingEntry entry;
		synchronized (lock)
		{
			entry = entries.remove(id);
		}
		if (entry == null)
		{
			return false;
		}
		if (!deleteFiles(entry))
		{
			// Put it back: a file still held open is not reclaimed space, and
			// dropping the row would hide it from the library forever.
			synchronized (lock)
			{
				entries.put(entry.id(), entry);
			}
			return false;
		}
		return true;
	}

	/**
	 * Renames a recording and the files behind it, so the description a user
	 * gave it is what they see in the folder too.
	 *
	 * @return the updated entry, or empty when the id is unknown
	 */
	public Optional<RecordingEntry> rename(String id, String description)
	{
		RecordingEntry entry;
		synchronized (lock)
		{
			entry = entries.get(id);
		}
		if (entry == null)
		{
			return Optional.empty();
		}
		String clean = sanitizeDescription(description);
		if (slugify(clean).isEmpty())
		{
			return Optional.of(entry);
		}

		Path folder = folderOf(entry);
		String base = baseName(entry.triggeredAt(), clean);
		String candidate = base;
		for (int i = 2; Files.exists(folder.resolve(candidate + VIDEO_EXT)); i++)
		{
			candidate = base + "_" + i;
		}

		Path oldSidecar = sidecarPath(entry);
		try
		{
			Files.move(videoPath(entry), folder.resolve(candidate + VIDEO_EXT));
		}
		catch (IOException e)
		{
			log.warn("Could not rename recording {}", id, e);
			return Optional.of(entry);
		}

		String thumbName = entry.thumbnailName();
		String newThumbName = null;
		if (thumbName != null && !thumbName.isEmpty())
		{
			Path thumbs = folder.resolve(THUMB_DIR);
			Path from = thumbs.resolve(thumbName);
			if (Files.isRegularFile(from))
			{
				try
				{
					Files.move(from, thumbs.resolve(candidate + THUMB_EXT));
					newThumbName = candidate + THUMB_EXT;
				}
				catch (IOException e)
				{
					// The video already moved; keep going without the thumbnail.
					log.debug("Could not rename thumbnail for {}", id, e);
				}
			}
		}

		RecordingEntry renamed = entry.toBuilder()
			.description(clean)
			.fileName(candidate + VIDEO_EXT)
			.thumbnailName(newThumbName)
			.build();
		try
		{
			RecordingCodec.write(sidecarPath(renamed), renamed);
			deleteQuietly(oldSidecar);
			synchronized (lock)
			{
				entries.put(renamed.id(), renamed);
			}
			return Optional.of(renamed);
		}
		catch (IOException e)
		{
			log.warn("Renamed the files for {} but could not write its sidecar", id, e);
			return Optional.of(entry);
		}
	}

	/**
	 * Attaches a key/value to a stored recording. The metadata map is the
	 * extension point for state the recorder itself knows nothing about.
	 *
	 * @return the updated entry, or empty when the id is unknown
	 */
	public Optional<RecordingEntry> annotate(String id, String key, String value)
	{
		// Read and write under one lock: annotate runs on the upload thread
		// while pin and rename run on the EDT, and a straddled update loses one
		// of the two -- or resurrects an entry deleted in between.
		synchronized (lock)
		{
			RecordingEntry entry = entries.get(id);
			if (entry == null)
			{
				return Optional.empty();
			}
			try
			{
				return Optional.of(commitLocked(entry.toBuilder().meta(key, value).build()));
			}
			catch (IOException e)
			{
				log.warn("Could not annotate recording {}", id, e);
				return Optional.of(entry);
			}
		}
	}

	public Optional<RecordingEntry> setPinned(String id, boolean pinned)
	{
		synchronized (lock)
		{
			RecordingEntry entry = entries.get(id);
			if (entry == null || entry.pinned() == pinned)
			{
				return Optional.ofNullable(entry);
			}
			try
			{
				return Optional.of(commitLocked(entry.withPinned(pinned)));
			}
			catch (IOException e)
			{
				log.warn("Failed to update pin state for {}", id, e);
				return Optional.of(entry);
			}
		}
	}

	public long totalBytes()
	{
		synchronized (lock)
		{
			long total = 0;
			for (RecordingEntry entry : entries.values())
			{
				total += entry.sizeBytes();
			}
			return total;
		}
	}

	/**
	 * Drops anything past the age limit, then evicts oldest-first to fit the
	 * budget. Pinned recordings are never evicted, so a pinned library can
	 * legitimately exceed it.
	 *
	 * @param budgetBytes   total size cap, or <= 0 to skip
	 * @param retentionDays age cap in days, or <= 0 to skip
	 */
	public List<RecordingEntry> enforceRetention(long budgetBytes, int retentionDays)
	{
		return enforceRetention(budgetBytes, retentionDays, java.util.Collections.emptySet());
	}

	/** @param keepIds exempt from this pass, so a save cannot evict its own file */
	public List<RecordingEntry> enforceRetention(long budgetBytes, int retentionDays,
		java.util.Collection<String> keepIds)
	{
		List<RecordingEntry> evicted = new ArrayList<>();
		List<RecordingEntry> candidates;
		synchronized (lock)
		{
			candidates = new ArrayList<>(entries.values());
		}
		candidates.removeIf(RecordingEntry::pinned);
		candidates.removeIf(e -> keepIds.contains(e.id()));
		candidates.sort(Comparator.comparingLong(RecordingEntry::triggeredAt));

		if (retentionDays > 0)
		{
			long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
			for (RecordingEntry entry : new ArrayList<>(candidates))
			{
				if (entry.triggeredAt() < cutoff && delete(entry.id()))
				{
					evicted.add(entry);
					candidates.remove(entry);
				}
			}
		}

		if (budgetBytes > 0)
		{
			long total = totalBytes();
			for (RecordingEntry entry : candidates)
			{
				if (total <= budgetBytes)
				{
					break;
				}
				if (delete(entry.id()))
				{
					total -= entry.sizeBytes();
					evicted.add(entry);
				}
			}
		}
		return evicted;
	}

	public Path videoPath(RecordingEntry entry)
	{
		return folderOf(entry).resolve(entry.fileName());
	}

	public Optional<Path> thumbnailPath(RecordingEntry entry)
	{
		String name = entry.thumbnailName();
		if (name == null || name.isEmpty())
		{
			return Optional.empty();
		}
		Path path = folderOf(entry).resolve(THUMB_DIR).resolve(name);
		return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
	}

	private Path sidecarPath(RecordingEntry entry)
	{
		String name = entry.fileName();
		int dot = name.lastIndexOf('.');
		return folderOf(entry).resolve((dot < 0 ? name : name.substring(0, dot)) + SIDECAR_EXT);
	}

	private Path folderOf(RecordingEntry entry)
	{
		String folder = entry.folder();
		return root.resolve(folder == null || folder.isEmpty()
			? kinds.resolve(entry.kindId()).folder() : folder);
	}

	/** @return false when the video could not be removed */
	private boolean deleteFiles(RecordingEntry entry)
	{
		boolean removed;
		try
		{
			Path video = videoPath(entry);
			removed = !Files.exists(video) || Files.deleteIfExists(video);
		}
		catch (IOException e)
		{
			log.warn("Could not delete {}", entry.id(), e);
			return false;
		}
		deleteQuietly(sidecarPath(entry));
		String thumb = entry.thumbnailName();
		if (thumb != null && !thumb.isEmpty())
		{
			deleteQuietly(folderOf(entry).resolve(THUMB_DIR).resolve(thumb));
		}
		return removed;
	}

	private void deleteQuietly(Path path)
	{
		try
		{
			Files.deleteIfExists(path);
		}
		catch (IOException e)
		{
			log.debug("Failed to delete {}", path, e);
		}
	}

	private static long sizeOf(Path path)
	{
		try
		{
			return Files.size(path);
		}
		catch (IOException e)
		{
			return 0L;
		}
	}

	private static String baseName(long triggeredAt, String description)
	{
		String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date(triggeredAt));
		String slug = slugify(description);
		return slug.isEmpty() ? stamp : stamp + "_" + slug;
	}

	/**
	 * Strips what a file name cannot carry, so the description on the card
	 * matches the file on disk. Extensions and separators go: a recording
	 * called "party.mp4" is only ever confusing.
	 */
	static String sanitizeDescription(String input)
	{
		if (input == null)
		{
			return "";
		}
		String cleaned = input.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|.]", " ");
		cleaned = cleaned.replaceAll("\\s+", " ").trim();
		return cleaned.length() > 60 ? cleaned.substring(0, 60).trim() : cleaned;
	}

	static String slugify(String input)
	{
		if (input == null)
		{
			return "";
		}
		String cleaned = input.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
		cleaned = cleaned.replaceAll("^_+|_+$", "");
		return cleaned.length() > 48 ? cleaned.substring(0, 48) : cleaned;
	}

	public static Path defaultRoot()
	{
		return java.nio.file.Paths.get(System.getProperty("user.home"), ".runelite", "recordings");
	}
}
