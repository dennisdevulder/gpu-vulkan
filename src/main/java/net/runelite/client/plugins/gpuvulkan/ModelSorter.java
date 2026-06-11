/*
 * The bucket-sort algorithm and the per-vertex projection / orient-rotate
 * scaffolding below are ported from RuneLite's
 * {@code net.runelite.client.plugins.gpu.FacePrioritySorter} (BSD-2-Clause).
 * Original copyright + license:
 *
 *   Copyright (c) 2018, Adam <Adam@sigterm.info>
 *   All rights reserved.
 *
 *   Redistribution and use in source and binary forms, with or without
 *   modification, are permitted provided that the following conditions are met:
 *
 *   1. Redistributions of source code must retain the above copyright notice, this
 *      list of conditions and the following disclaimer.
 *   2. Redistributions in binary form must reproduce the above copyright notice,
 *      this list of conditions and the following disclaimer in the documentation
 *      and/or other materials provided with the distribution.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 *   ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *   WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *   DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 *   ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *   (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *   ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *   (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Adapted to Vulkan: stock writes pre-shuffled vertex bytes into an
 * {@code IntBuffer}; we return ordered face indices via {@link #sortedFaces}
 * and let {@link SceneModelEmitter#captureModelSorted} emit each face into the
 * shared vertex buffer.
 */
package net.runelite.client.plugins.gpuvulkan;

import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;

final class ModelSorter
{
	// Sized to mirror stock FacePrioritySorter.
	static final int MAX_VERTEX_COUNT = 6500;
	static final int MAX_FACE_COUNT = 8192;
	static final int MAX_DIAMETER = 6000;
	private static final int MAX_FACES_PER_PRIORITY = 4000;

	// --- per-vertex scratch (filled by sort, read by SceneRenderer when emitting) ---
	final float[] localX = new float[MAX_VERTEX_COUNT];
	final float[] localY = new float[MAX_VERTEX_COUNT];
	final float[] localZ = new float[MAX_VERTEX_COUNT];
	private final float[] projX = new float[MAX_VERTEX_COUNT];
	private final float[] projY = new float[MAX_VERTEX_COUNT];
	private final int[] distances = new int[MAX_VERTEX_COUNT];
	private final float[] projectScratch = new float[3];

	// --- per-face Z bucket linked list ---
	private final char[] zsortHead = new char[MAX_DIAMETER];
	private final char[] zsortTail = new char[MAX_DIAMETER];
	private final char[] zsortNext = new char[MAX_FACE_COUNT];
	private final int[] zsortStamp = new int[MAX_DIAMETER];
	private int currentStamp;

	// --- output: face indices in back-to-front draw order ---
	final int[] sortedFaces = new int[MAX_FACE_COUNT];
	int sortedCount;

	private final int[] numOfPriority = new int[12];
	private final int[] eq10 = new int[MAX_FACES_PER_PRIORITY];
	private final int[] eq11 = new int[MAX_FACES_PER_PRIORITY];
	private final int[] lt10 = new int[12];
	private final int[][] orderedFaces = new int[12][MAX_FACES_PER_PRIORITY];

	// Depth-bucket bounds written by bucketFacesByDepth.
	private int bucketMinFz;
	private int bucketMaxFz;

	// Priority-10/11 interleave cursor used by writePriorityOrder's drains.
	private int[] dynFaces;
	private int[] dynFaceDistances;
	private int dynDrawn;
	private int dynCount;
	private int dynDistance;
	private int outCursor;

	/**
	 * Project the model, bucket its faces by camera depth, and write the
	 * back-to-front face order into {@link #sortedFaces} (length
	 * {@link #sortedCount}). After a successful call, {@link #localX} /
	 * {@link #localY} / {@link #localZ} hold each vertex's world-space
	 * position with orientation already applied.
	 *
	 * <p>Returns {@code false} when stock would have skipped the model:
	 * <ul>
	 *   <li>any vertex projects to z &lt; 50 (camera near-clip — same value
	 *       stock uses; gives us the near-plane geometry cull listed as
	 *       gap #7 for free);</li>
	 *   <li>{@code model.getDiameter() >= MAX_DIAMETER};</li>
	 *   <li>{@code model.getVerticesCount() > MAX_VERTEX_COUNT}.</li>
	 * </ul>
	 *
	 * <p>Faces with {@code faceColors3[f] == -2} (engine "skip" sentinel)
	 * and back-face-culled faces are dropped here.
	 */
	boolean sort(Projection proj, Model m, int orientation, int wx, int wy, int wz)
	{
		return sort(proj, m, orientation, wx, wy, wz, false);
	}

