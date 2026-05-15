/*
 * Port of stock GPU plugin's FacePrioritySorter (CPU-side face sort).
 *
 * Stock GpuPlugin runs this once per dynamic model per frame: project the
 * model's vertices through the engine-supplied Projection, bucket each face
 * by camera-space depth, then emit triangles back-to-front. We don't ship
 * the per-priority interleave yet (stock's prioritySort path) — it only
 * fires for the rare RENDERMODE_SORTED_NO_DEPTH renderables.
 *
 * This class owns scratch arrays so we don't allocate per-call. One instance
 * is shared by SceneRenderer; the sort is run sequentially from the Client
 * thread, so no thread-safety concerns.
 */
package net.runelite.client.plugins.gpuvulkan;

import java.util.Arrays;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;

final class ModelSorter
{
	// Sized to mirror stock FacePrioritySorter.
	static final int MAX_VERTEX_COUNT = 6500;
	static final int MAX_FACE_COUNT = 8192;
	static final int MAX_DIAMETER = 6000;

	// --- per-vertex scratch (filled by sort, read by SceneRenderer when emitting) ---
	final float[] localX = new float[MAX_VERTEX_COUNT];
	final float[] localY = new float[MAX_VERTEX_COUNT];
	final float[] localZ = new float[MAX_VERTEX_COUNT];
	private final float[] projX = new float[MAX_VERTEX_COUNT];
	private final float[] projY = new float[MAX_VERTEX_COUNT];
	private final int[] distances = new int[MAX_VERTEX_COUNT];

	// --- per-face Z bucket linked list ---
	private final char[] zsortHead = new char[MAX_DIAMETER];
	private final char[] zsortTail = new char[MAX_DIAMETER];
	private final char[] zsortNext = new char[MAX_FACE_COUNT];

	// --- output: face indices in back-to-front draw order ---
	final int[] sortedFaces = new int[MAX_FACE_COUNT];
	int sortedCount;

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
			// Same lookup tables as stock — Perspective.SINE/COSINE are fixed-point
			// (1<<16) over [0, 2048). Divide by 65536 to get a unit sine/cosine.
			orientSine = Perspective.SINE[orientation & 0x7FF] / 65536f;
			orientCosine = Perspective.COSINE[orientation & 0x7FF] / 65536f;
		}

		// Anchor depth at the model's centre — `distances[v]` then holds the
		// depth delta from centre, matching stock so the bucket index fits
		// in [0, diameter).
		float[] p = proj.project(wx, wy, wz);
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

			p = proj.project(vx, vy, vz);
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

		Arrays.fill(zsortHead, 0, diameter, (char) -1);
		Arrays.fill(zsortTail, 0, diameter, (char) -1);

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

			if (zsortTail[distance] == (char) -1)
			{
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

		// Walk far → near, mirroring stock's `for (int i = maxFz; i >= minFz; --i)`.
		int out = 0;
		for (int i = maxFz; i >= minFz; i--)
		{
			for (char f = zsortHead[i]; f != (char) -1; f = zsortNext[f])
			{
				sortedFaces[out++] = f;
			}
		}
		sortedCount = out;
		return true;
	}
}
