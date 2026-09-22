/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording.events;

import com.gpuvulkan.recording.RecordingEntry;

/** A stored recording's metadata changed: renamed, annotated, pinned. */
public final class RecordingUpdated
{
	private final RecordingEntry entry;

	public RecordingUpdated(RecordingEntry entry)
	{
		this.entry = entry;
	}

	public RecordingEntry entry()
	{
		return entry;
	}
}
