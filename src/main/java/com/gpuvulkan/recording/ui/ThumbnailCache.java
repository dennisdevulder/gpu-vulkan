/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decoded thumbnails, kept so the list can be rebuilt without decoding.
 *
 * The list is rebuilt on every keystroke in the search box, so decoding per
 * card meant re-reading the whole visible library from disk each time.
 */
final class ThumbnailCache
{
	private final Map<Path, BufferedImage> decoded = new ConcurrentHashMap<>();

	/** Null when it has not been decoded yet; never decodes on the caller's thread. */
	BufferedImage get(Path path)
	{
		return path == null ? null : decoded.get(path);
	}

	void put(Path path, BufferedImage image)
	{
		if (path != null && image != null)
		{
			decoded.put(path, image);
		}
	}

	void forget(Path path)
	{
		if (path != null)
		{
			decoded.remove(path);
		}
	}
}
