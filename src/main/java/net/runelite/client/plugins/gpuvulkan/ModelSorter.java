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

		final int[] faceColors3 = m.getFaceColors3();
		final byte[] faceRenderPriorities = m.getFaceRenderPriorities();

		float orientSine = 0f;
		float orientCosine = 0f;
		if (orientation != 0)
		{
			// Same lookup tables as stock — Perspective.SINE/COSINE are fixed-point
			// (1<<16) over [0, 2048). Divide by 65536 to get a unit sine/cosine.
			orientSine = Perspective.SINE[orientation & 0x7FF] / 65536f;
			orientCosine = Perspective.COSINE[orientation & 0x7FF] / 65536f;
		}

		// Anchor depth at the model's centre — `distances[v]` then holds the
		// depth delta from centre, matching stock so the bucket index fits
		// in [0, diameter).
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
				// Near-plane reject. Stock drops the whole model — mirror that.
				return false;
			}

			projX[v] = p[0] / p[2];
			projY[v] = p[1] / p[2];
			distances[v] = (int) p[2] - zero;
		}

		final int diameter = m.getDiameter();
		final int radius = m.getRadius();
		if (diameter <= 0 || diameter >= MAX_DIAMETER)
		{
			return false;
		}

		int stamp = ++currentStamp;
		if (stamp == 0)
		{
			java.util.Arrays.fill(zsortStamp, 0);
			stamp = ++currentStamp;
		}

		int minFz = diameter;
		int maxFz = 0;

		for (char f = 0; f < faceCount; f++)
		{
			// Engine "skip this face" sentinel.
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

			// Screen-space cross product: positive = front-facing, drop the rest.
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

		if (faceRenderPriorities == null || !prioritySort)
		{
			writeBasicOrder(stamp, minFz, maxFz);
		}
		else if (!writePriorityOrder(faceRenderPriorities, stamp, minFz, maxFz))
		{
			writeBasicOrder(stamp, minFz, maxFz);
		}
		return true;
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

	private boolean writePriorityOrder(byte[] faceRenderPriorities, int stamp, int minFz, int maxFz)
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

		int out = 0;
		int drawnFaces = 0;
		int numDynFaces = numOfPriority[10];
		int[] dynFaces = orderedFaces[10];
		int[] dynFaceDistances = eq10;
		if (drawnFaces == numDynFaces)
		{
			drawnFaces = 0;
			numDynFaces = numOfPriority[11];
			dynFaces = orderedFaces[11];
			dynFaceDistances = eq11;
		}

		int currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;

		for (int pri = 0; pri < 10; pri++)
		{
			while (pri == 0 && currFaceDistance > avg12)
			{
				out = appendDynamicFace(out, dynFaces, drawnFaces++);
				if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11])
				{
					drawnFaces = 0;
					numDynFaces = numOfPriority[11];
					dynFaces = orderedFaces[11];
					dynFaceDistances = eq11;
				}
				currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;
			}

			while (pri == 3 && currFaceDistance > avg34)
			{
				out = appendDynamicFace(out, dynFaces, drawnFaces++);
				if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11])
				{
					drawnFaces = 0;
					numDynFaces = numOfPriority[11];
					dynFaces = orderedFaces[11];
					dynFaceDistances = eq11;
				}
				currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;
			}

			while (pri == 5 && currFaceDistance > avg68)
			{
				out = appendDynamicFace(out, dynFaces, drawnFaces++);
				if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11])
				{
					drawnFaces = 0;
					numDynFaces = numOfPriority[11];
					dynFaces = orderedFaces[11];
					dynFaceDistances = eq11;
				}
				currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;
			}

			int priNum = numOfPriority[pri];
			int[] priFaces = orderedFaces[pri];
			for (int faceIdx = 0; faceIdx < priNum; faceIdx++)
			{
				sortedFaces[out++] = priFaces[faceIdx];
			}
		}

		while (currFaceDistance != -1000)
		{
			out = appendDynamicFace(out, dynFaces, drawnFaces++);
			if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11])
			{
				drawnFaces = 0;
				dynFaces = orderedFaces[11];
				numDynFaces = numOfPriority[11];
				dynFaceDistances = eq11;
			}
			currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;
		}

		sortedCount = out;
		return true;
	}

	private int appendDynamicFace(int out, int[] dynFaces, int faceIdx)
	{
		sortedFaces[out++] = dynFaces[faceIdx];
		return out;
	}

	boolean cullOnly(Projection proj, Model m, int orientation, int wx, int wy, int wz)
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

		final int[] faceColors3 = m.getFaceColors3();

		float orientSine = 0f;
		float orientCosine = 0f;
		if (orientation != 0)
		{
			orientSine = Perspective.SINE[orientation & 0x7FF] / 65536f;
			orientCosine = Perspective.COSINE[orientation & 0x7FF] / 65536f;
		}

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

			float[] p = proj.project(vx, vy, vz, projectScratch);
			if (p[2] < 50f)
			{
				return false;
			}

			projX[v] = p[0] / p[2];
			projY[v] = p[1] / p[2];
		}

		int out = 0;
		for (int f = 0; f < faceCount; f++)
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

			sortedFaces[out++] = f;
		}

		sortedCount = out;
		return true;
	}
}
