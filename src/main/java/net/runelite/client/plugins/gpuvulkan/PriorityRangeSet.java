/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package net.runelite.client.plugins.gpuvulkan;

import java.util.Arrays;

final class PriorityRangeSet
{
	private int[] starts = new int[1024];
	private int[] ends = new int[1024];
	private int[] skipPairs = new int[2048];
	private int count;

	void clear()
	{
		count = 0;
	}

	void record(int start, int end)
	{
		if (start < 0 || end <= start)
		{
			return;
		}
		if (count == starts.length)
		{
			int newSize = starts.length * 2;
			starts = Arrays.copyOf(starts, newSize);
			ends = Arrays.copyOf(ends, newSize);
			skipPairs = Arrays.copyOf(skipPairs, newSize * 2);
		}
		starts[count] = start;
		ends[count] = end;
		skipPairs[count * 2] = start;
		skipPairs[count * 2 + 1] = end;
		count++;
	}

	int count()
	{
		return count;
	}

	int start(int index)
	{
		return starts[index];
	}

	int end(int index)
	{
		return ends[index];
	}

	int[] skipPairs()
	{
		return skipPairs;
	}
}
