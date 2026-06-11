/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import net.runelite.api.Constants;
import net.runelite.api.Tile;

final class SceneRoofInfo
{
	static final int MAX_PLANES = 4;

	final boolean visbelow;
	final int roofId;

	private SceneRoofInfo(boolean visbelow, int roofId)
	{
		this.visbelow = visbelow;
		this.roofId = roofId;
	}

	static SceneRoofInfo forTile(int[][][] roofs, byte[][][] tileSettings,
		int sourceLevel, int msx, int msy)
	{
		int mapLevel = sourceLevel;
		if (isBridge(tileSettings, msx, msy))
		{
			mapLevel++;
		}

		boolean visbelow = mapLevel < MAX_PLANES && hasTileFlag(tileSettings, mapLevel, msx, msy,
			Constants.TILE_FLAG_VIS_BELOW);
		int roofId = visbelow || mapLevel == 0 ? 0 : tileRoofIdAt(roofs, mapLevel - 1, msx, msy);
		return new SceneRoofInfo(visbelow, roofId);
	}

	static int renderLevel(Tile tile, int fallbackLevel)
	{
		if (tile == null)
		{
			return fallbackLevel;
		}
		return Math.max(0, Math.min(MAX_PLANES - 1, tile.getRenderLevel()));
	}

	private static int tileRoofIdAt(int[][][] roofs, int p, int sx, int sy)
	{
		if (roofs == null || p >= roofs.length)
		{
			return 0;
		}
		int[][] plane = roofs[p];
		if (plane == null || sx >= plane.length)
		{
			return 0;
		}
		int[] row = plane[sx];
		if (row == null || sy >= row.length)
		{
			return 0;
		}
		return row[sy];
	}

	private static boolean isBridge(byte[][][] tileSettings, int msx, int msy)
	{
		return hasTileFlag(tileSettings, 1, msx, msy, Constants.TILE_FLAG_BRIDGE);
	}

	private static boolean hasTileFlag(byte[][][] tileSettings, int plane, int x, int y, int flag)
	{
		if (tileSettings == null || plane < 0 || plane >= tileSettings.length)
		{
			return false;
		}
		byte[][] planeSettings = tileSettings[plane];
		if (planeSettings == null || x < 0 || x >= planeSettings.length)
		{
			return false;
		}
		byte[] row = planeSettings[x];
		return row != null && y >= 0 && y < row.length && (row[y] & flag) != 0;
	}
}
