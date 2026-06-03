/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package net.runelite.client.plugins.gpuvulkan;

import java.util.Arrays;

final class DirtyZoneTracker
{
	private final boolean[] dirty;
	private final int[] slotMask;
	private int count;

	DirtyZoneTracker(int zoneCount)
	{
		dirty = new boolean[zoneCount];
		slotMask = new int[zoneCount];
	}

	void clear()
	{
		Arrays.fill(dirty, false);
		Arrays.fill(slotMask, 0);
		count = 0;
	}

	void mark(int zone)
	{
		if (zone < 0 || zone >= dirty.length)
		{
			return;
		}
		if (!dirty[zone])
		{
			dirty[zone] = true;
			count++;
		}
		slotMask[zone] = 0;
	}

	boolean needsSlot(int zone, int slotBit)
	{
		return dirty[zone] && (slotMask[zone] & slotBit) == 0;
	}

	void markSlotRebuilt(int zone, int slotBit, int allSlots)
	{
		slotMask[zone] |= slotBit;
		if (slotMask[zone] == allSlots)
		{
			dirty[zone] = false;
			count--;
		}
	}

	boolean isDirty(int zone)
	{
		return zone >= 0 && zone < dirty.length && dirty[zone];
	}

	int count()
	{
		return count;
	}
}
