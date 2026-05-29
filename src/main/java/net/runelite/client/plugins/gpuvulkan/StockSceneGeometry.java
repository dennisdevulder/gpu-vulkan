package net.runelite.client.plugins.gpuvulkan;

import java.nio.ByteBuffer;

/**
 * CPU-side metadata for the stock-style zone renderer.
 *
 * <p>The OpenGL GPU plugin gets much of its speed from uploading static scene
 * geometry as zone-local opaque/alpha ranges. This class captures that shape
 * without affecting the current renderer.</p>
 */
final class StockSceneGeometry
{
	static final int ZONE_SIZE = 8;
	static final int ZONES_PER_SIDE = SceneRenderer.ZONES_PER_SIDE;
	static final int ZONE_COUNT = ZONES_PER_SIDE * ZONES_PER_SIDE;
	static final int PLANES = 4;

	private final Range[][] opaque = newRanges();
	private final Range[][] alpha = newRanges();

	private int opaqueVertexCount;
	private int alphaVertexCount;

	void clear()
	{
		opaqueVertexCount = 0;
		alphaVertexCount = 0;
		for (int p = 0; p < PLANES; p++)
		{
			for (int z = 0; z < ZONE_COUNT; z++)
			{
				opaque[p][z].clear();
				alpha[p][z].clear();
			}
		}
	}

	Range opaqueRange(int plane, int zone)
	{
		return opaque[plane][zone];
	}

	Range alphaRange(int plane, int zone)
	{
		return alpha[plane][zone];
	}

	int beginOpaqueRange(int plane, int zone)
	{
		Range range = opaque[plane][zone];
		range.start = opaqueVertexCount;
		return opaqueVertexCount;
	}

	void endOpaqueRange(int plane, int zone)
	{
		opaque[plane][zone].count = opaqueVertexCount - opaque[plane][zone].start;
	}

	int beginAlphaRange(int plane, int zone)
	{
		Range range = alpha[plane][zone];
		range.start = alphaVertexCount;
		return alphaVertexCount;
	}

	void endAlphaRange(int plane, int zone)
	{
		alpha[plane][zone].count = alphaVertexCount - alpha[plane][zone].start;
	}

	int appendOpaque(ByteBuffer dst, int zoneBaseX, int zoneBaseZ,
					 int x, int y, int z, int hsl, int texture, int u, int v,
					 int alpha, int bias)
	{
		writeVertex(dst, opaqueVertexCount++, zoneBaseX, zoneBaseZ,
			x, y, z, hsl, texture, u, v, alpha, bias);
		return opaqueVertexCount;
	}

	int appendAlpha(ByteBuffer dst, int zoneBaseX, int zoneBaseZ,
					int x, int y, int z, int hsl, int texture, int u, int v,
					int alpha, int bias)
	{
		writeVertex(dst, alphaVertexCount++, zoneBaseX, zoneBaseZ,
			x, y, z, hsl, texture, u, v, alpha, bias);
		return alphaVertexCount;
	}

	int opaqueVertexCount()
	{
		return opaqueVertexCount;
	}

	int alphaVertexCount()
	{
		return alphaVertexCount;
	}

	static int zoneIndex(int sceneX, int sceneY)
	{
		return (sceneX / ZONE_SIZE) * ZONES_PER_SIDE + sceneY / ZONE_SIZE;
	}

	static int zoneBaseX(int zone)
	{
		return (zone / ZONES_PER_SIDE) * ZONE_SIZE * 128;
	}

	static int zoneBaseZ(int zone)
	{
		return (zone % ZONES_PER_SIDE) * ZONE_SIZE * 128;
	}

	private static void writeVertex(ByteBuffer dst, int vertexIndex,
									int zoneBaseX, int zoneBaseZ,
									int x, int y, int z, int hsl,
									int texture, int u, int v,
									int alpha, int bias)
	{
		int offset = vertexIndex * StockSceneVertexLayout.VERTEX_STRIDE;
		dst.putShort(offset + StockSceneVertexLayout.OFFSET_POS, (short) (x - zoneBaseX));
		dst.putShort(offset + StockSceneVertexLayout.OFFSET_POS + 2, (short) y);
		dst.putShort(offset + StockSceneVertexLayout.OFFSET_POS + 4, (short) (z - zoneBaseZ));
		dst.putShort(offset + StockSceneVertexLayout.OFFSET_POS + 6, (short) 0);
		dst.putInt(offset + StockSceneVertexLayout.OFFSET_ABHSL,
			((alpha & 0xFF) << 24) | ((bias & 0xFF) << 16) | (hsl & 0xFFFF));
		dst.putShort(offset + StockSceneVertexLayout.OFFSET_TEX_UV, (short) texture);
		dst.putShort(offset + StockSceneVertexLayout.OFFSET_TEX_UV + 2, (short) u);
		dst.putShort(offset + StockSceneVertexLayout.OFFSET_TEX_UV + 4, (short) v);
		dst.putShort(offset + StockSceneVertexLayout.OFFSET_TEX_UV + 6, (short) 0);
	}

	private static Range[][] newRanges()
	{
		Range[][] ranges = new Range[PLANES][ZONE_COUNT];
		for (int p = 0; p < PLANES; p++)
		{
			for (int z = 0; z < ZONE_COUNT; z++)
			{
				ranges[p][z] = new Range();
			}
		}
		return ranges;
	}

	static final class Range
	{
		int start;
		int count;

		void clear()
		{
			start = 0;
			count = 0;
		}
	}
}
