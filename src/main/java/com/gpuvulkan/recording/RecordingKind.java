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

import java.util.Locale;
import java.util.Objects;

/**
 * A class of recording. A value object rather than an enum so extensions can
 * register their own through {@link RecordingKindRegistry}.
 */
public final class RecordingKind
{
	private final String id;
	private final String folder;
	private final String displayName;

	public RecordingKind(String id, String folder, String displayName)
	{
		this.id = requireId(id);
		this.folder = sanitizeFolder(folder == null || folder.isEmpty() ? this.id : folder);
		this.displayName = displayName == null || displayName.isEmpty() ? this.id : displayName;
	}

	public String id()
	{
		return id;
	}

	/** Sub-directory of the recordings root. Never empty, never an escape. */
	public String folder()
	{
		return folder;
	}

	public String displayName()
	{
		return displayName;
	}

	private static String requireId(String id)
	{
		if (id == null || id.trim().isEmpty())
		{
			throw new IllegalArgumentException("kind id must not be blank");
		}
		return id.trim().toLowerCase(Locale.ROOT);
	}

	/** Kind ids come from third-party code: no separators, no traversal. */
	private static String sanitizeFolder(String raw)
	{
		String cleaned = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
		cleaned = cleaned.replaceAll("^[_-]+|[_-]+$", "");
		if (cleaned.isEmpty())
		{
			cleaned = "misc";
		}
		return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof RecordingKind))
		{
			return false;
		}
		return id.equals(((RecordingKind) o).id);
	}

	@Override
	public int hashCode()
	{
		return Objects.hashCode(id);
	}

	@Override
	public String toString()
	{
		return "RecordingKind(" + id + ")";
	}
}
