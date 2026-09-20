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
 * What a trigger asks for. Roll lengths are clamped to the configured ceilings
 * and, for clips, to what the ring still holds.
 */
public final class RecordingRequest
{
	private final RecordingKind kind;
	private final String description;
	private final int preRollSeconds;
	private final int postRollSeconds;
	private final boolean session;
	private final int maxSeconds;
	private final Map<String, String> metadata;

	private RecordingRequest(Builder b)
	{
		this.kind = b.kind;
		this.description = b.description;
		this.preRollSeconds = b.preRollSeconds;
		this.postRollSeconds = b.postRollSeconds;
		this.session = b.session;
		this.maxSeconds = b.maxSeconds;
		this.metadata = b.metadata.isEmpty()
			? Collections.emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata));
	}

	public RecordingKind kind()
	{
		return kind;
	}

	public String description()
	{
		return description;
	}

	/** Buffered seconds to include before the trigger, or -1 for the default. */
	public int preRollSeconds()
	{
		return preRollSeconds;
	}

	/** Seconds to keep after the trigger, or -1 for the default. Unused for sessions. */
	public int postRollSeconds()
	{
		return postRollSeconds;
	}

	public boolean session()
	{
		return session;
	}

	/** Session cap, or -1 for the default. */
	public int maxSeconds()
	{
		return maxSeconds;
	}

	public Map<String, String> metadata()
	{
		return metadata;
	}

	public static Builder of(RecordingKind kind)
	{
		return new Builder(kind);
	}

	public static final class Builder
	{
		private final RecordingKind kind;
		private String description = "";
		private int preRollSeconds = -1;
		private int postRollSeconds = -1;
		private boolean session;
		private int maxSeconds = -1;
		private final Map<String, String> metadata = new LinkedHashMap<>();

		private Builder(RecordingKind kind)
		{
			if (kind == null)
			{
				throw new IllegalArgumentException("kind must not be null");
			}
			this.kind = kind;
		}

		public Builder description(String v)
		{
			this.description = v == null ? "" : v;
			return this;
		}

		public Builder preRollSeconds(int v)
		{
			this.preRollSeconds = v;
			return this;
		}

		public Builder postRollSeconds(int v)
		{
			this.postRollSeconds = v;
			return this;
		}

		public Builder session(boolean v)
		{
			this.session = v;
			return this;
		}

		public Builder maxSeconds(int v)
		{
			this.maxSeconds = v;
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

		public RecordingRequest build()
		{
			return new RecordingRequest(this);
		}
	}
}
