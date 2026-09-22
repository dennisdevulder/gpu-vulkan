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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The JSON sidecar beside every recording. Sidecars rather than one index
 * file: a half-written index would lose the library.
 */
final class RecordingCodec
{
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private RecordingCodec()
	{
	}

	/** Via a temp file, so a crash cannot leave a truncated sidecar. */
	static void write(Path sidecar, RecordingEntry entry) throws IOException
	{
		Path parent = sidecar.getParent();
		if (parent != null)
		{
			Files.createDirectories(parent);
		}
		Path tmp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))
		{
			GSON.toJson(entry, w);
		}
		try
		{
			Files.move(tmp, sidecar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (java.nio.file.AtomicMoveNotSupportedException e)
		{
			Files.move(tmp, sidecar, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Null for an unreadable or invalid sidecar. */
	static RecordingEntry read(Path sidecar)
	{
		try (Reader r = Files.newBufferedReader(sidecar, StandardCharsets.UTF_8))
		{
			RecordingEntry entry = GSON.fromJson(r, RecordingEntry.class);
			if (entry == null || entry.id() == null || entry.id().isEmpty()
				|| entry.fileName() == null || entry.fileName().isEmpty())
			{
				return null;
			}
			return entry;
		}
		catch (IOException | JsonSyntaxException e)
		{
			return null;
		}
	}
}
