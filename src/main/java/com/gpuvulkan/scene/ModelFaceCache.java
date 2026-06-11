/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import java.util.Arrays;

final class ModelFaceCache
{
	private static final int MAX_ENTRIES = 8192;
	private static final int BUCKETS = 16384;
	private static final int BUCKET_MASK = BUCKETS - 1;

	private final Entry[] buckets = new Entry[BUCKETS];
	private int size;
	private long hits;
	private long misses;

	Entry info(int faceCount, byte[] faceTransparencies)
	{
		int hash = 31 * faceCount + System.identityHashCode(faceTransparencies);
		int bucket = hash & BUCKET_MASK;
		for (Entry entry = buckets[bucket]; entry != null; entry = entry.next)
		{
			if (entry.matches(hash, faceCount, faceTransparencies))
			{
				hits++;
				return entry;
			}
		}

		misses++;
		if (size >= MAX_ENTRIES)
		{
			clear();
			bucket = hash & BUCKET_MASK;
		}
		Entry cached = new Entry(hash, faceCount, faceTransparencies, buckets[bucket]);
		buckets[bucket] = cached;
		size++;
		return cached;
	}

	void collectDebugMetrics(GpuVulkanDebugMetrics metrics)
	{
		metrics.modelCacheEntries += size;
		metrics.modelCacheHits += hits;
		metrics.modelCacheMisses += misses;
	}

	static int countTransparentFaces(int faceCount, byte[] transparencies)
	{
		if (transparencies == null)
		{
			return 0;
		}
		int transparent = 0;
		int faces = Math.min(faceCount, transparencies.length);
		for (int i = 0; i < faces; i++)
		{
			if (transparencies[i] != 0)
			{
				transparent++;
			}
		}
		return transparent;
	}

	void clear()
	{
		Arrays.fill(buckets, null);
		size = 0;
	}

	static final class Entry
	{
		private Entry next;
		final int transparentFaces;
		final boolean hasTransparentFaces;
		final int faceCount;

		private final int hash;
		private final byte[] faceTransparencies;

		private Entry(int hash, int faceCount, byte[] faceTransparencies, Entry next)
		{
			this.next = next;
			this.hash = hash;
			this.faceCount = faceCount;
			this.faceTransparencies = faceTransparencies;
			this.transparentFaces = countTransparentFaces(faceCount, faceTransparencies);
			this.hasTransparentFaces = transparentFaces > 0;
		}

		private boolean matches(int hash, int faceCount, byte[] faceTransparencies)
		{
			return this.hash == hash
				&& this.faceCount == faceCount
				&& this.faceTransparencies == faceTransparencies;
		}
	}
}
