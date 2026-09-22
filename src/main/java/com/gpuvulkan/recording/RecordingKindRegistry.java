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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Open registry of {@link RecordingKind}s. Instance-scoped, so a plugin restart
 * cannot leak kinds from the previous session.
 */
public final class RecordingKindRegistry
{
	public static final RecordingKind QUICK_CAPTURE = new RecordingKind("quick_capture", "quick_captures", "Quick capture");
	public static final RecordingKind LEVEL_UP = new RecordingKind("level_up", "level_ups", "Level up");
	public static final RecordingKind QUEST = new RecordingKind("quest", "quests", "Quest");
	public static final RecordingKind LOOT = new RecordingKind("loot", "loot", "Loot");
	public static final RecordingKind DEATH = new RecordingKind("death", "deaths", "Death");
	public static final RecordingKind PET = new RecordingKind("pet", "pets", "Pet");
	public static final RecordingKind COLLECTION_LOG = new RecordingKind("collection_log", "collection_logs", "Collection log");
	public static final RecordingKind CLUE_SCROLL = new RecordingKind("clue_scroll", "clue_scrolls", "Clue scroll");
	public static final RecordingKind GENERIC = new RecordingKind("generic", "misc", "Recording");

	private static final RecordingKind[] BUILT_INS = {
		QUICK_CAPTURE, LEVEL_UP, QUEST, LOOT, DEATH, PET, COLLECTION_LOG, CLUE_SCROLL, GENERIC
	};

	private final Map<String, RecordingKind> kinds = new LinkedHashMap<>();

	public RecordingKindRegistry()
	{
		for (RecordingKind kind : BUILT_INS)
		{
			kinds.put(kind.id(), kind);
		}
	}

	/** Returns the existing kind when the id is taken, so rebinding is a no-op. */
	public synchronized RecordingKind register(RecordingKind kind)
	{
		if (kind == null)
		{
			throw new IllegalArgumentException("kind must not be null");
		}
		RecordingKind existing = kinds.get(kind.id());
		if (existing != null)
		{
			return existing;
		}
		kinds.put(kind.id(), kind);
		return kind;
	}

	public RecordingKind register(String id, String folder, String displayName)
	{
		return register(new RecordingKind(id, folder, displayName));
	}

	public synchronized RecordingKind find(String id)
	{
		return id == null ? null : kinds.get(id.trim().toLowerCase(Locale.ROOT));
	}

	/** Synthesizes a kind for an unknown id, so recordings written by an
	 *  uninstalled extension still list. */
	public synchronized RecordingKind resolve(String id)
	{
		RecordingKind known = find(id);
		if (known != null)
		{
			return known;
		}
		return id == null || id.trim().isEmpty() ? GENERIC : new RecordingKind(id, id, id);
	}

	public synchronized List<RecordingKind> all()
	{
		return Collections.unmodifiableList(new ArrayList<>(kinds.values()));
	}
}