	boolean sort(Projection proj, Model m, int orientation, int wx, int wy, int wz, boolean prioritySort)
	{
		sortedCount = 0;

		final int vertexCount = m.getVerticesCount();
		if (vertexCount > MAX_VERTEX_COUNT)
		{
			return false;
		}

		final float[] vxs = m.getVerticesX();
		final float[] vys = m.getVerticesY();
		final float[] vzs = m.getVerticesZ();
		if (vxs == null || vys == null || vzs == null)
		{
			return false;
		}

		final int faceCount = Math.min(m.getFaceCount(), MAX_FACE_COUNT);
		final int[] fa = m.getFaceIndices1();
		final int[] fb = m.getFaceIndices2();
		final int[] fc = m.getFaceIndices3();
		if (fa == null || fb == null || fc == null)
		{
			return false;
		}

		final int diameter = m.getDiameter();
		if (diameter <= 0 || diameter >= MAX_DIAMETER)
		{
			return false;
		}

		if (!transformAndProject(proj, vxs, vys, vzs, vertexCount, orientation, wx, wy, wz))
		{
			return false;
		}

		int stamp = ++currentStamp;
		if (stamp == 0)
		{
			java.util.Arrays.fill(zsortStamp, 0);
			stamp = ++currentStamp;
		}
		bucketFacesByDepth(faceCount, fa, fb, fc, m.getFaceColors3(), m.getRadius(), diameter, stamp);

		final byte[] faceRenderPriorities = m.getFaceRenderPriorities();
		if (faceRenderPriorities == null || !prioritySort)
		{
			writeBasicOrder(stamp, bucketMinFz, bucketMaxFz);
		}
		else if (!writePriorityOrder(faceRenderPriorities, stamp, bucketMinFz, bucketMaxFz))
		{
			writeBasicOrder(stamp, bucketMinFz, bucketMaxFz);
		}
		return true;
	}

	// Rotates each vertex by orientation, translates to world, fills local*/
	// proj*/distances. False = a vertex projects to z < 50 and stock drops
	// the whole model — mirror that (this is also the near-plane cull).
	private boolean transformAndProject(Projection proj, float[] vxs, float[] vys, float[] vzs,
		int vertexCount, int orientation, int wx, int wy, int wz)
	{
		float orientSine = 0f;
		float orientCosine = 0f;
		if (orientation != 0)
		{
			// Perspective.SINE/COSINE are fixed-point (1<<16) over [0, 2048).
			orientSine = Perspective.SINE[orientation & 0x7FF] / 65536f;
			orientCosine = Perspective.COSINE[orientation & 0x7FF] / 65536f;
		}

		// Depth anchored at the model centre so the bucket index fits [0, diameter).
		float[] p = proj.project(wx, wy, wz, projectScratch);
		int zero = (int) p[2];

		for (int v = 0; v < vertexCount; v++)
		{
			float vx = vxs[v];
			float vy = vys[v];
			float vz = vzs[v];

			if (orientation != 0)
			{
				float x0 = vx;
				vx = vz * orientSine + x0 * orientCosine;
				vz = vz * orientCosine - x0 * orientSine;
			}

			vx += wx;
			vy += wy;
			vz += wz;

			localX[v] = vx;
			localY[v] = vy;
			localZ[v] = vz;

			p = proj.project(vx, vy, vz, projectScratch);
			if (p[2] < 50f)
			{
				return false;
			}

			projX[v] = p[0] / p[2];
			projY[v] = p[1] / p[2];
			distances[v] = (int) p[2] - zero;
		}
		return true;
	}

	// Drops skip-sentinel (-2) and back-facing faces, FIFO-chains the rest
	// into per-depth buckets; writes bucketMinFz/bucketMaxFz.
	private void bucketFacesByDepth(int faceCount, int[] fa, int[] fb, int[] fc,
		int[] faceColors3, int radius, int diameter, int stamp)
	{
		int minFz = diameter;
		int maxFz = 0;

		for (char f = 0; f < faceCount; f++)
		{
			if (faceColors3 != null && faceColors3[f] == -2)
			{
				continue;
			}

			final int v1 = fa[f];
			final int v2 = fb[f];
			final int v3 = fc[f];

			final float aX = projX[v1], aY = projY[v1];
			final float bX = projX[v2], bY = projY[v2];
			final float cX = projX[v3], cY = projY[v3];

			if ((aX - bX) * (cY - bY) - (cX - bX) * (aY - bY) <= 0)
			{
				continue;
			}

			int distance = radius + (distances[v1] + distances[v2] + distances[v3]) / 3;
			if (distance < 0)
			{
				distance = 0;
			}
			else if (distance >= diameter)
			{
				distance = diameter - 1;
			}

			if (zsortStamp[distance] != stamp)
			{
				zsortStamp[distance] = stamp;
				zsortHead[distance] = f;
				zsortNext[f] = (char) -1;
			}
			else
			{
				zsortNext[zsortTail[distance]] = f;
				zsortNext[f] = (char) -1;
			}
			zsortTail[distance] = f;

			if (distance < minFz) minFz = distance;
			if (distance > maxFz) maxFz = distance;
		}

		bucketMinFz = minFz;
		bucketMaxFz = maxFz;
	}

