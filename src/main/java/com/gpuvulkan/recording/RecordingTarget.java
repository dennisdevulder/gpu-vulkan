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

import java.nio.file.Path;

/** A destination the store has reserved but not created. */
public final class RecordingTarget
{
	private final String id;
	private final RecordingKind kind;
	private final long triggeredAt;
	private final String fileName;
	private final String thumbnailName;
	private final Path file;
	private final Path thumbnail;
	private final Path sidecar;

	RecordingTarget(String id, RecordingKind kind, long triggeredAt, String fileName,
		String thumbnailName, Path file, Path thumbnail, Path sidecar)
	{
		this.id = id;
		this.kind = kind;
		this.triggeredAt = triggeredAt;
		this.fileName = fileName;
		this.thumbnailName = thumbnailName;
		this.file = file;
		this.thumbnail = thumbnail;
		this.sidecar = sidecar;
	}

	public String id()
	{
		return id;
	}

	public RecordingKind kind()
	{
		return kind;
	}

	public long triggeredAt()
	{
		return triggeredAt;
	}

	public String fileName()
	{
		return fileName;
	}

	public String thumbnailName()
	{
		return thumbnailName;
	}

	/** Written directly. There is no spool file. */
	public Path file()
	{
		return file;
	}

	public Path thumbnail()
	{
		return thumbnail;
	}

	public Path sidecar()
	{
		return sidecar;
	}

	public RecordingEntry.Builder entry()
	{
		return RecordingEntry.builder()
			.id(id)
			.kind(kind)
			.fileName(fileName)
			.triggeredAt(triggeredAt);
	}
}
