/*
 * Tile paint/model geometry emission for SceneRenderer.
 */
package com.gpuvulkan;

import net.runelite.api.Constants;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.WorldView;

final class SceneTileEmitter
{
	private static final int HSL_HIDDEN = 12345678;
	private static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;

	private final Sink sink;

	SceneTileEmitter(Sink sink)
	{
		this.sink = sink;
	}

	void captureTilePaint(Scene scene, SceneTilePaint paint, int plane, int sx, int sy)
	{
		int neColor = paint.getNeColor();
		if (neColor == HSL_HIDDEN) return;

		int swColor = paint.getSwColor();
		int seColor = paint.getSeColor();
		int nwColor = paint.getNwColor();

		int[][][] tileHeights = scene.getTileHeights();
		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET : 0;
		int ex = sx + offset;
		int ey = sy + offset;
		if (!hasTileHeights(tileHeights, plane, ex, ey))
		{
			return;
		}
		int swH = tileHeights[plane][ex    ][ey    ];
		int seH = tileHeights[plane][ex + 1][ey    ];
		int neH = tileHeights[plane][ex + 1][ey + 1];
		int nwH = tileHeights[plane][ex    ][ey + 1];

		int x0 = sx << 7, x1 = x0 + 128;
		int z0 = sy << 7, z1 = z0 + 128;
		int texLayer = paint.getTexture() + 1;

		if (!sink.reserveVertices(6)) return;

		sink.writeHslVert(x0, swH, z0, swColor, 0f, 0f, texLayer);
		sink.writeHslVert(x1, seH, z0, seColor, 1f, 0f, texLayer);
		sink.writeHslVert(x1, neH, z1, neColor, 1f, 1f, texLayer);

		sink.writeHslVert(x0, swH, z0, swColor, 0f, 0f, texLayer);
		sink.writeHslVert(x1, neH, z1, neColor, 1f, 1f, texLayer);
		sink.writeHslVert(x0, nwH, z1, nwColor, 0f, 1f, texLayer);

		sink.addVertices(6);
	}

	private static boolean hasTileHeights(int[][][] tileHeights, int plane, int x, int y)
	{
		if (tileHeights == null || plane < 0 || plane >= tileHeights.length)
		{
			return false;
		}
		int[][] heights = tileHeights[plane];
		if (heights == null || x < 0 || x + 1 >= heights.length)
		{
			return false;
		}
		int[] row = heights[x];
		int[] nextRow = heights[x + 1];
		return row != null && nextRow != null && y >= 0 && y + 1 < row.length && y + 1 < nextRow.length;
	}

	void captureTileModel(SceneTileModel model, int sx, int sy)
	{
		int[] faceX = model.getFaceX();
		int[] faceY = model.getFaceY();
		int[] faceZ = model.getFaceZ();
		if (faceX == null) return;

		int[] vertexX = model.getVertexX();
		int[] vertexY = model.getVertexY();
		int[] vertexZ = model.getVertexZ();
		int[] colorA = model.getTriangleColorA();
		int[] colorB = model.getTriangleColorB();
		int[] colorC = model.getTriangleColorC();
		int[] triangleTextures = model.getTriangleTextureId();

		float lx = sx << 7;
		float lz = sy << 7;

		int faces = faceX.length;
		if (!sink.reserveVertices(faces * 3)) return;

		for (int i = 0; i < faces; i++)
		{
			int a = colorA[i];
			if (a == HSL_HIDDEN) continue;
			int b = colorB[i], c = colorC[i];
			int v0 = faceX[i], v1 = faceY[i], v2 = faceZ[i];
			int texLayer = triangleTextures != null && triangleTextures[i] != -1
				? triangleTextures[i] + 1 : 0;
			float u0 = (vertexX[v0] - lx) / 128f, w0 = (vertexZ[v0] - lz) / 128f;
			float u1 = (vertexX[v1] - lx) / 128f, w1 = (vertexZ[v1] - lz) / 128f;
			float u2 = (vertexX[v2] - lx) / 128f, w2 = (vertexZ[v2] - lz) / 128f;
			sink.writeHslVert(vertexX[v0], vertexY[v0], vertexZ[v0], a, u0, w0, texLayer);
			sink.writeHslVert(vertexX[v1], vertexY[v1], vertexZ[v1], b, u1, w1, texLayer);
			sink.writeHslVert(vertexX[v2], vertexY[v2], vertexZ[v2], c, u2, w2, texLayer);
			sink.addVertices(3);
		}
	}

	interface Sink
	{
		boolean reserveVertices(int vertices);

		void addVertices(int vertices);

		void writeHslVert(float x, float y, float z, int hsl16, float u, float v, int texLayer);
	}
}
