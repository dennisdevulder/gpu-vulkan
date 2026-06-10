/*
 * Zone-based static and overlay draw scheduling for SceneRenderer.
 */
package net.runelite.client.plugins.gpuvulkan;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.lwjgl.vulkan.VkCommandBuffer;

final class SceneZoneDrawScheduler
{
	private final SceneDrawEmitter drawEmitter;
	private final int zonesPerSide;
	private final int zoneCount;
	private final int[][] planeEnds;
	private final int[][][] zoneVertexStart;
	private final int[][][] zoneVertexCount;
	private final int[][][][] overlayZoneStart;
	private final int[][][][] overlayZoneCount;
	private final boolean[][] overlayZoneValid;
	private final boolean[] overlaySlotHasZones;

	private int[] overlaySkipScratch = new int[256];
	private int[] combinedSkipScratch = new int[256];
	/** Per-zone frustum visibility for the pass being recorded; null = all
	 *  visible. Set by SceneRenderer from the pass's MVP before drawing. */
	private boolean[] zoneVisible;

	void setZoneVisibility(boolean[] mask)
	{
		zoneVisible = mask;
	}

	private boolean zoneCulled(int zoneIdx)
	{
		return zoneVisible != null && !zoneVisible[zoneIdx];
	}

	SceneZoneDrawScheduler(SceneDrawEmitter drawEmitter,
		int zonesPerSide, int zoneCount,
		int[][] planeEnds,
		int[][][] zoneVertexStart, int[][][] zoneVertexCount,
		int[][][][] overlayZoneStart, int[][][][] overlayZoneCount,
		boolean[][] overlayZoneValid, boolean[] overlaySlotHasZones)
	{
		this.drawEmitter = drawEmitter;
		this.zonesPerSide = zonesPerSide;
		this.zoneCount = zoneCount;
		this.planeEnds = planeEnds;
		this.zoneVertexStart = zoneVertexStart;
		this.zoneVertexCount = zoneVertexCount;
		this.overlayZoneStart = overlayZoneStart;
		this.overlayZoneCount = overlayZoneCount;
		this.overlayZoneValid = overlayZoneValid;
		this.overlaySlotHasZones = overlaySlotHasZones;
	}

	void drawStaticPlane(VkCommandBuffer cmd, int layer, int plane, int layerStart,
		boolean fullZoneRange, int minZoneX, int maxZoneX, int minZoneZ, int maxZoneZ,
		int[] skips, int skipPairs, boolean applySkips,
		int slot, int slotFirstVertex, long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		int planeStart = plane == 0 ? layerStart : planeEnds[layer][plane - 1];
		int planeEnd = planeEnds[layer][plane];
		if (planeEnd <= planeStart)
		{
			return;
		}

		if (fullZoneRange)
		{
			drawFullStaticPlane(cmd, layer, plane, planeStart, planeEnd,
				skips, skipPairs, applySkips, slot, slotFirstVertex, pipelineLayout, vertPush, fragPush);
			return;
		}

		drawVisibleStaticZones(cmd, layer, plane, minZoneX, maxZoneX, minZoneZ, maxZoneZ,
			skips, skipPairs, applySkips, slot, slotFirstVertex, pipelineLayout, vertPush, fragPush);
	}

	void drawOverlayPlane(VkCommandBuffer cmd, int layer, int plane,
		int minZoneX, int maxZoneX, int minZoneZ, int maxZoneZ,
		int[] skips, int skipPairs, boolean applySkips,
		int slot, int slotFirstVertex, long pipelineLayout,
		ByteBuffer vertPush, ByteBuffer fragPush)
	{
		if (!overlaySlotHasZones[slot])
		{
			return;
		}

		boolean mergeRanges = !applySkips || skipPairs == 0;
		int mergedStart = -1;
		int mergedEnd = -1;
		for (int zx = minZoneX; zx <= maxZoneX; zx++)
		{
			for (int zz = minZoneZ; zz <= maxZoneZ; zz++)
			{
				int zoneIdx = zx * zonesPerSide + zz;
				if (zoneCulled(zoneIdx) || !hasOverlayRange(slot, layer, plane, zoneIdx))
				{
					continue;
				}
				int count = overlayZoneCount[slot][layer][plane][zoneIdx];
				int start = overlayZoneStart[slot][layer][plane][zoneIdx];
				int end = start + count;
				if (mergeRanges)
				{
					if (mergedStart < 0)
					{
						mergedStart = start;
						mergedEnd = end;
					}
					else if (start == mergedEnd)
					{
						mergedEnd = end;
					}
					else
					{
						drawEmitter.drawRange(cmd, mergedStart, mergedEnd, skips, 0, false,
							slotFirstVertex, pipelineLayout, vertPush, fragPush);
						mergedStart = start;
						mergedEnd = end;
					}
				}
				else
				{
					drawEmitter.drawRange(cmd, start, end, skips, skipPairs, true,
						slotFirstVertex, pipelineLayout, vertPush, fragPush);
				}
			}
		}
		if (mergedStart >= 0)
		{
			drawEmitter.drawRange(cmd, mergedStart, mergedEnd, skips, 0, false,
				slotFirstVertex, pipelineLayout, vertPush, fragPush);
		}
	}