	private void writeBasicOrder(int stamp, int minFz, int maxFz)
	{
		int out = 0;
		for (int i = maxFz; i >= minFz; i--)
		{
			if (zsortStamp[i] != stamp)
			{
				continue;
			}
			for (char f = zsortHead[i]; f != (char) -1; f = zsortNext[f])
			{
				sortedFaces[out++] = f;
			}
		}
		sortedCount = out;
	}

	// Stock's 12-bucket priority interleave: priorities 0-9 emit in order
	// with the priority-10/11 "dynamic" faces drained in front of buckets
	// 0/3/5 based on the avg12/avg34/avg68 depth averages.
	private boolean writePriorityOrder(byte[] faceRenderPriorities, int stamp, int minFz, int maxFz)
	{
		if (!binFacesByPriority(faceRenderPriorities, stamp, minFz, maxFz))
		{
			return false;
		}

		int avg12 = 0;
		if (numOfPriority[1] > 0 || numOfPriority[2] > 0)
		{
			avg12 = (lt10[1] + lt10[2]) / (numOfPriority[1] + numOfPriority[2]);
		}
		int avg34 = 0;
		if (numOfPriority[3] > 0 || numOfPriority[4] > 0)
		{
			avg34 = (lt10[3] + lt10[4]) / (numOfPriority[3] + numOfPriority[4]);
		}
		int avg68 = 0;
		if (numOfPriority[6] > 0 || numOfPriority[8] > 0)
		{
			avg68 = (lt10[8] + lt10[6]) / (numOfPriority[8] + numOfPriority[6]);
		}

		resetDynamicCursor();
		for (int pri = 0; pri < 10; pri++)
		{
			if (pri == 0) drainDynamicFacesAbove(avg12);
			if (pri == 3) drainDynamicFacesAbove(avg34);
			if (pri == 5) drainDynamicFacesAbove(avg68);

			int priNum = numOfPriority[pri];
			int[] priFaces = orderedFaces[pri];
			for (int faceIdx = 0; faceIdx < priNum; faceIdx++)
			{
				sortedFaces[outCursor++] = priFaces[faceIdx];
			}
		}
		while (dynDistance != -1000)
		{
			appendDynamicFace();
		}

		sortedCount = outCursor;
		return true;
	}

	// False = a priority index is out of range or a bucket overflows; the
	// caller falls back to plain depth order.
	private boolean binFacesByPriority(byte[] faceRenderPriorities, int stamp, int minFz, int maxFz)
	{
		java.util.Arrays.fill(numOfPriority, 0);
		java.util.Arrays.fill(lt10, 0);

		for (int i = maxFz; i >= minFz; i--)
		{
			if (zsortStamp[i] != stamp)
			{
				continue;
			}
			for (char face = zsortHead[i]; face != (char) -1; face = zsortNext[face])
			{
				if (face >= faceRenderPriorities.length)
				{
					return false;
				}

				int pri = faceRenderPriorities[face] & 0xFF;
				if (pri >= orderedFaces.length)
				{
					return false;
				}

				int distIdx = numOfPriority[pri]++;
				if (distIdx >= MAX_FACES_PER_PRIORITY)
				{
					return false;
				}

				orderedFaces[pri][distIdx] = face;
				if (pri < 10)
				{
					lt10[pri] += i;
				}
				else if (pri == 10)
				{
					eq10[distIdx] = i;
				}
				else
				{
					eq11[distIdx] = i;
				}
			}
		}
		return true;
	}

	private void resetDynamicCursor()
	{
		outCursor = 0;
		dynDrawn = 0;
		dynCount = numOfPriority[10];
		dynFaces = orderedFaces[10];
		dynFaceDistances = eq10;
		if (dynDrawn == dynCount)
		{
			dynDrawn = 0;
			dynCount = numOfPriority[11];
			dynFaces = orderedFaces[11];
			dynFaceDistances = eq11;
		}
		dynDistance = dynDrawn < dynCount ? dynFaceDistances[dynDrawn] : -1000;
	}

	private void drainDynamicFacesAbove(int threshold)
	{
		while (dynDistance > threshold)
		{
			appendDynamicFace();
		}
	}

	private void appendDynamicFace()
	{
		sortedFaces[outCursor++] = dynFaces[dynDrawn++];
		if (dynDrawn == dynCount && dynFaces != orderedFaces[11])
		{
			dynDrawn = 0;
			dynCount = numOfPriority[11];
			dynFaces = orderedFaces[11];
			dynFaceDistances = eq11;
		}
		dynDistance = dynDrawn < dynCount ? dynFaceDistances[dynDrawn] : -1000;
	}

}
