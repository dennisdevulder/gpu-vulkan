/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import java.util.ArrayList;
import net.runelite.api.Model;
import net.runelite.api.Renderable;

final class PendingRenderables
{
	private static final int MAX_PENDING = 4096;

	private final ArrayList<Entry> entries = new ArrayList<>();

	void clear()
	{
		entries.clear();
	}

	int size()
	{
		return entries.size();
	}

	void add(Renderable renderable, int orientation, int x, int y, int z, int zone)
	{
		if (entries.size() < MAX_PENDING)
		{
			entries.add(new Entry(renderable, orientation, x, y, z, zone));
		}
	}

	void captureLoaded(Sink sink)
	{
		if (entries.isEmpty())
		{
			return;
		}
		for (int i = 0; i < entries.size(); )
		{
			Entry entry = entries.get(i);
			Model model = entry.model;
			if (model == null)
			{
				model = resolveModel(entry.renderable);
				if (model == null)
				{
					i++;
					continue;
				}
				entry.model = model;
			}
			if (!entry.dirtyMarked)
			{
				sink.markZoneDirty(entry.zone);
				entry.dirtyMarked = true;
			}
			sink.captureModel(model, entry.orientation, entry.x, entry.y, entry.z);
			if (!sink.isZoneDirty(entry.zone))
			{
				entries.remove(i);
				continue;
			}
			i++;
		}
	}

	/** Stock's zone whitelist (SceneUploader.zoneRenderableSize): only Model
	 *  and DynamicObject are zone-static. Anything else on a tile (Actor,
	 *  Projectile, GraphicsObject) is engine-drawn per frame via
	 *  drawDynamic/drawTemp — baking it into a zone freezes it at its
	 *  capture pose until the next rebuild, which may never come. */
	static Model resolveModel(Renderable renderable)
	{
		if (renderable instanceof Model)
		{
			return (Model) renderable;
		}
		if (renderable instanceof net.runelite.api.DynamicObject)
		{
			return ((net.runelite.api.DynamicObject) renderable).getModelZbuf();
		}
		return null;
	}

	interface Sink
	{
		void markZoneDirty(int zone);

		void captureModel(Model model, int orientation, int x, int y, int z);

		boolean isZoneDirty(int zone);
	}

	private static final class Entry
	{
		final Renderable renderable;
		final int orientation;
		final int x;
		final int y;
		final int z;
		final int zone;
		Model model;
		boolean dirtyMarked;

		Entry(Renderable renderable, int orientation, int x, int y, int z, int zone)
		{
			this.renderable = renderable;
			this.orientation = orientation;
			this.x = x;
			this.y = y;
			this.z = z;
			this.zone = zone;
		}
	}
}