	private void drawFullStaticPlane(VkCommandBuffer cmd, int layer, int plane, int planeStart, int planeEnd,
		int[] skips, int skipPairs, boolean applySkips,
		int slot, int slotFirstVertex, long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		if (!overlaySlotHasZones[slot])
		{
			drawEmitter.drawRange(cmd, planeStart, planeEnd, skips, skipPairs, applySkips,
				slotFirstVertex, pipelineLayout, vertPush, fragPush);
			return;
		}

		int overlayPairs = buildOverlayStaticSkipPairs(layer, plane, slot);
		if (!applySkips || skipPairs == 0)
		{
			drawEmitter.drawRange(cmd, planeStart, planeEnd, overlaySkipScratch, overlayPairs, overlayPairs > 0,
				slotFirstVertex, pipelineLayout, vertPush, fragPush);
			return;
		}

		int combinedPairs = mergeSkipPairs(skips, skipPairs, overlaySkipScratch, overlayPairs);
		drawEmitter.drawRange(cmd, planeStart, planeEnd, combinedSkipScratch, combinedPairs, combinedPairs > 0,
			slotFirstVertex, pipelineLayout, vertPush, fragPush);
	}

	private void drawVisibleStaticZones(VkCommandBuffer cmd, int layer, int plane,
		int minZoneX, int maxZoneX, int minZoneZ, int maxZoneZ,
		int[] skips, int skipPairs, boolean applySkips,
		int slot, int slotFirstVertex, long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		boolean mergeRanges = !applySkips || skipPairs == 0;
		int mergedStart = -1;
		int mergedEnd = -1;
		for (int zx = minZoneX; zx <= maxZoneX; zx++)
		{
			for (int zz = minZoneZ; zz <= maxZoneZ; zz++)
			{
				int zoneIdx = zx * zonesPerSide + zz;
				if (zoneCulled(zoneIdx))
				{
					continue;
				}
				// A rebuilt zone replaces its static counterpart even when
				// the rebuild emitted zero vertices (object removed).
				if (overlayZoneValid[slot][zoneIdx])
				{
					continue;
				}
				int count = zoneVertexCount[layer][plane][zoneIdx];
				if (count <= 0)
				{
					continue;
				}
				int start = zoneVertexStart[layer][plane][zoneIdx];
				int end = start + count;
				if (mergeRanges)
				{
					if (mergedStart < 0)
					{
						mergedStart = start;
						mergedEnd = end;
					}
					else if (start == mergedEnd)
					{
						mergedEnd = end;
					}
					else
					{
						drawEmitter.drawRange(cmd, mergedStart, mergedEnd, skips, 0, false,
							slotFirstVertex, pipelineLayout, vertPush, fragPush);
						mergedStart = start;
						mergedEnd = end;
					}
				}
				else
				{
					drawEmitter.drawRange(cmd, start, end, skips, skipPairs, true,
						slotFirstVertex, pipelineLayout, vertPush, fragPush);
				}
			}
		}
		if (mergedStart >= 0)
		{
			drawEmitter.drawRange(cmd, mergedStart, mergedEnd, skips, 0, false,
				slotFirstVertex, pipelineLayout, vertPush, fragPush);
		}
	}

	private int buildOverlayStaticSkipPairs(int layer, int plane, int slot)
	{
		int pairs = 0;
		for (int zoneIdx = 0; zoneIdx < zoneCount; zoneIdx++)
		{
			if (!overlayZoneValid[slot][zoneIdx])
			{
				continue;
			}
			int count = zoneVertexCount[layer][plane][zoneIdx];
			if (count <= 0)
			{
				continue;
			}
			if (pairs * 2 + 2 > overlaySkipScratch.length)
			{
				overlaySkipScratch = Arrays.copyOf(overlaySkipScratch, overlaySkipScratch.length * 2);
			}
			int start = zoneVertexStart[layer][plane][zoneIdx];
			overlaySkipScratch[pairs * 2] = start;
			overlaySkipScratch[pairs * 2 + 1] = start + count;
			pairs++;
		}
		return pairs;
	}

	private int mergeSkipPairs(int[] a, int aPairs, int[] b, int bPairs)
	{
		int ai = 0;
		int bi = 0;
		int out = 0;
		int pendingStart = -1;
		int pendingEnd = -1;
		while (ai < aPairs || bi < bPairs)
		{
			int start;
			int end;
			if (bi >= bPairs || (ai < aPairs && a[ai * 2] <= b[bi * 2]))
			{
				start = a[ai * 2];
				end = a[ai * 2 + 1];
				ai++;
			}
			else
			{
				start = b[bi * 2];
				end = b[bi * 2 + 1];
				bi++;
			}
			if (end <= start)
			{
				continue;
			}
			if (pendingStart < 0)
			{
				pendingStart = start;
				pendingEnd = end;
			}
			else if (start <= pendingEnd)
			{
				pendingEnd = Math.max(pendingEnd, end);
			}
			else
			{
				out = writeCombinedSkipPair(out, pendingStart, pendingEnd);
				pendingStart = start;
				pendingEnd = end;
			}
		}
		if (pendingStart >= 0)
		{
			out = writeCombinedSkipPair(out, pendingStart, pendingEnd);
		}
		return out;
	}

	private int writeCombinedSkipPair(int out, int start, int end)
	{
		if (out * 2 + 2 > combinedSkipScratch.length)
		{
			combinedSkipScratch = Arrays.copyOf(combinedSkipScratch, combinedSkipScratch.length * 2);
		}
		combinedSkipScratch[out * 2] = start;
		combinedSkipScratch[out * 2 + 1] = end;
		return out + 1;
	}

	private boolean hasOverlayRange(int slot, int layer, int plane, int zoneIdx)
	{
		return overlayZoneValid[slot][zoneIdx]
			&& overlayZoneCount[slot][layer][plane][zoneIdx] > 0;
	}
}
