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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One stored recording, serialized verbatim as its sidecar. Field names are a
 * compatibility surface: add them, never rename them. Paths are relative to
 * the library root so it can be moved.
 */
public final class RecordingEntry
{
	/** Bumped when a field changes meaning. */
	public static final int SCHEMA_VERSION = 1;

	private int schemaVersion = SCHEMA_VERSION;
	private String id;
	private String kindId;
	private String folder;
	private String description;
	private String fileName;
	private String thumbnailName;
	private long triggeredAt;
	private long durationMs;
	private long sizeBytes;
	private int width;
	private int height;
	private int fps;
	private boolean session;
	private boolean pinned;
	private String continuationOf;
	private Map<String, String> metadata;

	/** Gson needs this; use {@link Builder}. */
	RecordingEntry()
	{
	}

	private RecordingEntry(Builder b)
	{
		this.schemaVersion = SCHEMA_VERSION;
		this.id = b.id;
		this.kindId = b.kindId;
		this.folder = b.folder;
		this.description = b.description;
		this.fileName = b.fileName;
		this.thumbnailName = b.thumbnailName;
		this.triggeredAt = b.triggeredAt;
		this.durationMs = b.durationMs;
		this.sizeBytes = b.sizeBytes;
		this.width = b.width;
		this.height = b.height;
		this.fps = b.fps;
		this.session = b.session;
		this.pinned = b.pinned;
		this.continuationOf = b.continuationOf;
		this.metadata = b.metadata.isEmpty() ? null : new LinkedHashMap<>(b.metadata);
	}

	public int schemaVersion()
	{
		return schemaVersion;
	}

	public String id()
	{
		return id;
	}

	public String kindId()
	{
		return kindId;
	}

	/**
	 * Directory holding this recording, relative to the library root. Stored
	 * rather than derived: the kind's extension may be gone by the time anyone
	 * looks, and a synthesised kind does not know the real folder.
	 */
	public String folder()
	{
		return folder;
	}

	public String description()
	{
		return description == null ? "" : description;
	}

	/** File name within the kind folder. */
	public String fileName()
	{
		return fileName;
	}

	public String thumbnailName()
	{
		return thumbnailName;
	}

	public long triggeredAt()
	{
		return triggeredAt;
	}

	public long durationMs()
	{
		return durationMs;
	}

	public long sizeBytes()
	{
		return sizeBytes;
	}

	public int width()
	{
		return width;
	}

	public int height()
	{
		return height;
	}

	public int fps()
	{
		return fps;
	}

	/** True for a streamed session, false for a ring-backed clip. */
	public boolean session()
	{
		return session;
	}

	/** Exempt from retention eviction. */
	public boolean pinned()
	{
		return pinned;
	}

	/** The recording this one continues after a resize. */
	public String continuationOf()
	{
		return continuationOf;
	}

	/** Free-form extras. Never null. */
	public Map<String, String> metadata()
	{
		return metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
	}

	public RecordingEntry withPinned(boolean value)
	{
		return toBuilder().pinned(value).build();
	}

	public RecordingEntry withSize(long bytes)
	{
		return toBuilder().sizeBytes(bytes).build();
	}

	public Builder toBuilder()
	{
		return new Builder()
			.id(id)
			.kindId(kindId)
			.folder(folder)
			.description(description)
			.fileName(fileName)
			.thumbnailName(thumbnailName)
			.triggeredAt(triggeredAt)
			.durationMs(durationMs)
			.sizeBytes(sizeBytes)
			.dimensions(width, height)
			.fps(fps)
			.session(session)
			.pinned(pinned)
			.continuationOf(continuationOf)
			.metadata(metadata);
	}

	public static Builder builder()
	{
		return new Builder();
	}

	public static final class Builder
	{
		private String id;
		private String kindId = RecordingKindRegistry.GENERIC.id();
		private String folder;
		private String description = "";
		private String fileName;
		private String thumbnailName;
		private long triggeredAt;
		private long durationMs;
		private long sizeBytes;
		private int width;
		private int height;
		private int fps;
		private boolean session;
		private boolean pinned;
		private String continuationOf;
		private final Map<String, String> metadata = new LinkedHashMap<>();

		public Builder id(String v)
		{
			this.id = v;
			return this;
		}

		public Builder kind(RecordingKind v)
		{
			RecordingKind kind = v == null ? RecordingKindRegistry.GENERIC : v;
			this.kindId = kind.id();
			this.folder = kind.folder();
			return this;
		}

		public Builder folder(String v)
		{
			this.folder = v;
			return this;
		}

		public Builder kindId(String v)
		{
			this.kindId = v == null ? RecordingKindRegistry.GENERIC.id() : v;
			return this;
		}

		public Builder description(String v)
		{
			this.description = v == null ? "" : v;
			return this;
		}

		public Builder fileName(String v)
		{
			this.fileName = v;
			return this;
		}

		public Builder thumbnailName(String v)
		{
			this.thumbnailName = v;
			return this;
		}

		public Builder triggeredAt(long v)
		{
			this.triggeredAt = v;
			return this;
		}

		public Builder durationMs(long v)
		{
			this.durationMs = v;
			return this;
		}

		public Builder sizeBytes(long v)
		{
			this.sizeBytes = v;
			return this;
		}

		public Builder dimensions(int w, int h)
		{
			this.width = w;
			this.height = h;
			return this;
		}

		public Builder fps(int v)
		{
			this.fps = v;
			return this;
		}

		public Builder session(boolean v)
		{
			this.session = v;
			return this;
		}

		public Builder pinned(boolean v)
		{
			this.pinned = v;
			return this;
		}

		public Builder continuationOf(String v)
		{
			this.continuationOf = v;
			return this;
		}

		public Builder metadata(Map<String, String> v)
		{
			this.metadata.clear();
			if (v != null)
			{
				for (Map.Entry<String, String> e : v.entrySet())
				{
					if (e.getKey() != null && e.getValue() != null)
					{
						this.metadata.put(e.getKey(), e.getValue());
					}
				}
			}
			return this;
		}

		public Builder meta(String key, String value)
		{
			if (key != null && value != null)
			{
				this.metadata.put(key, value);
			}
			return this;
		}

		public RecordingEntry build()
		{
			if (id == null || id.isEmpty())
			{
				throw new IllegalStateException("recording id is required");
			}
			if (fileName == null || fileName.isEmpty())
			{
				throw new IllegalStateException("recording fileName is required");
			}
			return new RecordingEntry(this);
		}
	}

	@Override
	public String toString()
	{
		return "RecordingEntry(" + id + ", " + kindId + ", " + fileName + ")";
	}
}
