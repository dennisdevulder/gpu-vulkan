/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import java.util.Arrays;
import java.util.Set;

final class RoofRangeSet
{
	private int[] ids = new int[2048];
	private int[] starts = new int[2048];
	private int[] counts = new int[2048];
	private int count;

	void clear()
	{
		count = 0;
	}

	int count()
	{
		return count;
	}

	void record(int roofId, int vertexStart, int vertexCount)
	{
		if (count == ids.length)
		{
			int newSize = ids.length * 2;
			ids = Arrays.copyOf(ids, newSize);
			starts = Arrays.copyOf(starts, newSize);
			counts = Arrays.copyOf(counts, newSize);
		}
		ids[count] = roofId;
		starts[count] = vertexStart;
		counts[count] = vertexCount;
		count++;
	}

	int buildSkipPairs(Set<Integer> hiddenRoofIds, int[] out)
	{
		if (hiddenRoofIds.isEmpty() || count == 0)
		{
			return 0;
		}
		int pairs = 0;
		for (int i = 0; i < count; i++)
		{
			if (!hiddenRoofIds.contains(ids[i]))
			{
				continue;
			}
			out[pairs * 2] = starts[i];
			out[pairs * 2 + 1] = starts[i] + counts[i];
			pairs++;
		}
		return pairs;
	}

	int requiredSkipPairCapacity()
	{
		return Math.max(2, count * 2);
	}
}
